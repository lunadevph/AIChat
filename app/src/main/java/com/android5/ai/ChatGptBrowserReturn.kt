package com.android5.ai

/** A foreground-navigation ticket, never an OAuth credential or proof of authentication. */
internal class ChatGptBrowserReturn(private val now: () -> Long = System::nanoTime) {
    enum class Target { SETUP, SETTINGS }
    private data class Pending(val ticket: String, val target: Target, val deadline: Long, val ready: Boolean = false)
    private var pending: Pending? = null
    @Synchronized fun begin(target: Target): String = ChatGptOAuth.random().also {
        pending = Pending(it, target, now() + 6 * 60 * 1_000_000_000L)
    }
    @Synchronized fun ready(ticket: String) {
        pending?.takeIf { ChatGptOAuth.same(it.ticket, ticket) }?.let { pending = it.copy(ready = true) }
    }
    @Synchronized fun consume(ticket: String): Target? {
        val current = pending ?: return null
        if (!current.ready || now() > current.deadline || !ChatGptOAuth.same(current.ticket, ticket)) return null
        pending = null
        return current.target
    }
    @Synchronized fun cancel() { pending = null }
}
