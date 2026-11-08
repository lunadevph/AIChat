package com.android5.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID

/** OAuth credentials stay in Keystore-encrypted, no-backup app storage, separate from API keys. */
internal class ChatGptAuth private constructor(private val context: Context) {
    data class Profile(val clientId: String, val subject: String, val email: String, val connected: Boolean, val planEnabled: Boolean)
    private data class Tokens(val access: String = "", val refresh: String? = null, val id: String = "",
        val expiresAt: Long = 0, val earliestRefreshAt: Long = 0, val scopes: Set<String> = emptySet()) {
        override fun toString() = "[protected credentials]"
    }
    private data class Registration(val clientId: String = "", val subject: String = "", val email: String = "",
        val tokens: Tokens? = null, val models: List<ModelInfo> = emptyList())
    private data class Store(val hostId: String = "urn:uuid:${UUID.randomUUID()}", val accounts: List<Registration> = emptyList(),
        val active: String? = null, val pendingClient: String? = null)
    private val gson = Gson()
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt-credentials.enc"))
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var store = load()
    val revision = MutableStateFlow(0L)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private val signingOut = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val profiles: List<Profile> get() = store.accounts.map { Profile(it.clientId, it.subject, it.email,
        it.tokens != null && it.clientId !in signingOut, ChatGptOAuth.permitsInference(it.tokens?.scopes.orEmpty())) }
    val activeClientId: String? get() = store.active
    val usable: Boolean get() = profiles.any { it.clientId == store.active && it.connected && it.planEnabled }
    fun ensureActive(clientId: String) {
        if (store.active != clientId || clientId in signingOut || !usable) throw CancellationException("ChatGPT account changed or signed out")
    }
    fun cachedModels(): List<ModelInfo> = store.accounts.firstOrNull { it.clientId == store.active }?.models.orEmpty()

    private fun load(): Store = runCatching {
        file.openRead().use { input ->
            val encrypted = input.readBytes().toString(Charsets.UTF_8)
            require(encrypted.startsWith("enc:v1:"))
            val plain = KeyStoreHelper.decrypt(encrypted) ?: error("Cannot unlock ChatGPT credentials")
            gson.fromJson(plain, Store::class.java)
        }
    }.getOrNull() ?: Store()

    private fun save(next: Store) {
        val encrypted = KeyStoreHelper.encrypt(gson.toJson(next)) ?: error("Could not protect ChatGPT credentials")
        val output = file.startWrite()
        try { output.write(encrypted.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        store = next
        revision.value++
    }

    fun cancelSignIn() { job?.cancel() }
    fun signIn(clientId: String? = null, requestPlanConsent: Boolean = false) {
        if (busy.value) return
        busy.value = true; error.value = null
        val previousProvider = SettingsManager(context).selectedProvider
        job = scope.launch {
            try {
                val id = performSignIn(clientId, requestPlanConsent)
                if (profiles.first { it.clientId == id }.planEnabled && SettingsManager(context).selectedProvider == previousProvider) activate(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message?.take(800) ?: context.getString(R.string.chatgpt_signin_error) }
            finally { busy.value = false; job = null }
        }
    }

    fun select(clientId: String) {
        if (busy.value) return
        busy.value = true; error.value = null
        job = scope.launch {
            try { activate(clientId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message?.take(800) }
            finally { busy.value = false; job = null }
        }
    }

    suspend fun activate(clientId: String) {
        val token = accessToken(clientId, requireActive = false)
        val models = catalog(clientId, token)
        require(models.isNotEmpty()) { context.getString(R.string.chatgpt_no_models) }
        mutex.withLock {
            store.active?.takeIf { it != clientId }?.let(ChatGptHttp::cancelAccount)
            signingOut.remove(clientId)
            save(store.copy(active = clientId, accounts = store.accounts.map { if (it.clientId == clientId) it.copy(models = models) else it }))
        }
        SettingsManager(context).apply {
            selectedProvider = ChatGptOAuth.PROVIDER
            if (models.none { it.id == selectedModel }) selectedModel = models.first().id
        }
    }

    suspend fun listModels(clientId: String? = activeClientId): List<ModelInfo> {
        val id = requireNotNull(clientId) { context.getString(R.string.chatgpt_signin_required) }
        return catalog(id, accessToken(id))
    }

    private suspend fun catalog(id: String, token: String): List<ModelInfo> = ChatGptHttp.use(Request.Builder()
        .url("${ChatGptOAuth.RESOURCE}/models").header("Authorization", "Bearer $token").build(), id) { response ->
        if (!response.isSuccessful) throw ChatGptHttp.failure(response)
        ChatGptModels.parse(JsonParser.parseString(response.body!!.string()).asJsonObject)
    }

    suspend fun accessToken(clientId: String, requireActive: Boolean = true): String = mutex.withLock {
        if (requireActive) ensureActive(clientId)
        val account = store.accounts.firstOrNull { it.clientId == clientId } ?: error(context.getString(R.string.chatgpt_signin_required))
        val tokens = account.tokens ?: error(context.getString(R.string.chatgpt_signin_required))
        require(ChatGptOAuth.permitsInference(tokens.scopes)) { context.getString(R.string.chatgpt_plan_permission) }
        val now = System.currentTimeMillis() / 1000
        if (tokens.expiresAt > now + 60 || (tokens.earliestRefreshAt > now && tokens.expiresAt > now)) return@withLock tokens.access
        val refresh = tokens.refresh ?: error(context.getString(R.string.chatgpt_signin_required))
        val renewed = try {
            val json = tokenRequest(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", clientId)
                .add("refresh_token", refresh).add("resource", ChatGptOAuth.RESOURCE).build())
            json["id_token"]?.takeIf { !it.isJsonNull }?.asString?.let { idToken ->
                val claims = verify(idToken, clientId, null)
                require(claims["sub"].asString == account.subject) { "ChatGPT account identity changed" }
            }
            tokens(json, tokens)
        } catch (e: ChatGptApiException) {
            if (e.errorCode in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) {
                ChatGptHttp.cancelAccount(clientId)
                save(store.copy(accounts = store.accounts.map { if (it.clientId == clientId) it.copy(tokens = null) else it }))
            }
            throw e
        }
        save(store.copy(accounts = store.accounts.map { if (it.clientId == clientId) it.copy(tokens = renewed) else it }))
        require(ChatGptOAuth.permitsInference(renewed.scopes)) { context.getString(R.string.chatgpt_plan_permission) }
        renewed.access
    }

    fun signOut(clientId: String) {
        if (busy.value) return
        busy.value = true; error.value = null
        signingOut.add(clientId)
        revision.value++
        ChatGptHttp.cancelAccount(clientId)
        job = scope.launch {
            try {
                var revoked = true
                mutex.withLock {
                    val refresh = store.accounts.firstOrNull { it.clientId == clientId }?.tokens?.refresh
                    if (refresh != null) {
                        revoked = false
                        for (attempt in 0..1) {
                            try {
                                val endpoint = discovery()["revocation_endpoint"].asString
                                trustedAuthUrl(endpoint)
                                ChatGptHttp.use(Request.Builder().url(endpoint).post(FormBody.Builder().add("token", refresh)
                                    .add("token_type_hint", "refresh_token").add("client_id", clientId).build()).build()) {
                                    if (!it.isSuccessful) throw ChatGptHttp.failure(it)
                                }
                                revoked = true; break
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { if (attempt == 0) delay(500) }
                        }
                    }
                    save(store.copy(accounts = store.accounts.map { if (it.clientId == clientId) it.copy(tokens = null) else it }))
                }
                if (!revoked) error.value = context.getString(R.string.chatgpt_revoke_unconfirmed)
            } catch (e: CancellationException) {
                val cleared = withContext(NonCancellable) {
                    runCatching { mutex.withLock { save(store.copy(accounts = store.accounts.map { if (it.clientId == clientId) it.copy(tokens = null) else it })) } }.isSuccess
                }
                error.value = context.getString(if (cleared) R.string.chatgpt_revoke_unconfirmed else R.string.chatgpt_local_signout_failed)
                throw e
            }
            catch (e: Exception) { error.value = e.message?.take(800) ?: context.getString(R.string.chatgpt_signin_error) }
            finally { busy.value = false; job = null }
        }
    }

    private suspend fun performSignIn(selected: String?, consent: Boolean): String = withContext(Dispatchers.IO) {
        val previous = store.accounts.firstOrNull { it.clientId == selected }
        val client = previous?.clientId ?: store.pendingClient
        mutex.withLock { save(store) } // Persist this installation's stable host identifier before authorization.
        val state = ChatGptOAuth.random(); val nonce = ChatGptOAuth.random(); val verifier = ChatGptOAuth.random()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
        val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
        coroutineScope {
            val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { server.close() } }
            try {
                val authorize = ChatGptOAuth.authorize(client, store.hostId, redirect, state, nonce, verifier,
                    previous?.tokens?.id, previous?.email).newBuilder().apply {
                    if (consent) addQueryParameter("prompt", "consent")
                }.build()
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorize.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                val deadline = System.nanoTime() + 5 * 60 * 1_000_000_000L
                var callback: ChatGptOAuth.Callback? = null
                while (callback == null && System.nanoTime() < deadline) {
                    ensureActive()
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    socket.use { incoming ->
                        incoming.soTimeout = 2_000
                        val line = StringBuilder()
                        val input = incoming.getInputStream()
                        val read = runCatching {
                            while (line.length < 16_384) { val ch = input.read(); if (ch < 0 || ch == 10) break; line.append(ch.toChar()) }
                        }
                        if (read.isFailure || line.length >= 16_384) return@use
                        val target = line.toString().trim().split(' ')
                        val url = runCatching { if (target.size == 3 && target[0] == "GET" && target[1].startsWith("/")) ("http://127.0.0.1:${server.localPort}" + target[1]).toHttpUrl() else null }.getOrNull()
                        val matches = url != null && url.encodedPath == "/auth/callback" &&
                            url.queryParameterValues("state").size == 1 && ChatGptOAuth.same(url.queryParameter("state").orEmpty(), state)
                        val body = context.getString(if (matches) R.string.chatgpt_browser_done else R.string.chatgpt_browser_invalid)
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        runCatching { incoming.getOutputStream().apply {
                            write("HTTP/1.1 ${if (matches) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
                        } }
                        if (matches) callback = ChatGptOAuth.callback(url!!, state, client)
                    }
                }
                val result = callback ?: error(context.getString(R.string.chatgpt_timeout))
                if (previous == null) mutex.withLock { save(store.copy(pendingClient = result.clientId)) }
                val json = tokenRequest(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", result.clientId)
                    .add("code", result.code).add("code_verifier", verifier).add("redirect_uri", redirect).add("resource", ChatGptOAuth.RESOURCE).build())
                val identity = verify(json["id_token"].asString, result.clientId, nonce)
                val subject = identity["sub"].asString
                require(previous == null || previous.subject == subject) { "ChatGPT account identity does not match the selected connection" }
                val account = Registration(result.clientId, subject, identity["email"]?.asString.orEmpty(), tokens(json))
                mutex.withLock {
                    val old = store.accounts.firstOrNull { it.clientId == account.clientId }
                    require(old == null || old.subject == account.subject)
                    save(store.copy(accounts = if (old == null) store.accounts + account else store.accounts.map { if (it.clientId == account.clientId) account else it }, pendingClient = null))
                }
                result.clientId
            } finally { closer.cancel(); server.close() }
        }
    }

    private fun tokens(json: JsonObject, previous: Tokens? = null): Tokens {
        fun value(name: String): String? = json[name]?.takeUnless { it.isJsonNull }?.asString
        require(value("token_type").let { it == null || it.equals("Bearer", ignoreCase = true) })
        val expiry = json["expires_in"]?.asLong ?: 3600L
        require(expiry in 1..86_400)
        val granted = value("scope")?.split(' ')?.filter { it.isNotBlank() }?.toSet() ?: previous?.scopes.orEmpty()
        val access = value("access_token").orEmpty()
        require(!ChatGptOAuth.permitsInference(granted) || access.isNotBlank()) { "Missing authorized ChatGPT access token" }
        return Tokens(access, value("refresh_token") ?: previous?.refresh,
            value("id_token") ?: previous?.id.orEmpty(), System.currentTimeMillis() / 1000 + expiry,
            runCatching { json["earliest_refresh_at"]?.asLong }.getOrNull() ?: 0, granted)
    }

    private suspend fun tokenRequest(form: FormBody): JsonObject = ChatGptHttp.use(Request.Builder()
        .url("${ChatGptOAuth.ISSUER}/api/accounts/oauth/token").post(form).build()) {
        if (!it.isSuccessful) throw ChatGptHttp.failure(it)
        JsonParser.parseString(it.body!!.string()).asJsonObject
    }
    private fun trustedAuthUrl(value: String) { val url = value.toHttpUrl(); require(url.isHttps && url.host == "auth.openai.com" && url.port == 443) }
    private suspend fun discovery(): JsonObject = ChatGptHttp.use(Request.Builder()
        .url("${ChatGptOAuth.ISSUER}/.well-known/openid-configuration").build()) {
        if (!it.isSuccessful) throw ChatGptHttp.failure(it)
        JsonParser.parseString(it.body!!.string()).asJsonObject.also { data -> require(data["issuer"]?.asString == ChatGptOAuth.ISSUER) }
    }
    private suspend fun verify(token: String, clientId: String, nonce: String?): JsonObject {
        val url = discovery()["jwks_uri"].asString
        trustedAuthUrl(url)
        val jwks = ChatGptHttp.use(Request.Builder().url(url).build()) {
            if (!it.isSuccessful) throw ChatGptHttp.failure(it)
            JsonParser.parseString(it.body!!.string()).asJsonObject
        }
        return ChatGptOAuth.verifyIdToken(token, jwks, clientId, nonce)
    }
    companion object {
        @Volatile private var instance: ChatGptAuth? = null
        fun get(context: Context): ChatGptAuth = instance ?: synchronized(this) {
            instance ?: ChatGptAuth(context.applicationContext).also { instance = it }
        }
    }
}
