package com.android5.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RequestBudgetTest {
    @Test fun removesWholeOldTurnsAndPreservesSignedCurrentCall() {
        val call = StreamToolCall(id = "written", function = StreamToolFunction("write", "x".repeat(40_000)),
            extraContent = JsonParser.parseString("""{"google":{"thought_signature":"exact+/=="}}""").asJsonObject)
        val messages = listOf(ApiMessage("system", "rules"), ApiMessage("user", "old".repeat(50_000)),
            ApiMessage("assistant", "old answer"), ApiMessage("user", "write code"),
            ApiMessage("assistant", toolCalls = listOf(call)), ApiMessage("tool", "saved", toolCallId = "written"))
        val result = RequestBudget.compact(messages, maxBytes = 10_000)
        assertEquals(listOf("system", "system", "user", "assistant", "tool"), result.map { it.role })
        assertEquals(call, result[3].toolCalls!!.single())
        assertEquals("written", result.last().toolCallId)
        assertEquals(6, messages.size)
        assertEquals(40_000, call.function!!.arguments!!.length)
    }

    @Test fun boundedToolResultRetainsJsonStatusAndOriginalData() {
        val original = Gson().toJson(mapOf("ok" to false, "result" to "é".repeat(20_000)))
        val message = ApiMessage("tool", original, toolCallId = "result")
        val trimmed = RequestBudget.compact(listOf(ApiMessage("user", "read"), message), outputLimit = 200).last()
        val json = JsonParser.parseString(trimmed.content as String).asJsonObject
        assertFalse(json["ok"].asBoolean)
        assertTrue(json["result"].asString.contains("omitted"))
        assertEquals(original, message.content)
    }

    @Test fun lastResortContinuationKeepsUserButOmitsHugeCodeAndToolProtocol() {
        val user = ApiMessage("user", "Generate my complete program")
        val input = listOf(ApiMessage("system", "rules"), user,
            ApiMessage("assistant", toolCalls = listOf(StreamToolCall(id = "saved",
                function = StreamToolFunction("write", "FULL_SOURCE".repeat(10_000))))),
            ApiMessage("tool", """{"ok":true,"result":"Saved project.kt"}""", toolCallId = "saved"))
        val recovered = RequestBudget.completionContext(input)
        assertEquals(user, recovered.last())
        assertTrue(recovered.all { it.toolCalls == null && it.toolCallId == null })
        val wire = Gson().toJson(recovered)
        assertFalse(wire.contains("FULL_SOURCE"))
        assertTrue(wire.contains("Saved project.kt"))
        assertTrue(wire.contains("do not repeat"))
    }

    private class TooLarge : Exception()

    @Test fun sizeRecoveryRetriesRequestsBeforeApplyingAnyActions() = runBlocking {
        val stages = mutableListOf<Int>()
        var actions = 0
        val answer = RequestBudget.recover(isTooLarge = { it is TooLarge }) { stage ->
            stages += stage
            if (stage < 2) throw TooLarge()
            "continue using saved files"
        }
        actions++
        assertEquals(listOf(0, 1, 2), stages)
        assertEquals(1, actions)
        assertEquals("continue using saved files", answer)
    }

    @Test fun retriesAreBoundedAndOtherErrorsPropagate() = runBlocking {
        var attempts = 0
        try {
            RequestBudget.recover(isTooLarge = { it is TooLarge }) { attempts++; throw TooLarge() }
            fail("Expected size failure")
        } catch (_: TooLarge) { }
        assertEquals(3, attempts)
        attempts = 0
        try {
            RequestBudget.recover(isTooLarge = { it is TooLarge }) { attempts++; throw IllegalStateException() }
            fail("Expected original failure")
        } catch (_: IllegalStateException) { }
        assertEquals(1, attempts)
    }

    @Test fun cancellationNeverBecomesRecovery() = runBlocking {
        var attempts = 0
        try {
            RequestBudget.recover(isTooLarge = { true }) { attempts++; throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, attempts)
    }
}
