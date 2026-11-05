package com.android5.ai

import org.junit.Assert.*
import org.junit.Test

class ToolPolicyTest {
    @Test fun aliasesCannotBypassDisabledTools() {
        for ((name, alias) in listOf("read" to "cat", "shell" to "exec")) {
            assertFalse(ToolPolicy.isAllowed(alias, true, setOf(name)))
            assertFalse(ToolPolicy.isAllowed(name, true, setOf(alias)))
            assertEquals(emptySet<String>(), ToolPolicy.setEnabled(setOf(name, alias), alias, true))
        }
    }

    @Test fun disabledToolsAreRemovedFromBothProviderFormats() {
        val disabled = setOf("delete", "shell", "read")
        val openAi = NativeTools.openAiDefinitions(disabled).map { (it["function"] as Map<*, *>)["name"] }.toSet()
        val claude = NativeTools.anthropicDefinitions(disabled).map { it["name"] }.toSet()
        assertEquals(openAi, claude)
        assertFalse(openAi.any { it in setOf("delete", "shell", "exec", "read", "cat") })
        assertTrue(openAi.contains("list"))
        assertTrue(NativeTools.openAiDefinitions(AiTools.names.toSet()).isEmpty())
    }

    @Test fun executionPolicyRejectsDisabledAndUnknownCalls() {
        for ((name, master, disabled) in listOf(
            Triple("write", true, setOf("write")), Triple("time", false, emptySet()),
            Triple("exec", true, setOf("shell")), Triple("invented", true, emptySet()))) {
            try { ToolPolicy.requireAllowed(name, master, disabled); fail("Allowed $name") }
            catch (_: IllegalArgumentException) { }
        }
        ToolPolicy.requireAllowed("time", true, emptySet())
    }
}
