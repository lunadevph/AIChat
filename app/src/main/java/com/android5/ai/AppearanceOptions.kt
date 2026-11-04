package com.android5.ai

object AppearanceOptions {
    val themes = listOf("dynamic", "light", "amoled", "midnight", "ocean", "forest", "sunset")
    val bubbles = listOf("outlined", "filled", "tonal", "classic")

    fun theme(value: String): String = when (value) {
        "old_android" -> "forest"
        "holo" -> "ocean"
        "compact" -> "dynamic"
        in themes -> value
        else -> "dynamic"
    }

    fun bubble(value: String): String = value.takeIf { it in bubbles } ?: "tonal"
}
