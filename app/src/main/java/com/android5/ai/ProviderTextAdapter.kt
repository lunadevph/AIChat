package com.android5.ai

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

/** Compatible endpoints may return text blocks instead of a plain content string. */
internal class ProviderTextAdapter : JsonDeserializer<String> {
    override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): String = text(json)

    private fun text(value: JsonElement?): String = when {
        value == null || value.isJsonNull -> ""
        value.isJsonPrimitive -> value.asString
        value.isJsonArray -> value.asJsonArray.joinToString("") { text(it) }
        value.isJsonObject -> {
            val block = value.asJsonObject
            if (block.has("text")) text(block["text"])
            else if (block.has("value")) text(block["value"])
            else ""
        }
        else -> ""
    }
}
