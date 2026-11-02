package com.android5.ai

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolReplyLoopTest {
    private val call = StreamToolCall(id = "call_1", type = "function", function = StreamToolFunction("time", "{}"))

    @Test fun toolExecutionContinuesToAnAnswerWithMatchingResults() = runBlocking {
        val history = mutableListOf<ApiMessage>()
        val completed = mutableListOf<String>()
        var requests = 0
        ToolReplyLoop.run(3, request = { _, _ ->
            requests++
            if (requests == 1) ToolReply("", 0, "Need the current time", listOf(call))
            else {
                assertEquals("call_1", history.last().toolCallId)
                assertTrue(history.last().content.toString().contains("12:00"))
                assertEquals("Need the current time", history.first().reasoningContent)
                ToolReply("It is 12:00.", 1, null)
            }
        }, apply = { reply ->
            if (reply.calls.isNotEmpty()) history.addAll(NativeTools.exchange(reply.raw, reply.reasoning,
                reply.calls, listOf(AiTools.Run("time", true, "time", "12:00"))))
            else completed.add(reply.raw)
        })
        assertEquals(2, requests)
        assertEquals(listOf("It is 12:00."), completed)
    }

    @Test fun emptyFollowupRetriesWithoutExecutingTheToolAgain() = runBlocking {
        var requests = 0
        var executions = 0
        ToolReplyLoop.run(3, request = { _, retry ->
            when (++requests) {
                1 -> ToolReply("", 0, null, listOf(call))
                2 -> ToolReply("", 1, null)
                else -> { assertTrue(retry); ToolReply("Done", 1, null) }
            }
        }, apply = { if (it.calls.isNotEmpty()) executions++ })
        assertEquals(3, requests)
        assertEquals(1, executions)
    }

    @Test fun exhaustedToolBudgetStillRequestsFinalAnswer() = runBlocking {
        var requests = 0
        ToolReplyLoop.run(1, request = { allowTools, _ ->
            if (++requests == 1) { assertTrue(allowTools); ToolReply("", 0, null, listOf(call)) }
            else { assertFalse(allowTools); ToolReply("Finished", 1, null) }
        }, apply = {})
        assertEquals(2, requests)
    }

    @Test fun emptyReplyRetryDisablesFurtherToolExecution() = runBlocking {
        var requests = 0
        ToolReplyLoop.run(8, request = { allowTools, retry ->
            if (++requests == 1) { assertTrue(allowTools); ToolReply("", 0, "reasoning", finishReason = "length") }
            else { assertFalse(allowTools); assertTrue(retry); ToolReply("Recovered answer", 1, null) }
        }, apply = {})
        assertEquals(2, requests)
    }

    @Test fun filteredResponseIsNotPointlesslyRetried() = runBlocking {
        var requests = 0
        try {
            ToolReplyLoop.run(8, request = { _, _ -> requests++; ToolReply("", 0, null, finishReason = "content_filter") }, apply = {})
            fail("Expected a specific filtered response")
        } catch (e: EmptyReplyException) {
            assertEquals("content_filter", e.finishReason)
            assertEquals(1, requests)
        }
    }

    @Test fun repeatedEmptyResponseRaisesVisibleFailure() = runBlocking {
        var requests = 0
        try {
            ToolReplyLoop.run(3, request = { _, _ -> requests++; null }, apply = {})
            fail("Empty replies must not silently finish")
        } catch (_: EmptyReplyException) { assertEquals(2, requests) }
    }

    @Test fun persistedToolResultsSurviveJsonRoundTrip() {
        val saved = Message(role = "assistant", content = "", reasoning = "Look up the time",
            tools = listOf(ToolRun("time", "time", true, callId = "call_1", arguments = "{}", result = "12:00")))
        val restored = Gson().fromJson(Gson().toJson(saved), Message::class.java)
        val exchange = NativeTools.restoreExchange(restored)
        assertEquals(listOf("assistant", "tool"), exchange.map { it.role })
        assertEquals("call_1", exchange[0].toolCalls!!.single().id)
        assertEquals("call_1", exchange[1].toolCallId)
        assertTrue(exchange[1].content.toString().contains("12:00"))
    }
}
