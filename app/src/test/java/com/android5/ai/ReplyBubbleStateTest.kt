package com.android5.ai

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ReplyBubbleStateTest {
    @Test fun aLaterToolRoundDoesNotOverwriteAnEarlierResult() {
        val bubble = ReplyBubbleState()
        val first = ToolRun("read", callId = "a", result = "First result", state = "completed")
        val second = ToolRun("read", callId = "a", state = "queued")
        bubble.queue(listOf(first))
        bubble.queue(listOf(second))
        bubble.update(second.copy(state = "running"))
        assertEquals("First result", bubble.snapshot.tools!![0].result)
        assertEquals("running", bubble.snapshot.tools!![1].state)
    }

    @Test fun retryCanReuseTheBubbleAndItsCompletedActions() {
        val previous = Message(role = "assistant", content = "", tools = listOf(
            ToolRun("write", callId = "a", result = "Saved", state = "completed")))
        val bubble = ReplyBubbleState(previous)
        bubble.commit("Here is the corrected answer.", null)
        assertEquals(previous.id, bubble.snapshot.id)
        assertEquals("Saved", bubble.snapshot.tools!!.single().result)
        assertEquals("Here is the corrected answer.", bubble.snapshot.content)
    }

    @Test fun streamingUpdatesReplaceThePreviewWithoutDuplicatingText() {
        val bubble = ReplyBubbleState()
        val id = bubble.snapshot.id
        bubble.commit("Checking the file.", null)
        assertEquals("Checking the file.\n\nHel", bubble.preview("Hel", null).content)
        assertEquals("Checking the file.\n\nHello", bubble.preview("Hello", null).content)
        bubble.commit("Hello", null)
        assertEquals("Checking the file.\n\nHello", bubble.snapshot.content)
        assertEquals(id, bubble.snapshot.id)
    }

    @Test fun eachToolMovesFromQueuedToRunningToCompleted() {
        val bubble = ReplyBubbleState()
        val tool = ToolRun("read", callId = "a", arguments = "{}", state = "queued")
        bubble.queue(listOf(tool))
        assertEquals("queued", bubble.snapshot.tools!!.single().state)
        bubble.update(tool.copy(state = "running"))
        assertEquals("running", bubble.snapshot.tools!!.single().state)
        bubble.update(tool.copy(state = "completed", result = "hello"))
        assertEquals(1, bubble.snapshot.tools!!.size)
        assertEquals("hello", bubble.snapshot.tools!!.single().result)
    }

    @Test fun furtherRoundsAndFinalAnswerStayInTheSameBubble() {
        val bubble = ReplyBubbleState()
        val id = bubble.snapshot.id
        bubble.queue(listOf(ToolRun("time", callId = "a", arguments = "{}", result = "12:00", state = "completed")))
        bubble.commit("Next action.", "First reasoning")
        bubble.queue(listOf(ToolRun("storage", callId = "b", arguments = "{}", result = "Available", state = "completed")))
        bubble.commit("Finished.", "Second reasoning")
        val done = bubble.finish(bubble.snapshot)
        assertEquals(id, done.id)
        assertEquals("Next action.\n\nFinished.", done.content)
        assertEquals(2, done.tools!!.size)
        assertFalse(done.isStreaming)
    }

    @Test fun stoppingKeepsPartialTextAndFinishedTools() {
        val bubble = ReplyBubbleState()
        bubble.queue(listOf(
            ToolRun("write", callId = "a", result = "Saved", state = "completed"),
            ToolRun("shell", callId = "b", state = "running")))
        val done = bubble.finish(bubble.preview("Partial answer", null))
        assertEquals("Partial answer", done.content)
        assertEquals("Saved", done.tools!![0].result)
        assertEquals("completed", done.tools[0].state)
        assertEquals("cancelled", done.tools[1].state)
        assertFalse(done.tools[1].ok)
    }

    @Test fun retryingAnEmptyRoundKeepsPreviouslyCompletedWork() {
        val bubble = ReplyBubbleState()
        bubble.commit("Saved your file.", null)
        bubble.queue(listOf(ToolRun("write", callId = "a", result = "Saved", state = "completed")))
        val retry = bubble.preview("", null)
        assertEquals("Saved your file.", retry.content)
        assertEquals("Saved", retry.tools!!.single().result)
    }

    @Test fun savedInterruptedBubbleStillRestoresCompletedToolResults() {
        val bubble = ReplyBubbleState()
        bubble.queue(listOf(
            ToolRun("time", callId = "a", arguments = "{}", result = "12:00", state = "completed"),
            ToolRun("shell", callId = "b", arguments = "{}", state = "running")))
        val saved = Gson().fromJson(Gson().toJson(bubble.finish(bubble.snapshot)), Message::class.java)
        val exchange = NativeTools.restoreExchange(saved)
        assertEquals(2, exchange.size)
        assertEquals("a", exchange.last().toolCallId)
        assertTrue(exchange.last().content.toString().contains("12:00"))
    }
}
