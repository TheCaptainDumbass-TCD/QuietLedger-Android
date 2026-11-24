package com.quietwire.quietledger.data.db

import android.content.Context
import android.util.Log
import com.quietwire.quietledger.BuildConfig
import androidx.room.Room
import com.quietwire.quietledger.core.crypto.DatabaseKeyStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens the encrypted database once the key is available, and closes it when
 * the app locks.
 *
 * The database cannot be created at injection time, because until the user
 * has unlocked there is no key to open it with. So this holds the open
 * instance and hands it out, rather than Hilt providing the database
 * directly.
 */
@Singleton
class DatabaseProvider @Inject constructor(
    private val context: Context,
    private val keyStore: DatabaseKeyStore
) : DatabaseHolder {

    /**
     * Observable so that long-lived Flows follow a lock and unlock.
     *
     * A StateFlow rather than an AtomicReference: the value is read the same
     * way, and subscribers are told when it changes instead of holding a
     * reference to a closed database for ever.
     */
    private val instance = MutableStateFlow<QuietLedgerDatabase?>(null)

    val isOpen: Boolean get() = instance.value != null

    /** Throws if the app has not been unlocked. Callers below the UI can rely on this. */
    override fun require(): QuietLedgerDatabase =
        instance.value ?: throw IllegalStateException("Database is locked")

    override fun observe(): StateFlow<QuietLedgerDatabase?> = instance.asStateFlow()

    /**
     * The key SQLCipher is actively using, held until the database closes.
     *
     * This must stay intact for as long as the database is open.
     * [SupportOpenHelperFactory] keeps the array **by reference** and re-reads
     * it every time it opens a connection, so zeroing it after the first
     * connection leaves every later one decrypting with 32 zero bytes.
     *
     * That was a real bug, and a nasty one: the first connection worked, so
     * setup and imports succeeded and the data really was written. Then Room
     * opened another connection for its invalidation tracker or a second
     * thread, SQLCipher failed with "file is not a database", and every screen
     * reported that it could not read anything while the data sat safely on
     * disk. The original code zeroed it here with a comment asserting that
     * SQLCipher copies what it needs. It does not.
     */
    private val activeKey = AtomicReference<ByteArray?>(null)

    /**
     * Opens the database with the supplied key.
     *
     * The caller's array is zeroed, because it is theirs and they are done with
     * it. A private copy is retained for SQLCipher and zeroed in [close], so
     * the key still disappears from memory when the app locks.
     */
    fun open(databaseKey: ByteArray): QuietLedgerDatabase {
        instance.value?.let { return it }

        System.loadLibrary("sqlcipher")

        val retained = databaseKey.copyOf()
        val factory = SupportOpenHelperFactory(retained)
        val db = Room.databaseBuilder(
            context,
            QuietLedgerDatabase::class.java,
            QuietLedgerDatabase.FILE_NAME
        )
            .openHelperFactory(factory)
            .addMigrations(*QuietLedgerDatabase.ALL_MIGRATIONS)
            // No fallbackToDestructiveMigration. Silently wiping someone's
            // financial records because a migration was missing is never the
            // right recovery.
            .build()

        // Force the file open now so a bad key fails here rather than on the
        // first query from some unrelated screen.
        db.openHelper.writableDatabase

        instance.value = db
        activeKey.getAndSet(retained)?.fill(0)
        if (BuildConfig.DEBUG) Log.i("QLDb", "opened; subscribers now see a database")
        databaseKey.fill(0)
        return db
    }

    fun close() {
        if (BuildConfig.DEBUG) Log.i("QLDb", "close() called")
        instance.getAndUpdate { null }?.close()
        // Now that nothing can open another connection, the key can go.
        activeKey.getAndSet(null)?.fill(0)
    }
}
