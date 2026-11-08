package com.android5.ai

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ChatGptDnsTest {
    private fun dns(lookup: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String) = lookup.invoke(hostname)
    }
    private val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test fun retriesResolutionBeforeAnyRequestIsSent() {
        var attempts = 0
        var pauses = 0
        val dns = ChatGptDns(dns { host ->
            assertEquals("auth.openai.com", host)
            if (++attempts == 1) throw UnknownHostException(host)
            listOf(address)
        }) { pauses++ }
        assertEquals(listOf(address), dns.lookup("auth.openai.com"))
        assertEquals(2, attempts)
        assertEquals(1, pauses)
    }

    @Test fun persistentFailureIsBoundedAndRetainsHostAndCause() {
        var attempts = 0
        val dns = ChatGptDns(dns { attempts++; throw UnknownHostException("not found") }) {}
        try { dns.lookup("auth.openai.com"); fail() }
        catch (e: ChatGptDnsFailure) {
            assertEquals("auth.openai.com", e.host)
            assertEquals("not found", e.cause?.message)
            assertSame(e, java.io.IOException(e).chatGptDnsFailure())
        }
        assertEquals(2, attempts)
    }

    @Test fun successDoesNotWaitAndEmptyResolutionIsAFailure() {
        val dns = ChatGptDns(dns { listOf(address) }) { fail("No pause on success") }
        assertEquals(listOf(address), dns.lookup("api.openai.com"))
        try { ChatGptDns(dns { emptyList() }) {}.lookup("auth.openai.com"); fail() }
        catch (e: ChatGptDnsFailure) { assertEquals("auth.openai.com", e.host) }
        assertNull(IllegalArgumentException("bad consent").chatGptDnsFailure())
    }
}
