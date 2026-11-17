/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audiosource

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSourceAttemptDeadlineTest {
    @Test
    fun `child deadlines share the remaining parent budget and round up partial milliseconds`() {
        var now = 0L
        val parent = AudioSourceAttemptDeadline.start(10L) { now }
        now = TimeUnit.MILLISECONDS.toNanos(7L) + 1L
        val cappedChild = parent.child(100L)
        val shorterChild = parent.child(1L)

        assertEquals(3L, parent.remainingMillis())
        assertEquals(3L, cappedChild.remainingMillis())
        assertEquals(1L, shorterChild.remainingMillis())
        assertFalse(parent.isExpired())

        now = TimeUnit.MILLISECONDS.toNanos(10L)
        assertTrue(parent.isExpired())
        assertTrue(cappedChild.isExpired())
        assertTrue(shorterChild.isExpired())
    }

    @Test
    fun `an expired parent cannot be extended and does not start more work`() = runTest {
        var now = 0L
        val parent = AudioSourceAttemptDeadline.start(1L) { now }
        now = TimeUnit.MILLISECONDS.toNanos(2L)
        val child = parent.child(100L)
        var invoked = false

        val result = child.runWithin { invoked = true }

        assertNull(result)
        assertFalse(invoked)
        assertEquals(0L, child.remainingMillis())
    }

    @Test
    fun `runWithin returns successful results and cancels timed out work with cleanup`() = runTest {
        val deadline = AudioSourceAttemptDeadline.start(10L) {
            TimeUnit.MILLISECONDS.toNanos(testScheduler.currentTime)
        }
        var cleanedUp = false

        assertEquals("resolved", deadline.runWithin { "resolved" })
        val result = deadline.runWithin {
            try {
                delay(11L)
                "too late"
            } finally {
                cleanedUp = true
            }
        }

        assertNull(result)
        assertTrue(cleanedUp)
        assertEquals(10L, testScheduler.currentTime)
    }

    @Test
    fun `nested synchronous scopes restore their parent even when the provider throws`() {
        val parent = AudioSourceAttemptDeadline.start(20L) { 0L }
        val failure = IOException("provider unavailable")
        assertNull(AudioSourceAttemptScope.current())

        val thrown = assertThrows(IOException::class.java) {
            AudioSourceAttemptScope.withDeadline(parent) {
                AudioSourceAttemptScope.within(100L) {
                    assertEquals(20L, AudioSourceAttemptScope.current()!!.remainingMillis())
                }
                assertSame(parent, AudioSourceAttemptScope.current())
                AudioSourceAttemptScope.within(5L) {
                    assertEquals(5L, AudioSourceAttemptScope.current()!!.remainingMillis())
                    throw failure
                }
            }
        }

        assertSame(failure, thrown)
        assertNull(AudioSourceAttemptScope.current())
    }

    @Test
    fun `suspending scopes retain the parent deadline across suspension and restore it on timeout`() = runTest {
        val parent = AudioSourceAttemptDeadline.start(10L) {
            TimeUnit.MILLISECONDS.toNanos(testScheduler.currentTime)
        }
        val context = AudioSourceAttemptScope.withDeadline(parent) {
            AudioSourceAttemptScope.coroutineContextElement()
        }

        withContext(context) {
            val result = AudioSourceAttemptScope.withinSuspending(100L) {
                val child = AudioSourceAttemptScope.current()
                assertEquals(10L, child!!.remainingMillis())
                delay(1L)
                assertSame(child, AudioSourceAttemptScope.current())
                delay(10L)
                "too late"
            }

            assertNull(result)
            assertSame(parent, AudioSourceAttemptScope.current())
        }
        assertNull(AudioSourceAttemptScope.current())
        assertEquals(10L, testScheduler.currentTime)
    }

    @Test
    fun `cancelling the caller cancels provider work rather than waiting for the attempt timeout`() = runTest {
        var started = false
        var cleanedUp = false
        val job = launch {
            AudioSourceAttemptScope.withinSuspending(1_000L) {
                started = true
                try {
                    awaitCancellation()
                } finally {
                    cleanedUp = true
                }
            }
        }
        runCurrent()
        assertTrue(started)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(cleanedUp)
        assertEquals(0L, testScheduler.currentTime)
        assertNull(AudioSourceAttemptScope.current())
    }

    @Test
    fun `HTTP calls use only the remaining budget and reject already expired attempts`() {
        var now = 0L
        val deadline = AudioSourceAttemptDeadline.start(10L) { now }
        val call = OkHttpClient().newCall(Request.Builder().url("https://example.invalid/audio").build())
        assertSame(call, call.withAudioSourceAttemptDeadline())

        AudioSourceAttemptScope.withDeadline(deadline) {
            now = TimeUnit.MILLISECONDS.toNanos(7L)
            assertSame(call, call.withAudioSourceAttemptDeadline())
            assertEquals(TimeUnit.MILLISECONDS.toNanos(3L), call.timeout().timeoutNanos())

            now = TimeUnit.MILLISECONDS.toNanos(10L)
            assertThrows(AudioSourceAttemptTimedOutException::class.java) {
                call.withAudioSourceAttemptDeadline()
            }
        }
    }

    @Test
    fun `wrapped HTTP cancellation propagates cancellation with the original failure`() {
        val failure = IOException("provider failed", IOException("cAnCeLeD"))

        val cancelled = assertThrows(CancellationException::class.java) {
            failure.rethrowIfAudioSourceCancelled()
        }

        assertSame(failure, cancelled.cause)
    }

    @Test
    fun `HTTP timeouts remain eligible for fallback even with a cancelled call cause`() {
        val timeouts = listOf(
            AudioSourceAttemptTimedOutException(),
            SocketTimeoutException("read timed out"),
            InterruptedIOException("TIMEOUT"),
        )

        for (timeout in timeouts) {
            timeout.initCause(IOException("Canceled"))
            IOException("provider failed", timeout).rethrowIfAudioSourceCancelled()
        }
    }

    @Test
    fun `explicit coroutine cancellation is not swallowed by timeout classification`() {
        val cancellation = CancellationException("playback stopped")
        val timeout = SocketTimeoutException("timeout").apply { initCause(cancellation) }

        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
                IOException("provider failed", timeout).rethrowIfAudioSourceCancelled()
            },
        )
    }
}
