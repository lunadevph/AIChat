package com.android5.ai

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class ChatGptApiException(val status: Int, val errorCode: String?, message: String, val requestId: String? = null) : Exception(message)

internal object ChatGptHttp {
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).followRedirects(false).build()
    private val accountCalls = ConcurrentHashMap<Call, String>()
    fun cancelAccount(id: String) { accountCalls.forEach { (call, account) -> if (account == id) call.cancel() } }

    suspend fun <T> use(request: Request, account: String? = null, guard: () -> Unit = {}, block: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val call = client.newCall(request)
            if (account != null) accountCalls[call] = account
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try { guard(); currentCoroutineContext().ensureActive(); call.execute().use { block(it) } }
            finally { accountCalls.remove(call); cancellation.cancel() }
        }
    }

    fun failure(response: Response): ChatGptApiException {
        val json = runCatching { JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject }.getOrNull()
        val error = json?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
        val message = error?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            ?: json?.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
            ?: json?.get("error_description")?.takeIf { it.isJsonPrimitive }?.asString
            ?: "ChatGPT request failed (HTTP ${response.code})"
        return ChatGptApiException(response.code, error?.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: json?.get("error")?.takeIf { it.isJsonPrimitive }?.asString,
            message.take(800), response.header("x-request-id"))
    }
}
