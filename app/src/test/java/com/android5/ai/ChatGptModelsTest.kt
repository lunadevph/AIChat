package com.android5.ai

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ChatGptModelsTest {
    @Test fun usesAccountCatalogOrderAndVisibilityInsteadOfStaticDefaults() {
        val catalog = JsonParser.parseString("""{"models":[{"slug":"second","display_name":"Z model","visibility":"list"},{"slug":"hidden","visibility":"hide"},{"slug":"first","display_name":"A model","visibility":"list"}]}""").asJsonObject
        val models = ChatGptModels.parse(catalog)
        assertEquals(listOf("second", "first"), models.map { it.id })
        assertEquals("second", ChatGptModels.select("retired-default", models))
        assertEquals("first", ChatGptModels.select("first", models))
    }

    @Test fun absentCatalogDoesNotPretendToGrantModelAccess() {
        assertTrue(ChatGptModels.parse(JsonParser.parseString("""{"data":[{"id":"api-key-model"}]}""").asJsonObject).isEmpty())
        assertEquals("", ChatGptModels.select("unknown", emptyList()))
    }
}
