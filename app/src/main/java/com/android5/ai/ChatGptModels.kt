package com.android5.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal object ChatGptModels {
    fun parse(json: JsonObject): List<ModelInfo> = (json.getAsJsonArray("models") ?: JsonArray()).mapNotNull {
        val item = it.asJsonObject
        if (item["visibility"]?.asString != "list") null
        else ModelInfo(item["slug"].asString, item["display_name"]?.asString)
    }.distinctBy { it.id }

    fun select(preferred: String, available: List<ModelInfo>): String =
        available.firstOrNull { it.id == preferred }?.id ?: available.firstOrNull()?.id.orEmpty()
}
