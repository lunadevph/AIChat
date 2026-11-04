package com.android5.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class GeminiToolContinuationTest {
    private val gson = Gson()
    private val signedCall = """{"index":0,"id":"gemini-call","type":"function","function":{"name":"time","arguments":"{}"},"extra_content":{"google":{"thought_signature":"opaque+/signature=="}}}"""

    private fun signature(call: StreamToolCall): String? =
        call.extraContent?.getAsJsonObject("google")?.get("thought_signature")?.asString

    @Test fun streamedSignatureSurvivesLateMetadataAndParallelCalls() {
        val calls = NativeTools.Accumulator()
        val first = gson.fromJson(signedCall, StreamToolCall::class.java)
        calls.add(first.copy(extraContent = null))
        calls.add(StreamToolCall(index = 1, id = "parallel", function = StreamToolFunction("storage", "{}")))
        calls.add(StreamToolCall(index = 0, extraContent = first.extraContent))
        calls.add(StreamToolCall(index = 0, extraContent = JsonParser.parseString(
            """{"google":{"other_metadata":true}}""").asJsonObject))
        val complete = calls.complete()
        assertEquals("opaque+/signature==", signature(complete[0]))
        assertNull(signature(complete[1]))
        val wire = gson.toJsonTree(NativeTools.exchange("", null, complete,
            complete.map { AiTools.Run(it.function!!.name!!, true, "", "ok") }).first()).asJsonObject
        assertEquals(first.extraContent!!.getAsJsonObject("google")["thought_signature"],
            wire.getAsJsonArray("tool_calls")[0].asJsonObject.getAsJsonObject("extra_content")
                .getAsJsonObject("google")["thought_signature"])
    }

    @Test fun nonStreamingSignatureSurvivesSavedConversationReplay() {
        val response = gson.fromJson("""{"choices":[{"message":{"role":"assistant","tool_calls":[$signedCall]}}]}""",
            ChatResponse::class.java)
        val call = response.choices.single().message!!.toolCalls!!.single()
        val saved = Message(role = "assistant", content = "", tools = listOf(ToolRun("time",
            callId = call.id, arguments = "{}", result = "ok", state = "completed", extraContent = call.extraContent)))
        val loaded = gson.fromJson(gson.toJson(saved), Message::class.java)
        val replay = NativeTools.restoreExchange(loaded)
        assertEquals("opaque+/signature==", signature(replay.first().toolCalls!!.single()))
        assertEquals(call.id, replay[1].toolCallId)
    }

    @Test fun ordinaryCallsDoNotGainGeminiMetadata() {
        val call = StreamToolCall(id = "ordinary", function = StreamToolFunction("time", "{}"))
        assertFalse(gson.toJsonTree(call).asJsonObject.has("extra_content"))
    }
}
