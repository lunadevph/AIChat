package com.android5.ai

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class AuditRegressionTest {
    @Test fun workspaceDeletionDoesNotFollowDirectoryLinks() {
        val base = java.nio.file.Files.createTempDirectory("audit-workspace").toFile()
        try {
            val outside = java.io.File(base, "outside").apply { mkdirs() }
            val keep = java.io.File(outside, "keep.txt").apply { writeText("keep") }
            val workspace = java.io.File(base, "workspace").apply { mkdirs() }
            java.io.File(workspace, "ordinary.txt").writeText("delete")
            try {
                java.nio.file.Files.createSymbolicLink(java.io.File(workspace, "link").toPath(), outside.toPath())
            } catch (e: Exception) {
                org.junit.Assume.assumeNoException("Host does not permit symbolic links", e)
            }
            assertTrue(SafeFileTree.delete(workspace) { java.nio.file.Files.isSymbolicLink(it.toPath()) })
            assertEquals("keep", keep.readText())
        } finally { SafeFileTree.delete(base) { java.nio.file.Files.isSymbolicLink(it.toPath()) } }
    }

    @Test fun savedSequentialToolsRemainSequentialAndKeepFinalAnswer() {
        val tools = listOf(
            ToolRun("write", callId = "one", arguments = "{}", result = "written", exchangeId = "round1", assistantContent = "Writing"),
            ToolRun("read", callId = "two", arguments = "{}", result = "read", exchangeId = "round2", assistantContent = "Checking")
        )
        val original = Message(role = "assistant", content = "Writing\n\nChecking\n\nDone", tools = tools)
        val restored = NativeTools.restoreExchange(Gson().fromJson(Gson().toJson(original), Message::class.java))
        assertEquals(listOf("assistant", "tool", "assistant", "tool", "assistant"), restored.map { it.role })
        assertEquals(listOf("one", "two"), restored.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals("Done", restored.last().content)
        assertEquals("Checking", restored[2].content)
    }

    @Test fun errorTextDoesNotBecomeAssistantHistory() {
        val message = Message(role = "assistant", content = "API failed", isError = true,
            tools = listOf(ToolRun("time", callId = "one", arguments = "{}", result = "ok")))
        assertNull(NativeTools.restoreExchange(message).first().content)
    }

    @Test fun previewsLeaveLargeFileRemainderUnread() {
        val reader = StringReader("abcdef")
        assertEquals("abc", BoundedText.read(reader, 3))
        assertEquals('d'.code, reader.read())
        assertEquals("xy", BoundedText.read(StringReader("xy"), 10))
        assertEquals("", BoundedText.read(StringReader("xy"), 0))
    }

    @Test fun conversationPathsRejectTraversalAndDatabaseNames() {
        for (bad in listOf("index", "INDEX", "../index", "index.db", "/data/file", "a/b", "a\\b", "", ".")) {
            try { ConversationIds.requireValid(bad); fail("Accepted $bad") } catch (_: IllegalArgumentException) { }
        }
        ConversationIds.requireValid("chat-0123_ABC")
    }
}
