package com.android5.ai

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val shell get() = if (windows) listOf("cmd.exe", "/c") else listOf("sh", "-c")
    private val busyCommand get() = if (windows) "for /L %i in (1,0,2) do @rem waiting" else "while :; do :; done"

    @Test fun capturesOutputAndSuccessfulExit() = runBlocking {
        val result = ShellRunner.run("echo hello", temporary.root, shell = shell)
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.output.trim())
        assertFalse(result.timedOut)
    }

    @Test fun reportsNonzeroExit() = runBlocking {
        val result = ShellRunner.run(if (windows) "exit /b 7" else "exit 7", temporary.root, shell = shell)
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test fun boundsOutputWhileStillCompletingTheCommand() = runBlocking {
        val result = ShellRunner.run("echo " + "x".repeat(2048), temporary.root, maxBytes = 64, shell = shell)
        assertEquals(0, result.exitCode)
        assertEquals(64, result.output.toByteArray().size)
        assertTrue(result.truncated)
    }

    @Test fun stopsACommandAtItsDeadline() = runBlocking {
        val result = ShellRunner.run(busyCommand, temporary.root, timeoutMillis = 150, shell = shell)
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
    }

    @Test fun generationCancellationDoesNotWaitForTheCommandDeadline() = runBlocking {
        val started = System.nanoTime()
        try {
            withTimeout(150) { ShellRunner.run(busyCommand, temporary.root, timeoutMillis = 10_000, shell = shell) }
            fail("Cancellation must propagate")
        } catch (_: TimeoutCancellationException) {
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        }
    }
}
