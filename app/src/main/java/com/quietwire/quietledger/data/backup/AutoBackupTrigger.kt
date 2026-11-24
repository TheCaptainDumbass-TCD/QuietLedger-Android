package com.quietwire.quietledger.data.backup

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.InvalidationTracker
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a backup shortly after anything in the database changes.
 *
 * It used to write when the app left the foreground, and that was the wrong
 * moment. Swiping the app away from recents kills the process without
 * reliably delivering `onStop`: a spot price update was followed by a close
 * and reopen, and nothing was written or even logged. The event the whole
 * mechanism depended on simply never arrived.
 *
 * Writing while the app is being used avoids that entirely. Room's
 * invalidation tracker reports every write to every table, so a change is
 * caught at the moment it is made, with the process alive and the database
 * open. There is nothing to race against.
 *
 * Leaving the foreground is still observed, as a backstop for a change made in
 * the last second or two. That is belt and braces now rather than the only
 * mechanism.
 */
@Singleton
class AutoBackupTrigger @Inject constructor(
    private val autoBackup: AutoBackup,
    private val databases: DatabaseHolder
) : DefaultLifecycleObserver {

    // Outlives every screen, so it cannot be cancelled by one going away.
    // SupervisorJob so a single failure does not stop the rest.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pending: Job? = null
    private var watching: QuietLedgerDatabase? = null
    private var observer: InvalidationTracker.Observer? = null

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Re-attached whenever the database is replaced, which happens on every
        // unlock. An observer registered once would be watching a closed
        // instance after the first lock.
        scope.launch {
            databases.observe().filterNotNull().collect { database ->
                attachTo(database)
            }
        }
    }

    private fun attachTo(database: QuietLedgerDatabase) {
        if (watching === database) return

        observer?.let { old ->
            runCatching { watching?.invalidationTracker?.removeObserver(old) }
        }

        val tracker = object : InvalidationTracker.Observer(WATCHED_TABLES) {
            override fun onInvalidated(tables: Set<String>) {
                Log.i(TAG, "data changed in $tables")
                scheduleBackup()
            }
        }

        database.invalidationTracker.addObserver(tracker)
        watching = database
        observer = tracker
        Log.i(TAG, "watching the database for changes")
    }

    /**
     * Waits a moment, then writes.
     *
     * Debounced because one user action can touch several tables, and an
     * import writes hundreds of rows. Each new change replaces the pending
     * write, so a burst produces a single backup once it settles.
     */
    private fun scheduleBackup() {
        pending?.cancel()
        pending = scope.launch {
            delay(SETTLE_MILLIS)
            val written = autoBackup.writeIfChanged()
            Log.i(TAG, if (written) "backup written after a change" else "no backup needed")
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // A backstop only. Not relied on, because it does not always arrive.
        if (databases.observe().value == null) return
        if (!autoBackup.configured) return

        scope.launch {
            val written = autoBackup.writeIfChanged()
            if (written) Log.i(TAG, "backup written on leaving the foreground")
        }
    }

    internal companion object {
        const val TAG = "QLAutoBackup"

        /** Long enough for an import to finish, short enough to feel immediate. */
        const val SETTLE_MILLIS = 3_000L

        /**
         * Every table. A change to any of them belongs in a backup.
         *
         * Internal rather than private so a test can compare it against the
         * database's own exported schema. A table missing here is a change
         * that silently never gets backed up, with nothing to report it.
         */
        val WATCHED_TABLES = arrayOf(
            "debt",
            "recurring_expense",
            "balance_change",
            "savings_account",
            "holding",
            "metal_lot",
            "metal_spot_price"
        )
    }
}
