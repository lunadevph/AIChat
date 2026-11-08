package com.android5.ai

/** Best-effort credential scrubbing before diagnostics ever reach disk. */
internal object AppLogRedactor {
    private val fields = "access_token|refresh_token|id_token_hint|id_token|code_verifier|client_secret|api_key|apikey|api-key|x-api-key|authorization|cookie|set-cookie|password"
    private val quoted = Regex("([\"'](?:$fields)[\"']\\s*:\\s*[\"'])[^\"']*([\"'])", RegexOption.IGNORE_CASE)
    private val assigned = Regex("(\\b(?:$fields)\\s*[=:]\\s*)[^\\s&;,]+", RegexOption.IGNORE_CASE)
    private val headers = Regex("(\\b(?:authorization|x-api-key|cookie|set-cookie)\\s*:\\s*)[^\\r\\n]+", RegexOption.IGNORE_CASE)
    private val querySecrets = Regex("([?&](?:code|state|nonce)=)[^&#\\s]+", RegexOption.IGNORE_CASE)
    private val bearer = Regex("(\\b(?:Bearer|Basic)\\s+)[^\\s\"'<>&]+", RegexOption.IGNORE_CASE)
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")
    private val keys = Regex("\\b(?:sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{16,}|gsk_[A-Za-z0-9_-]{8,}|nvapi-[A-Za-z0-9_-]{8,})\\b")
    fun redact(text: String): String {
        var result = headers.replace(text) { it.groupValues[1] + "[REDACTED]" }
        result = quoted.replace(result) { it.groupValues[1] + "[REDACTED]" + it.groupValues[2] }
        result = assigned.replace(result) { it.groupValues[1] + "[REDACTED]" }
        result = querySecrets.replace(result) { it.groupValues[1] + "[REDACTED]" }
        result = bearer.replace(result) { it.groupValues[1] + "[REDACTED]" }
        return keys.replace(jwt.replace(result, "[REDACTED]"), "[REDACTED]")
    }
}
