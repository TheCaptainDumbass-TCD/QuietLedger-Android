package com.quietwire.quietledger.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A small in-memory ring of recent read failures, so the app can say why a
 * screen was blank.
 *
 * The original code caught these and emitted an empty state, recording nothing
 * anywhere. A screen then said "could not read your finances" while the only
 * explanation existed for a few milliseconds inside a `catch` block. Diagnosing
 * that needed a USB cable and a rebuild with logging added.
 *
 * In memory on purpose. These are diagnostics, not history: writing them to the
 * database would mean recording failures in the thing that is failing, and
 * persisting them would keep stale alarms alive across restarts.
 *
 * Exception *messages* only. A stack trace from this layer can quote SQL, and
 * the SQL here contains financial amounts.
 */
object ReadFailureLog {

    private const val CAPACITY = 20
    private val entries = ArrayDeque<String>(CAPACITY)
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Synchronized
    fun record(attempt: Int, failure: Throwable) {
        val at = Instant.now().atZone(ZoneId.systemDefault()).format(clockFormat)
        val line = "$at  attempt $attempt  ${failure.javaClass.simpleName}: ${failure.message}"
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(line)
    }

    /** Most recent first, which is the order they get read in. */
    @Synchronized
    fun recent(): List<String> = entries.toList().asReversed()

    @Synchronized
    fun clear() = entries.clear()
}
