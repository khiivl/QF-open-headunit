package com.andrerinas.openheadunit.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SystemUidShellStreamTest {

    private fun hasSh(): Boolean = try {
        ProcessBuilder("sh", "-c", "exit 0").start().waitFor() == 0
    } catch (_: Exception) { false }

    @Test fun `lines reach the handler and the stream ends with the command`() = runBlocking {
        assumeTrue(hasSh())
        val lines = mutableListOf<String>()
        val why = SystemUidShell.streamLines("echo one; echo two") { lines += it }
        assertEquals(listOf("one", "two"), lines)
        assertEquals("stream closed", why)
    }

    @Test fun `cancelling ends a stream that is blocked waiting for output`() = runBlocking {
        assumeTrue(hasSh())
        val firstLine = CompletableDeferred<Unit>()
        // Prints one line and then sits silent, like a filtered logcat between key presses.
        val stream = async(Dispatchers.IO) {
            SystemUidShell.streamLines("echo up; sleep 30") { firstLine.complete(Unit) }
        }
        withTimeout(10_000) { firstLine.await() }
        // The old completion-handler version never came back from this within the time limit.
        withTimeout(5_000) { stream.cancelAndJoin() }
        assertTrue(stream.isCancelled)
    }
}
