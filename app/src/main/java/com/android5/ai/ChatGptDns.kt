package com.android5.ai

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

internal class ChatGptDnsFailure(val host: String, cause: UnknownHostException) : UnknownHostException(host) {
    init { initCause(cause) }
}

/** Retry name resolution only, before connecting or sending a request body. */
internal class ChatGptDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val fallback: ((String) -> List<InetAddress>)? = null,
    private val pause: () -> Unit = { Thread.sleep(300) }
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        repeat(2) { attempt ->
            try {
                return delegate.lookup(hostname).also {
                    if (it.isEmpty()) throw UnknownHostException(hostname)
                }
            } catch (e: UnknownHostException) {
                if (attempt == 1) {
                    if (hostname in ChatGptSecureDns.hosts && fallback != null) {
                        try { return fallback.invoke(hostname).ifEmpty { throw UnknownHostException(hostname) } }
                        catch (failure: Exception) { e.addSuppressed(failure) }
                    }
                    throw ChatGptDnsFailure(hostname, e)
                }
                try { pause() } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("DNS lookup cancelled").apply { initCause(e) }
                }
            }
        }
        error("Unreachable")
    }
}

internal fun Throwable.chatGptDnsFailure(): ChatGptDnsFailure? {
    var current: Throwable? = this
    repeat(8) {
        if (current is ChatGptDnsFailure) return current as ChatGptDnsFailure
        current = current?.cause
    }
    return null
}
