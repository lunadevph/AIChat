package com.android5.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

class ChatGptOAuthTest {
    @Test fun identityConsentDoesNotAuthorizePlanInference() {
        assertFalse(ChatGptOAuth.permitsInference(setOf("openid", "profile", "email")))
        assertFalse(ChatGptOAuth.permitsInference(setOf(ChatGptOAuth.DIRECT)))
        assertFalse(ChatGptOAuth.permitsInference(setOf("resource.invoke")))
        assertTrue(ChatGptOAuth.permitsInference(setOf(ChatGptOAuth.DIRECT, "resource.invoke")))
    }

    private val client = "oaiapp_test"
    private val nonce = "nonce-value"
    private val now = 1_800_000_000L
    private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private fun b64(bytes: ByteArray) = bytes.toByteString().base64Url().trimEnd('=')
    private fun jwks(): JsonObject {
        val key = pair.public as RSAPublicKey
        return JsonParser.parseString(Gson().toJson(mapOf("keys" to listOf(mapOf("kid" to "one", "kty" to "RSA", "use" to "sig",
            "n" to b64(key.modulus.toByteArray()), "e" to b64(key.publicExponent.toByteArray())))))).asJsonObject
    }
    private fun token(changes: Map<String, Any> = emptyMap()): String {
        val header = b64("""{"alg":"RS256","kid":"one"}""".toByteArray())
        val claims = mapOf("iss" to ChatGptOAuth.ISSUER, "aud" to client, "sub" to "subject-one", "exp" to now + 60,
            "iat" to now - 30, "nonce" to nonce) + changes
        val body = "$header.${b64(Gson().toJson(claims).toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(body.toByteArray()); sign() }
        return "$body.${b64(signature)}"
    }

    @Test fun pkceMatchesRfcVectorAndDoesNotExposeVerifier() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptOAuth.challenge(verifier))
        val url = ChatGptOAuth.authorize(null, "host", "http://127.0.0.1:12345/auth/callback", "state", nonce, verifier)
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("AI Chat", url.queryParameter("agent_name_hint"))
        assertFalse(url.toString().contains(verifier))
        val returning = ChatGptOAuth.authorize(client, "host", "http://127.0.0.1:12345/auth/callback", "new-state", nonce, verifier)
        assertNull(returning.queryParameter("agent_name_hint"))
        assertEquals(client, returning.queryParameter("client_id"))
    }

    @Test fun callbacksBindStateAndClientAndRejectDeclinesAndDuplicates() {
        val base = "http://127.0.0.1:12345/auth/callback?code=code&state=state"
        assertEquals(client, ChatGptOAuth.callback("$base&client_id=$client".toHttpUrl(), "state", null).clientId)
        assertEquals(client, ChatGptOAuth.callback(base.toHttpUrl(), "state", client).clientId)
        for (url in listOf("$base&client_id=other", "$base&state=other", "$base&error=access_denied", base.replace("127.0.0.1", "localhost"))) {
            assertTrue(runCatching { ChatGptOAuth.callback(url.toHttpUrl(), "state", client) }.isFailure)
        }
        assertTrue(runCatching { ChatGptOAuth.callback(base.toHttpUrl(), "wrong-state", client) }.isFailure)
        assertTrue(runCatching { ChatGptOAuth.callback(base.toHttpUrl(), "state", null) }.isFailure)
    }

    @Test fun verifiesSignatureIssuerAudienceExpiryAndNonce() {
        assertEquals("subject-one", ChatGptOAuth.verifyIdToken(token(), jwks(), client, nonce, now)["sub"].asString)
        for (change in listOf(mapOf("iss" to "https://attacker.invalid"), mapOf("aud" to "another-client"),
            mapOf("exp" to now - 1), mapOf("nonce" to "other"), mapOf("nbf" to now + 120))) {
            assertTrue(runCatching { ChatGptOAuth.verifyIdToken(token(change), jwks(), client, nonce, now) }.isFailure)
        }
        val signed = token()
        val tampered = signed.dropLast(5) + "AAAAA"
        assertTrue(runCatching { ChatGptOAuth.verifyIdToken(tampered, jwks(), client, nonce, now) }.isFailure)
        val noSignature = b64("""{"alg":"none","kid":"one"}""".toByteArray()) + "." + signed.split('.')[1] + "."
        assertTrue(runCatching { ChatGptOAuth.verifyIdToken(noSignature, jwks(), client, nonce, now) }.isFailure)
    }
}
