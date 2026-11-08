package com.android5.ai

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ChatGptSecureDnsTest {
    private fun response(data: String, name: String = "auth.openai.com.", status: Int = 0) =
        """{"Status":$status,"Question":[{"name":"$name","type":1}],"Answer":[{"type":1,"data":"$data"}]}"""

    @Test fun acceptsPublicLiteralsForExactQuestionOnly() {
        assertEquals("104.18.41.241", ChatGptSecureDns.parse("auth.openai.com", 1, response("104.18.41.241")).single().hostAddress)
        for (value in listOf("127.0.0.1", "10.0.0.1", "169.254.1.1", "0.0.0.0", "999.1.1.1", "evil.example"))
            assertTrue(ChatGptSecureDns.parse("auth.openai.com", 1, response(value)).isEmpty())
        assertTrue(ChatGptSecureDns.parse("auth.openai.com", 1, response("104.18.41.241", "evil.example.")).isEmpty())
        assertTrue(ChatGptSecureDns.parse("auth.openai.com", 1, response("104.18.41.241", status = 3)).isEmpty())
    }

    @Test fun fallbackOnlyRunsAfterSystemFailuresForOfficialHosts() {
        var fallbacks = 0
        val broken = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname) }
        val address = InetAddress.getByAddress(byteArrayOf(104, 18, 41, -15))
        val resolver = ChatGptDns(broken, fallback = { fallbacks++; listOf(address) }, pause = {})
        assertEquals(listOf(address), resolver.lookup("auth.openai.com"))
        assertEquals(1, fallbacks)
        try { resolver.lookup("example.com"); fail() } catch (_: UnknownHostException) { }
        assertEquals(1, fallbacks)
        val working = object : Dns { override fun lookup(hostname: String) = listOf(address) }
        assertEquals(listOf(address), ChatGptDns(working, fallback = { fail("Unexpected fallback"); emptyList() }).lookup("auth.openai.com"))
    }
}
