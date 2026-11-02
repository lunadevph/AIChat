package com.android5.ai

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ReplyRecoveryTest {
    @Test fun detectsJsonWithoutConsumingTheResponse() {
        val body = "  {\"choices\": []}"
        val source = okio.Buffer().writeUtf8(body)
        assertTrue(ReplyRecovery.isJson(source))
        assertEquals(body, source.readUtf8())
    }

    @Test fun eventStreamsRemainStreamsEvenIfTheirContentTypeIsWrong() {
        val body = "data: {\"choices\": []}\n\n"
        val source = okio.Buffer().writeUtf8(body)
        assertFalse(ReplyRecovery.isJson(source))
        assertEquals(body, source.readUtf8())
        assertFalse(ReplyRecovery.isJson(okio.Buffer()))
    }

    @Test fun lowersReasoningOnlyOnRecoveryForSupportedProviders() {
        val request = ChatRequest("openai/gpt-oss-120b", emptyList())
        assertSame(request, ReplyRecovery.retryRequest(request, "Groq", false))
        assertEquals("low", ReplyRecovery.retryRequest(request, "Groq", true).reasoningEffort)
        assertEquals("low", ReplyRecovery.retryRequest(request, "OpenRouter", true).reasoning?.get("effort"))
        assertEquals("disabled", ReplyRecovery.retryRequest(request, "DeepSeek", true).thinking?.get("type"))
        assertSame(request, ReplyRecovery.retryRequest(request, "Custom provider", true))
    }

    @Test fun emptyCompletionMarkersDoNotEndTheStream() {
        for (marker in listOf(null, "", " ", "null")) assertNull(ReplyRecovery.finishReason(marker))
        assertEquals("stop", ReplyRecovery.finishReason("stop"))
        assertEquals("length", ReplyRecovery.finishReason("length"))
    }

    @Test fun reasoningOnlyAndTruncatedRepliesGetMoreOutputRoomOnRetry() {
        assertTrue(ReplyRecovery.needsMoreTokens(ToolReply("", 0, "reasoning only")))
        assertTrue(ReplyRecovery.needsMoreTokens(ToolReply("", 0, null, finishReason = "length")))
        assertEquals(8192, ReplyRecovery.tokenLimit(4096, retrying = true, exhausted = true))
        assertEquals(4096, ReplyRecovery.tokenLimit(4096, retrying = true, exhausted = false))
        assertEquals(4096, ReplyRecovery.tokenLimit(4096, retrying = false, exhausted = true))
    }

    @Test fun retryBudgetDoesNotOverflowOrReduceALargerConfiguredLimit() {
        assertEquals(32768, ReplyRecovery.tokenLimit(20000, true, true))
        assertEquals(64000, ReplyRecovery.tokenLimit(64000, true, true))
        assertEquals(Int.MAX_VALUE, ReplyRecovery.tokenLimit(Int.MAX_VALUE, true, true))
    }

    @Test fun readsTextBlocksInsteadOfDiscardingTheWholeReply() {
        val response = Gson().fromJson("""{"choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"Hello "},{"type":"text","text":"world"}]},"finish_reason":"stop"}]}""", ChatResponse::class.java)
        assertEquals("Hello world", response.choices.single().message!!.content)
        assertEquals("stop", response.choices.single().finishReason)
    }

    @Test fun readsTextBlocksInStreamDeltas() {
        val chunk = Gson().fromJson("""{"choices":[{"delta":{"content":[{"type":"text","text":"Answer"}]},"finish_reason":""}]}""", StreamChunk::class.java)
        assertEquals("Answer", chunk.choices!!.single().delta!!.content)
        assertNull(ReplyRecovery.finishReason(chunk.choices.single().finishReason))
    }

    @Test fun preservesProviderRefusalsAndWholeMessageStreamEvents() {
        val chunk = Gson().fromJson("""{"choices":[{"message":{"role":"assistant","content":null,"refusal":"Cannot answer this request"},"finish_reason":"content_filter"}]}""", StreamChunk::class.java)
        assertEquals("Cannot answer this request", chunk.choices!!.single().message!!.refusal)
        assertEquals("content_filter", chunk.choices.single().finishReason)
    }
}
