package com.android5.ai

import org.junit.Assert.*
import org.junit.Test

class ChatGptBrowserReturnTest {
    @Test fun onlyReadyCurrentTicketCanReturnOnce() {
        val handoff = ChatGptBrowserReturn { 0 }
        val old = handoff.begin(ChatGptBrowserReturn.Target.SETTINGS)
        val ticket = handoff.begin(ChatGptBrowserReturn.Target.SETUP)
        assertNull(handoff.consume(ticket))
        handoff.ready(old)
        assertNull(handoff.consume(old))
        handoff.ready(ticket)
        assertNull(handoff.consume("wrong"))
        assertEquals(ChatGptBrowserReturn.Target.SETUP, handoff.consume(ticket))
        assertNull(handoff.consume(ticket))
    }

    @Test fun expiredOrCancelledTicketsCannotNavigate() {
        var now = 0L
        val handoff = ChatGptBrowserReturn { now }
        val ticket = handoff.begin(ChatGptBrowserReturn.Target.SETTINGS)
        handoff.ready(ticket)
        now = 7 * 60 * 1_000_000_000L
        assertNull(handoff.consume(ticket))
        val next = handoff.begin(ChatGptBrowserReturn.Target.SETTINGS)
        handoff.ready(next); handoff.cancel()
        assertNull(handoff.consume(next))
    }
}
