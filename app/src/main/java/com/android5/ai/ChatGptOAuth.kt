package com.android5.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

internal object ChatGptOAuth {
    const val PROVIDER = "ChatGPT"
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DIRECT = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    fun permitsInference(scopes: Set<String>): Boolean = DIRECT in scopes && "resource.invoke" in scopes
    fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.toByteString().base64Url().trimEnd('=')
    fun challenge(verifier: String): String = MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(Charsets.US_ASCII)).toByteString().base64Url().trimEnd('=')
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun authorize(clientId: String?, hostId: String, redirect: String, state: String, nonce: String,
                  verifier: String, idHint: String? = null, emailHint: String? = null): HttpUrl {
        val url = "$ISSUER/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId ?: "dynamic_agent_client")
            .addQueryParameter("ext_agent_host_id", hostId)
            .addQueryParameter("response_type", "code").addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("scope", SCOPES).addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", state).addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256").addQueryParameter("code_challenge", challenge(verifier))
        if (clientId == null) url.addQueryParameter("agent_name_hint", "AI Chat")
        idHint?.let { url.addQueryParameter("id_token_hint", it) }
        emailHint?.let { url.addQueryParameter("login_hint", it) }
        return url.build()
    }

    data class Callback(val code: String, val clientId: String)
    fun callback(url: HttpUrl, expectedState: String, previousClientId: String?): Callback {
        require(url.scheme == "http" && url.host == "127.0.0.1" && url.encodedPath == "/auth/callback")
        fun one(name: String): String? {
            val values = url.queryParameterValues(name)
            require(values.size <= 1) { "Repeated OAuth parameter" }
            return values.singleOrNull()
        }
        require(same(one("state") ?: "", expectedState)) { "Invalid sign-in state" }
        require(one("error") == null) { "ChatGPT sign-in was declined" }
        val client = one("client_id") ?: previousClientId ?: error("Registration did not return a client ID")
        require(client.isNotBlank() && client.length <= 256 && client != "dynamic_agent_client" && (previousClientId == null || client == previousClientId)) { "Client registration mismatch" }
        return Callback(one("code")?.takeIf { it.isNotBlank() } ?: error("Missing authorization code"), client)
    }

    fun verifyIdToken(token: String, jwks: JsonObject, client: String, nonce: String?, now: Long = System.currentTimeMillis() / 1000): JsonObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid identity token" }
        fun decode(value: String) = requireNotNull(value.decodeBase64()).toByteArray()
        val header = JsonParser.parseString(String(decode(parts[0]), Charsets.UTF_8)).asJsonObject
        require(header["alg"]?.asString == "RS256") { "Unsupported identity signature" }
        val key = jwks.getAsJsonArray("keys").map { it.asJsonObject }.singleOrNull {
            it["kid"]?.asString == header["kid"]?.asString && it["kty"]?.asString == "RSA"
        } ?: error("Unknown identity signing key")
        require(key["use"]?.asString.let { it == null || it == "sig" })
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decode(key["n"].asString)), BigInteger(1, decode(key["e"].asString))))
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)); verify(decode(parts[2]))
        }) { "Invalid identity signature" }
        val claims = JsonParser.parseString(String(decode(parts[1]), Charsets.UTF_8)).asJsonObject
        require(claims["iss"]?.asString == ISSUER && claims["exp"].asLong > now) { "Expired or invalid identity" }
        val audience = claims["aud"]
        val audiences = if (audience.isJsonArray) audience.asJsonArray.map { it.asString } else listOf(audience.asString)
        require(client in audiences && (audiences.size == 1 || claims["azp"]?.asString == client)) { "Wrong identity audience" }
        require(claims["nbf"]?.asLong?.let { it <= now + 60 } != false && claims["iat"]?.asLong?.let { it <= now + 60 } != false)
        require(!claims["sub"]?.asString.isNullOrBlank())
        if (nonce != null) require(same(claims["nonce"]?.asString.orEmpty(), nonce)) { "Invalid sign-in nonce" }
        return claims
    }
}
