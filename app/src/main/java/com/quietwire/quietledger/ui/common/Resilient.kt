package com.quietwire.quietledger.ui.common

import android.util.Log
import com.quietwire.quietledger.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Keeps a screen's state flow alive across a failure, and recovers by itself.
 *
 * The problem this replaces: `catch { emit(empty) }`. In Kotlin Flow, `catch`
 * emits and then **completes** the flow. Paired with `stateIn` and
 * `WhileSubscribed`, one transient failure pinned a screen to its fallback
 * until every subscriber went away for the timeout. Navigating off the screen
 * and back fixed it, which is exactly why the symptom looked random.
 *
 * Adding a bounded `retryWhen` in front of it was a patch, not a fix. It
 * covered a short gap and still gave up permanently on a longer one.
 *
 * This retries for as long as anyone is listening, with backoff capped so a
 * persistent failure does not spin. It reports trouble after a few attempts so
 * the UI can say something honest, and it keeps trying afterwards, so recovery
 * needs no interaction.
 *
 * @param attemptsBeforeReporting failures tolerated silently first, since the
 *   usual cause is a momentary gap around unlocking and flashing an error for
 *   that would be its own kind of lie.
 * @param unavailable state describing "cannot read this", which must be
 *   distinct from "there is nothing here". Telling someone their financial
 *   records are empty when they are merely unreachable is the worst thing this
 *   app could say.
 */
fun <T> Flow<T>.resilient(
    unavailable: T,
    attemptsBeforeReporting: Int = 3,
    initialDelayMillis: Long = 200,
    maxDelayMillis: Long = 5_000
): Flow<T> = flow {
    var attempt = 0
    while (true) {
        try {
            emitAll(this@resilient)
            // Upstream finished of its own accord, so there is nothing to retry.
            return@flow
        } catch (cancellation: CancellationException) {
            // Collection was cancelled. Never swallowed: doing so would keep a
            // ViewModel's flow running after its scope had gone.
            throw cancellation
        } catch (failure: Throwable) {
            attempt++
            // Logged, not swallowed. The first version of this discarded the
            // exception, which meant a screen could say "could not read your
            // finances" while nothing anywhere recorded why. That turned a
            // diagnosable fault into guesswork.
            //
            // Debug builds only, and the message only: a stack trace from a
            // database layer can quote SQL, and SQL here contains amounts.
            // Recorded in all builds so the About screen can show it, and
            // logged in debug builds for adb. A release build should still be
            // able to explain itself without a cable attached.
            ReadFailureLog.record(attempt, failure)
            if (BuildConfig.DEBUG) {
                Log.w(
                    "QLResilient",
                    "attempt $attempt failed: ${failure.javaClass.name}: ${failure.message}"
                )
            }
            if (attempt == attemptsBeforeReporting) {
                emit(unavailable)
            }
            delay((initialDelayMillis * attempt).coerceAtMost(maxDelayMillis))
        }
    }
}
