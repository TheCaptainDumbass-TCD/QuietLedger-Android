package com.quietwire.quietledger.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quietwire.quietledger.core.crypto.DatabaseKeyStore
import com.quietwire.quietledger.data.repository.DebtRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the bug that made every screen report it could not read anything.
 *
 * [SupportOpenHelperFactory] keeps the key array **by reference** and re-reads
 * it every time it opens a connection. The original [DatabaseProvider.open]
 * zeroed the array once the first connection was established, with a comment
 * asserting that SQLCipher copies what it needs. It does not.
 *
 * The result was vicious precisely because the first connection worked: setup
 * and imports succeeded and really did write the data. Then Room opened a
 * second connection, SQLCipher decrypted with 32 zero bytes, threw
 * "file is not a database", and every screen said the finances were unreadable
 * while they sat safely on disk.
 *
 * No JVM test could catch this. They substitute plain in-memory Room, so there
 * is no SQLCipher, no key and no second connection. It has to run on a device.
 *
 * Read only: it reopens and counts, and writes nothing.
 */
@RunWith(AndroidJUnit4::class)
class KeyLifetimeInstrumentedTest {

    @Test
    fun theDatabaseStillReadsAfterReopeningAConnection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keyStore = DatabaseKeyStore(context)

        if (!keyStore.isInitialised) return  // nothing set up on this device yet

        val provider = DatabaseProvider(context, keyStore)
        provider.open(keyStore.unlockWithKeystore())

        // A direct read on the first connection. This always worked, even with
        // the bug, which is why the fault looked so arbitrary.
        val direct = runBlocking { DebtRepository(provider).getOpen() }

        // Observing a Flow is the actual trigger. Room's invalidation tracker
        // runs "SELECT COUNT(*) FROM sqlite_schema" on its own connection, and
        // that is the query that failed on the device. An earlier version of
        // this test only closed and reopened the helper, which passed even with
        // the bug present and so proved nothing.
        val observed = runBlocking {
            withTimeoutOrNull(8_000) { DebtRepository(provider).observeOpen().first() }
        }

        assertNotNull(
            "observing a Flow produced nothing: the invalidation tracker could " +
                "not open its connection, which is the symptom the whole app showed",
            observed
        )
        assertEquals(
            "a Flow must see what a direct read sees",
            direct.size,
            observed!!.size
        )

        provider.close()
    }
}
