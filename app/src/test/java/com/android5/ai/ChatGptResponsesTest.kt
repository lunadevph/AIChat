package com.android5.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ChatGptResponsesTest {
    @Test fun oversizedCallsAreRejectedBeforeExecution() {
        val call = com.google.gson.JsonObject().apply {
            addProperty("type", "function_call"); addProperty("namespace", "aichat")
            addProperty("name", "write"); addProperty("call_id", "large")
            addProperty("arguments", "x".repeat(1_000_001))
        }
        val response = com.google.gson.JsonObject().apply {
            addProperty("status", "completed")
            add("output", com.google.gson.JsonArray().apply { add(call) })
        }
        assertTrue(runCatching { ChatGptResponses.completed(response) }.isFailure)
    }

    @Test fun planRequestsUseResponsesContractAndRespectToolSwitches() {
        val request = ChatGptResponses.body("account-model", listOf(ApiMessage("system", "rules"), ApiMessage("user", "hello")), setOf("shell"), true)
        assertFalse(request["store"].asBoolean)
        assertTrue(request["stream"].asBoolean)
        assertEquals("rules", request["instructions"].asString)
        for (unsupported in listOf("temperature", "top_p", "max_output_tokens", "previous_response_id", "background", "conversation")) assertFalse(request.has(unsupported))
        assertEquals("namespace", request.getAsJsonArray("tools")[0].asJsonObject["type"].asString)
        val tools = request.getAsJsonArray("tools")[0].asJsonObject.getAsJsonArray("tools").map { it.asJsonObject["name"].asString }
        assertFalse("shell" in tools || "exec" in tools)
        assertTrue("write" in tools)
        assertFalse(ChatGptResponses.body("account-model", listOf(ApiMessage("user", "done")), emptySet(), false).has("tools"))
    }

    @Test fun completedToolRoundPreservesOpaqueItemsAcrossSaveAndReplay() {
        val response = JsonParser.parseString("""{"status":"completed","output":[{"type":"reasoning","id":"r1","encrypted_content":"opaque"},{"type":"function_call","id":"fc1","call_id":"call1","name":"write","namespace":"aichat","arguments":"{}"}]}""").asJsonObject
        val turn = ChatGptResponses.completed(response)
        assertEquals("write", turn.calls.single().function!!.name)
        val stored = Message(role = "assistant", content = "Done", tools = listOf(ToolRun("write", callId = "call1", arguments = "{}", result = "saved",
            exchangeId = "round1", assistantContent = "", responseItems = turn.items)))
        val saved = Gson().fromJson(Gson().toJson(stored), Message::class.java)
        val exchange = NativeTools.restoreExchange(saved)
        val wire = ChatGptResponses.body("model", listOf(ApiMessage("user", "write")) + exchange, emptySet(), true)
        val items = wire.getAsJsonArray("input")
        assertEquals("opaque", items[1].asJsonObject["encrypted_content"].asString)
        assertEquals("function_call", items[2].asJsonObject["type"].asString)
        assertEquals("function_call_output", items[3].asJsonObject["type"].asString)
        assertEquals("call1", items[3].asJsonObject["call_id"].asString)
        assertFalse(Gson().toJson(exchange.first()).contains("responseItems"))
    }

    @Test fun imageInputAndTextStayInTheSameUserMessage() {
        val wire = ChatGptResponses.body("model", listOf(ApiMessage("user", listOf(ContentPart("text", "describe"),
            ContentPart("image_url", imageUrl = ApiImageUrl("data:image/jpeg;base64,AAAA"))))), emptySet(), false)
        val content = wire.getAsJsonArray("input")[0].asJsonObject.getAsJsonArray("content")
        assertEquals("input_text", content[0].asJsonObject["type"].asString)
        assertEquals("input_image", content[1].asJsonObject["type"].asString)
    }

    @Test fun incompleteOrForeignToolOutputIsNeverExecuted() {
        val incomplete = JsonParser.parseString("""{"status":"incomplete","output":[]}""").asJsonObject
        assertTrue(runCatching { ChatGptResponses.completed(incomplete) }.isFailure)
        val foreign = JsonParser.parseString("""{"status":"completed","output":[{"type":"function_call","namespace":"other","name":"write","call_id":"a","arguments":"{}"}]}""").asJsonObject
        assertTrue(runCatching { ChatGptResponses.completed(foreign) }.isFailure)
    }
}
