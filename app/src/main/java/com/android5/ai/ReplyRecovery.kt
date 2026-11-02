package com.android5.ai

internal object ReplyRecovery {
    /** Inspect the body, since some endpoints incorrectly label SSE as JSON. */
    fun isJson(source: okio.BufferedSource): Boolean = source.peek().use { peek ->
        try {
            var first = peek.readByte().toInt().toChar()
            while (first.isWhitespace()) first = peek.readByte().toInt().toChar()
            first == '{'
        } catch (_: java.io.EOFException) { false }
    }

    fun retryRequest(request: ChatRequest, provider: String, retrying: Boolean): ChatRequest {
        if (!retrying) return request
        return when {
            provider == "OpenRouter" -> request.copy(reasoning = mapOf("effort" to "low"))
            provider == "Groq" && request.model.contains("gpt-oss", ignoreCase = true) -> request.copy(reasoningEffort = "low")
            provider == "DeepSeek" -> request.copy(thinking = mapOf("type" to "disabled"))
            else -> request
        }
    }

    fun finishReason(raw: String?): String? = raw?.trim()?.takeUnless { it.isEmpty() || it == "null" }

    fun needsMoreTokens(reply: ToolReply): Boolean =
        reply.finishReason in setOf("length", "max_tokens") || !reply.reasoning.isNullOrBlank()

    fun tokenLimit(configured: Int, retrying: Boolean, exhausted: Boolean): Int {
        val base = configured.coerceAtLeast(1)
        if (!retrying || !exhausted) return base
        return maxOf(base.toLong(), (base.toLong() * 2).coerceIn(8192L, 32768L)).toInt()
    }
}
