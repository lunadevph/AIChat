package com.android5.ai

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AppLoggerTest {
    @Test fun redactsCredentialsButRetainsUsefulDiagnostics() {
        val raw = """Authorization: Bearer SECRET_BEARER
            |{"access_token":"SECRET_ACCESS","refresh_token":"SECRET_REFRESH","id_token":"SECRET_ID","ok":false}
            |url=https://auth.openai.com/authorize?id_token_hint=SECRET_HINT&scope=openid
            |{"x-api-key":"SECRET_JSON_HEADER","authorization":"SECRET_JSON_AUTH"}
            |url=http://127.0.0.1/auth/callback?code=SECRET_CODE&state=SECRET_STATE
            |api_key=SECRET_KEY
            |x-api-key: SECRET_HEADER
            |request failed HTTP 413 file=project.kt
        """.trimMargin()
        val clean = AppLogRedactor.redact(raw)
        for (secret in listOf("SECRET_BEARER", "SECRET_ACCESS", "SECRET_REFRESH", "SECRET_ID", "SECRET_HINT", "SECRET_KEY", "SECRET_HEADER", "SECRET_JSON_HEADER", "SECRET_JSON_AUTH", "SECRET_CODE", "SECRET_STATE")) assertFalse(clean.contains(secret))
        assertTrue(clean.contains("HTTP 413"))
        assertTrue(clean.contains("project.kt"))
        assertTrue(clean.contains("\"ok\":false"))
    }

    @Test fun appendsWithoutOverwritingAndRotatesWithinBudget() {
        val root = Files.createTempDirectory("applogger-test").toFile()
        try {
            val writer = RotatingAppLog(root, "session", maxFileBytes = 128, retainedFiles = 3)
            writer.append("first"); writer.append("second")
            assertTrue(writer.files().single().readText().contains("first\nsecond\n"))
            repeat(30) { writer.append("record-$it " + "x".repeat(80)) }
            assertTrue(writer.files().size <= 3)
            assertTrue(writer.files().all { it.length() <= 128 })
            assertTrue(writer.files().any { it.readText().contains("record-29") })
            assertTrue(writer.clear())
            assertTrue(writer.files().isEmpty())
            writer.append("after clear")
            assertEquals("after clear\n", writer.files().single().readText())
        } finally { SafeFileTree.delete(root) { Files.isSymbolicLink(it.toPath()) } }
    }

    @Test fun unicodeRecordsRemainValidAndSecretsAreRedactedOnDisk() {
        val root = Files.createTempDirectory("applogger-unicode").toFile()
        try {
            val writer = RotatingAppLog(root, "unicode", maxFileBytes = 128)
            writer.append("你好😀".repeat(100))
            writer.append("Bearer SUPER_SECRET_TOKEN")
            val all = writer.files().joinToString { it.readText() }
            assertFalse(all.contains('\uFFFD'))
            assertFalse(all.contains("SUPER_SECRET_TOKEN"))
            assertTrue(all.contains("[truncated]"))
            assertTrue(writer.files().all { it.length() <= 128 })
        } finally { SafeFileTree.delete(root) { Files.isSymbolicLink(it.toPath()) } }
    }

    @Test fun fallbackPidFilterDoesNotCollectAnotherProcess() {
        assertTrue(AppLogger.ownsLine("11-08 12:30:01.000 1234 1250 D AIChat: message", 1234))
        assertFalse(AppLogger.ownsLine("11-08 12:30:01.000 9999 9999 D Other: message", 1234))
        assertFalse(AppLogger.ownsLine("--------- beginning of main", 1234))
    }
}
