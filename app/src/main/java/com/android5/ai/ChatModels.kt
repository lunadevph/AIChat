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
    val reasoning: String? = null
) {
    val thinking: String? get() = reasoningContent ?: reasoning
}

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

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val localImageUrl: String? = null,
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
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
    val reasoning: String? = null
) {
    val thinking: String? get() = reasoningContent ?: reasoning
}

data class ModelResponse(
    val data: List<ModelInfo>
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
                "deepseek-v4-flash-vision", "nemotron"
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
