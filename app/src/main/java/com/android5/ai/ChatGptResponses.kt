package com.android5.ai

import com.google.gson.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The ChatGPT-plan route is Responses-only; it never sends OAuth tokens to Chat Completions. */
internal object ChatGptResponses {
    private val gson = Gson()
    data class Turn(val text: String, val calls: List<StreamToolCall>, val items: JsonArray)

    fun body(model: String, messages: List<ApiMessage>, disabled: Set<String>, allowTools: Boolean): JsonObject {
        val input = JsonArray()
        messages.filterNot { it.role == "system" }.forEach { message ->
            when {
                message.role == "tool" -> input.add(gson.toJsonTree(mapOf("type" to "function_call_output",
                    "call_id" to requireNotNull(message.toolCallId), "output" to message.content.toString())))
                message.role == "assistant" && message.responseItems != null -> message.responseItems.forEach { input.add(it.deepCopy()) }
                else -> {
                    if (message.content is String && message.content.isNotBlank()) input.add(gson.toJsonTree(mapOf("role" to message.role, "content" to message.content)))
                    else if (message.content is List<*>) {
                        val parts = message.content.filterIsInstance<ContentPart>().mapNotNull {
                            when (it.type) {
                                "text" -> mapOf("type" to "input_text", "text" to it.text.orEmpty())
                                "image_url" -> mapOf("type" to "input_image", "image_url" to requireNotNull(it.imageUrl).url)
                                else -> null
                            }
                        }
                        input.add(gson.toJsonTree(mapOf("role" to message.role, "content" to parts)))
                    }
                    message.toolCalls.orEmpty().forEach { call -> input.add(gson.toJsonTree(mapOf("type" to "function_call",
                        "call_id" to call.id, "name" to call.function?.name, "namespace" to "aichat",
                        "arguments" to call.function?.arguments.orEmpty()))) }
                }
            }
        }
        return JsonObject().apply {
            addProperty("model", model); add("input", input)
            addProperty("instructions", messages.filter { it.role == "system" }.joinToString("\n\n") { it.content.toString() })
            addProperty("store", false); addProperty("stream", true)
            add("include", gson.toJsonTree(listOf("reasoning.encrypted_content")))
            if (allowTools) {
                val functions = NativeTools.openAiDefinitions(disabled).map {
                    @Suppress("UNCHECKED_CAST") val function = it["function"] as Map<String, Any>
                    function + mapOf("type" to "function", "strict" to false)
                }
                if (functions.isNotEmpty()) add("tools", gson.toJsonTree(listOf(mapOf("type" to "namespace",
                    "name" to "aichat", "description" to "Actions on the user's device", "tools" to functions))))
            } else addProperty("tool_choice", "none")
        }
    }

    fun completed(response: JsonObject): Turn {
        require(response["status"]?.asString == "completed") { "ChatGPT did not complete this response" }
        val output = response.getAsJsonArray("output") ?: JsonArray()
        val text = StringBuilder()
        val calls = mutableListOf<StreamToolCall>()
        output.forEach { element ->
            val item = element.asJsonObject
            when (item["type"]?.asString) {
                "message" -> item.getAsJsonArray("content")?.forEach {
                    val part = it.asJsonObject
                    if (part["type"]?.asString == "output_text") text.append(part["text"]?.asString.orEmpty())
                    if (part["type"]?.asString == "refusal") text.append(part["refusal"]?.asString.orEmpty())
                }
                "function_call" -> {
                    require(item["namespace"]?.takeUnless { it.isJsonNull }?.asString.let { it == null || it == "aichat" })
                    require(item["arguments"].asString.length <= 1_000_000 && calls.size < 32) { "Tool response is too large; split code into smaller sections" }
                    calls += StreamToolCall(id = item["call_id"].asString, type = "function", function = StreamToolFunction(
                        item["name"].asString.removePrefix("aichat."), item["arguments"].asString))
                }
            }
        }
        require(text.length <= 2_000_000) { "ChatGPT response is too large; request smaller sections" }
        return Turn(text.toString(), calls, output)
    }

    suspend fun turn(auth: ChatGptAuth, clientId: String, model: String, messages: List<ApiMessage>,
                     disabled: Set<String>, allowTools: Boolean, onText: suspend (String) -> Unit): Turn {
        val token = auth.accessToken(clientId)
        val request = Request.Builder().url("${ChatGptOAuth.RESOURCE}/responses")
            .header("Authorization", "Bearer $token").header("Accept", "text/event-stream")
            .post(gson.toJson(body(model, messages, disabled, allowTools)).toRequestBody("application/json".toMediaType())).build()
        return ChatGptHttp.use(request, clientId, guard = { auth.ensureActive(clientId) }) { response ->
            if (!response.isSuccessful) throw ChatGptHttp.failure(response)
            val source = response.body?.source() ?: error("ChatGPT sent no response")
            val data = StringBuilder(); val text = StringBuilder()
            var completed: Turn? = null
            suspend fun event() {
                if (data.isEmpty()) return
                val raw = data.toString().trim(); data.clear()
                if (raw == "[DONE]") return
                val json = JsonParser.parseString(raw).asJsonObject
                when (json["type"]?.asString) {
                    "response.output_text.delta", "response.refusal.delta" -> {
                        text.append(json["delta"]?.asString.orEmpty())
                        require(text.length <= 2_000_000) { "ChatGPT response is too large; request smaller sections" }
                        onText(text.toString())
                    }
                    "response.completed" -> completed = completed(json.getAsJsonObject("response"))
                    "response.failed", "response.incomplete", "error" -> {
                        val result = json.getAsJsonObject("response")
                        val error = result?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: json
                        throw ChatGptApiException(0, error["code"]?.takeUnless { it.isJsonNull }?.asString,
                            error["message"]?.takeUnless { it.isJsonNull }?.asString?.take(800) ?: "ChatGPT response was interrupted or incomplete",
                            response.header("x-request-id"))
                    }
                }
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                if (source.exhausted()) break
                val line = source.readUtf8LineStrict(8L * 1024 * 1024)
                if (line.isBlank()) { event(); if (completed != null) break }
                else if (line.startsWith("data:")) {
                    data.append(line.removePrefix("data:").trimStart()).append('\n')
                    require(data.length <= 8 * 1024 * 1024) { "ChatGPT response event is too large" }
                }
            }
            event()
            completed ?: throw ChatGptApiException(0, "incomplete_stream", "ChatGPT disconnected before completing the response", response.header("x-request-id"))
        }
    }
}
