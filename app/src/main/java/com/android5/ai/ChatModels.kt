package com.android5.ai

import com.google.gson.annotations.SerializedName
import java.util.UUID

data class ChatRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val tools: List<Map<String, Any>>? = null,
    val stream: Boolean? = null,
    val temperature: Double? = null,
    @com.google.gson.annotations.SerializedName("top_p")
    val topP: Double? = null,
    @com.google.gson.annotations.SerializedName("max_tokens")
    val maxTokens: Int? = null,
    @com.google.gson.annotations.SerializedName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    @com.google.gson.annotations.SerializedName("frequency_penalty")
    val frequencyPenalty: Double? = null,
    @com.google.gson.annotations.SerializedName("presence_penalty")
    val presencePenalty: Double? = null
)

// SSE streaming response models
data class StreamDelta(
    val content: String? = null,
    val role: String? = null,
    @SerializedName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    /**
     * A provider's own tool call. This app asks for tools in plain text tags, so
     * this is only ever seen when a model ignores that and uses its native
     * function-calling format — in which case there is no text to show.
     */
    @SerializedName("tool_calls") val toolCalls: List<StreamToolCall>? = null
) {
    val thinking: String? get() = reasoningContent ?: reasoning
}

data class StreamToolCall(
    val id: String? = null,
    val type: String? = null,
    val function: StreamToolFunction? = null
)

data class StreamToolFunction(
    val name: String? = null,
    val arguments: String? = null
)

data class StreamChoice(
    val delta: StreamDelta? = null,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class StreamError(
    val message: String? = null,
    val type: String? = null,
    val code: Any? = null
)

data class StreamChunk(
    val choices: List<StreamChoice>? = null,
    val error: StreamError? = null
)

data class ApiMessage(
    val role: String,
    val content: Any
)

data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url")
    val imageUrl: ApiImageUrl? = null
)

data class ApiImageUrl(
    val url: String
)

/** One tool the assistant asked for, kept short so it can be shown in the transcript. */
data class ToolRun(
    val name: String,
    val label: String = "",
    val ok: Boolean = true,
    /**
     * Workspace-relative path the call touched, for the file tools. Null for
     * tools that do not work on one file, and for runs saved by older versions.
     */
    val path: String? = null
)

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val localImageUrl: String? = null,
    val isStreaming: Boolean = false,
    /**
     * Tools this message's reply invoked. Nullable so replies saved by older
     * versions (which have no such field) still deserialize cleanly.
     */
    val tools: List<ToolRun>? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * True when this message is an error reported by the app rather than a
     * reply from the model. Errors are styled differently in the transcript.
     */
    val isError: Boolean = false
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val messages: List<Message> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)

data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: AssistantMessage
)

data class AssistantMessage(
    val role: String,
    val content: String,
    @SerializedName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    /** Present only when a model used its own tool syntax instead of the tags. */
    @SerializedName("tool_calls") val toolCalls: List<StreamToolCall>? = null
) {
    val thinking: String? get() = reasoningContent ?: reasoning
}

data class ModelResponse(
    val data: List<ModelInfo>
)

// ---------------------------------------------------------------------
// Anthropic Messages API. Claude is the one provider that is not
// OpenAI-shaped, so it has its own request/response models and its own
// endpoint (/v1/messages) rather than /v1/chat/completions.
// ---------------------------------------------------------------------

data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean? = null,
    val temperature: Double? = null,
    @SerializedName("top_p") val topP: Double? = null
)

data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentBlock>
)

data class AnthropicContentBlock(
    val type: String = "text",
    val text: String? = null,
    val thinking: String? = null,
    val source: AnthropicImageSource? = null
)

data class AnthropicImageSource(
    val type: String = "base64",
    @SerializedName("media_type") val mediaType: String,
    val data: String
)

data class AnthropicResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<AnthropicContentBlock>? = null,
    @SerializedName("stop_reason") val stopReason: String? = null
) {
    /** The reply's text, from whichever content blocks carried it. */
    val text: String
        get() = content?.filter { it.type == "text" }
            ?.joinToString("") { it.text.orEmpty() }
            .orEmpty()

    /** The reply's thinking, if the model produced any extended-thinking blocks. */
    val thinking: String?
        get() = content?.filter { it.type == "thinking" }
            ?.joinToString("") { it.thinking.orEmpty() }
            ?.takeIf { it.isNotBlank() }
}

data class AnthropicStreamEvent(
    val type: String? = null,
    val index: Int? = null,
    val delta: AnthropicDelta? = null,
    val message: AnthropicResponse? = null
)

data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
    val thinking: String? = null
)

data class ModelInfo(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val context_length: Int = 0,
    val architecture: Architecture? = null,
    val pricing: Pricing? = null
) {
    val cleanId: String get() = id.removePrefix("models/").removePrefix("models:")

    val displayName: String
        get() {
            val rawName = name?.takeIf { it.isNotBlank() } ?: id
            val clean = rawName.removePrefix("models/").removePrefix("models:")
            return if (clean.startsWith("gemini-", ignoreCase = true) && !clean.contains(" ")) {
                clean.split("-").joinToString(" ") { part ->
                    when {
                        part.equals("gemini", ignoreCase = true) -> "Gemini"
                        part.equals("pro", ignoreCase = true) -> "Pro"
                        part.equals("flash", ignoreCase = true) -> "Flash"
                        part.equals("lite", ignoreCase = true) -> "Lite"
                        part.equals("preview", ignoreCase = true) -> "(Preview)"
                        part.equals("exp", ignoreCase = true) -> "(Experimental)"
                        part.equals("latest", ignoreCase = true) -> "Latest"
                        part.matches(Regex("""\d+(\.\d+)*""")) -> part
                        else -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                }
            } else {
                clean
            }
        }

    val isVision: Boolean
        get() {
            if (architecture?.modality?.contains("image") == true ||
                architecture?.modality?.contains("multimodal") == true ||
                architecture?.modality?.contains("vision") == true
            ) {
                return true
            }
            val lowerId = id.lowercase()
            val lowerName = (name ?: "").lowercase()
            val lowerDesc = (description ?: "").lowercase()

            val visionKeywords = listOf(
                "vision", "-vl", "vl-", "-v", "visual", "multimodal",
                "gpt-5.6", "gpt-4.1", "gpt-4o", "gpt-4-turbo", "o1", "o3", "o4", "chatgpt-4o",
                "gemini",
                "claude-3", "claude-4", "claude-sonnet-4", "claude-opus-4",
                "pixtral", "mistral-large",
                "grok-2-vision", "grok-vision", "grok-3", "grok-4",
                "llava", "qwen-vl", "qwen2-vl", "qwen2.5-vl",
                "llama-3.2-11b-vision", "llama-3.2-90b-vision", "llama-4",
                "minicpm-v", "internvl", "cogvlm", "florence", "phi-3-vision", "phi-3.5-vision",
                "deepseek-v4-flash-vision"
            )

            return visionKeywords.any {
                lowerId.contains(it) || lowerName.contains(it) ||
                        lowerDesc.contains("multimodal") || lowerDesc.contains("vision") || lowerDesc.contains("image")
            }
        }

    val capabilities: List<String>
        get() {
            val list = mutableListOf<String>()
            if (isVision) list.add("vision")
            return list
        }
}

data class Architecture(
    val modality: String? = null,
    val tokenizer: String? = null,
    val instruct_type: String? = null
)

data class Pricing(
    val prompt: String,
    val completion: String,
    val request: String,
    val image: String
)
