package com.android5.ai

internal object ConversationIds {
    fun requireValid(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && !id.equals("index", ignoreCase = true)) { "Invalid conversation identifier" }
    }
}
