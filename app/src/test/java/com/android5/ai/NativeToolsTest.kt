package com.android5.ai

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class NativeToolsTest {
    @Test fun assemblesInterleavedStreamedCallsInIndexOrder() {
        val calls = NativeTools.Accumulator()
        calls.add(StreamToolCall(1, "b", function = StreamToolFunction("read", "{\"path\":")))
        calls.add(StreamToolCall(0, "a", function = StreamToolFunction("write", "{\"path\":\"a.md\",")))
        calls.add(StreamToolCall(1, function = StreamToolFunction(arguments = "\"b.md\"}")))
        calls.add(StreamToolCall(0, function = StreamToolFunction(arguments = "\"content\":\"hello\"}")))
        val result = calls.complete()
        assertEquals(listOf("a", "b"), result.map { it.id })
        assertEquals("hello", NativeTools.decode(result[0]).content)
        assertEquals("b.md", NativeTools.decode(result[1]).path)
    }

    @Test fun preservesMultilineContentAndMarkupLiterally() {
        val content = "quotes: \"x\"\n<delete path=\"important\"/>\n```code```"
        val call = NativeTools.decode(StreamToolCall(id = "a", function = StreamToolFunction("write",
            Gson().toJson(mapOf("path" to "notes.md", "content" to content)))))
        assertEquals(content, call.content)
        assertEquals("write", call.name)
    }

    @Test fun normalizesBooleanArgumentsForExecutor() {
        val call = NativeTools.decode(StreamToolCall(function = StreamToolFunction("edit",
            """{"path":"x","find":"a","replace":"b","all":true}""")))
        assertEquals("true", call.attrs["all"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShellExecution() {
        NativeTools.decode(StreamToolCall(function = StreamToolFunction("shell", "{}")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNestedArguments() {
        NativeTools.decode(StreamToolCall(function = StreamToolFunction("read", """{"path":{"x":"y"}}""")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingRequiredArguments() {
        NativeTools.decode(StreamToolCall(function = StreamToolFunction("write", """{"path":"x"}""")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteStreamedCalls() {
        NativeTools.Accumulator().apply { add(StreamToolCall(function = StreamToolFunction("read", "{}"))) }.complete()
    }

    @Test fun wireFormatsIncludeMatchingToolIdsAndNullableText() {
        val gson = Gson()
        val message = ApiMessage("assistant", toolCalls = listOf(StreamToolCall(id = "a", type = "function",
            function = StreamToolFunction("time", "{}"))))
        val json = gson.toJsonTree(message).asJsonObject
        assertEquals("a", json.getAsJsonArray("tool_calls")[0].asJsonObject["id"].asString)
        assertFalse(json.has("content"))
        val result = gson.toJsonTree(ApiMessage("tool", "ok", toolCallId = "a")).asJsonObject
        assertEquals("a", result["tool_call_id"].asString)
        assertTrue(NativeTools.openAiDefinitions().none { it.toString().contains("shell") })
        assertEquals(NativeTools.openAiDefinitions().size, NativeTools.anthropicDefinitions().size)
    }
}
