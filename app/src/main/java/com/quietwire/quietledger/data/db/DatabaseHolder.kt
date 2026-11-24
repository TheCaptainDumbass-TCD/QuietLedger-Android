package com.quietwire.quietledger.data.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hands out the open database.
 *
 * This exists as an interface so repositories do not depend on how the
 * database gets opened or unlocked, and so tests can supply a plain
 * in-memory database instead of an encrypted one. SQLCipher needs native
 * libraries that are not available to JVM unit tests, and the encryption is
 * not what those tests are checking.
 */
interface DatabaseHolder {
    /** Throws if the app is locked. Use for one-off reads and writes. */
    fun require(): QuietLedgerDatabase

    /**
     * The open database, or null while locked.
     *
     * Observable rather than only fetchable, because a long-lived Flow needs to
     * re-subscribe when the database is replaced. Locking closes the instance
     * and unlocking creates a new one, so a Flow that resolved the database
     * once stays attached to a dead one that will never emit again. Watching
     * this means every subscription follows the swap on its own.
     *
     * The default suits tests, where the database is open for the lifetime of
     * the test and never swapped. [DatabaseProvider] overrides it with a single
     * shared state flow, which is what production needs.
     */
    fun observe(): StateFlow<QuietLedgerDatabase?> =
        MutableStateFlow(runCatching { require() }.getOrNull())
}
