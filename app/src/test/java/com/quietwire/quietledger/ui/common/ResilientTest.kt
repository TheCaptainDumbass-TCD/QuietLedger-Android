package com.quietwire.quietledger.ui.common

import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins down the behaviour that caused screens to show stale or empty data.
 *
 * The first test documents the old bug directly, so the difference is a fact in
 * the suite rather than a claim in a commit message.
 */
class ResilientTest {

    private data class State(val value: String, val unavailable: Boolean = false)

    private val unavailable = State("", unavailable = true)

    @Test
    fun `catch ends a flow, which is what made screens stick`() = runTest {
        var attempts = 0
        val upstream = flow {
            attempts++
            if (attempts < 3) error("database is locked")
            emit(State("real data"))
        }

        // The old arrangement. It emits the fallback and then completes, so the
        // retry that would have succeeded never happens.
        val collected = upstream.catch { emit(unavailable) }.toList()

        assertEquals(1, collected.size)
        assertTrue("the screen was left saying it could not read", collected.single().unavailable)
        assertEquals("upstream was never retried", 1, attempts)
    }

    @Test
    fun `resilient retries until the source works, then emits real data`() = runTest {
        val attempts = AtomicInteger()
        val upstream = flow {
            if (attempts.incrementAndGet() < 3) error("database is locked")
            emit(State("real data"))
        }

        val result = upstream.resilient(unavailable, initialDelayMillis = 1).first {
            !it.unavailable
        }

        assertEquals("real data", result.value)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `a brief failure never reports unavailable at all`() = runTest {
        val attempts = AtomicInteger()
        val upstream = flow {
            if (attempts.incrementAndGet() < 3) error("locked")
            emit(State("real data"))
        }

        // Two failures are within the tolerance, so nothing is shown to the
        // user. Flashing an error during the gap around unlocking would be its
        // own kind of lie.
        val collected = upstream.resilient(
            unavailable, attemptsBeforeReporting = 3, initialDelayMillis = 1
        ).take(1).toList()

        assertEquals(1, collected.size)
        assertEquals("real data", collected.single().value)
    }

    @Test
    fun `a sustained failure says so, and still keeps trying`() = runTest {
        val attempts = AtomicInteger()
        val upstream = flow {
            // Fails long enough to be reported, then recovers by itself.
            if (attempts.incrementAndGet() < 6) error("locked")
            emit(State("back again"))
        }

        val collected = upstream.resilient(
            unavailable, attemptsBeforeReporting = 3, initialDelayMillis = 1
        ).take(2).toList()

        assertTrue("it reported trouble", collected.first().unavailable)
        // The part the old code got wrong: recovery with no interaction.
        assertEquals("back again", collected[1].value)
    }

    @Test
    fun `it recovers without the screen being left and re-entered`() = runTest {
        val attempts = AtomicInteger()
        val upstream = flow {
            if (attempts.incrementAndGet() < 4) error("locked")
            emit(State("real data"))
        }

        // stateIn with WhileSubscribed is the production arrangement, and is
        // what turned one transient failure into a screen stuck until every
        // subscriber went away for the timeout.
        val state = upstream
            .resilient(unavailable, attemptsBeforeReporting = 2, initialDelayMillis = 1)
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5_000), State("initial"))

        assertEquals("real data", state.first { it.value == "real data" }.value)
    }

    @Test
    fun `a flow that completes normally is not retried`() = runTest {
        val attempts = AtomicInteger()
        val upstream = flow {
            attempts.incrementAndGet()
            emit(State("one"))
        }

        assertEquals(listOf(State("one")), upstream.resilient(unavailable).toList())
        assertEquals("no needless re-subscription", 1, attempts.get())
    }
}
