package com.android5.ai

internal object ToolPolicy {
    val choices: List<String> get() = AiTools.names.filterNot { it == "cat" || it == "exec" }

    fun aliases(name: String): Set<String> = when (name) {
        "read", "cat" -> setOf("read", "cat")
        "shell", "exec" -> setOf("shell", "exec")
        else -> setOf(name)
    }

    fun isAllowed(name: String, enabled: Boolean, disabled: Set<String>): Boolean =
        enabled && name in AiTools.names && aliases(name).none { it in disabled }

    fun requireAllowed(name: String, enabled: Boolean, disabled: Set<String>) {
        require(isAllowed(name, enabled, disabled)) { "This tool is disabled in the user's settings: $name" }
    }

    fun setEnabled(disabled: Set<String>, name: String, enabled: Boolean): Set<String> =
        if (enabled) disabled - aliases(name) else disabled + aliases(name)
}
