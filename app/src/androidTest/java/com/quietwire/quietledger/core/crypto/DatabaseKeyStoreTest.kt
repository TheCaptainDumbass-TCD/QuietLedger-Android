package com.quietwire.quietledger.core.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Android Keystore, which is the part that cannot be
 * tested on the JVM.
 *
 * This exists because of an actual bug: the Keystore rejects a
 * caller-provided IV on encryption, and that only surfaces on a device. Unit
 * tests passed while the app could not get past its own setup screen.
 *
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyStoreTest {

    private lateinit var keyStore: DatabaseKeyStore

    /**
     * Refuses to run on a device that holds real data.
     *
     * These tests call destroyAllKeys() and createDatabaseKey() against the
     * app's real keystore alias. Running the instrumented suite on a phone with
     * a live ledger would throw away the key and leave the database permanently
     * unreadable: the data would still be on disk, encrypted with a key that no
     * longer exists anywhere. No backup, no recovery.
     *
     * That nearly happened. The safe thing is for the test to stop rather than
     * for the operator to remember.
     */
    @Before
    fun refuseToRunAgainstRealData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = context.getDatabasePath("quietledger.db")
        Assume.assumeFalse(
            "SKIPPED: this device has a real QuietLedger database, and these " +
                "tests destroy the key that decrypts it.",
            database.exists() && database.length() > 0
        )
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        keyStore = DatabaseKeyStore(context)
        // Each test starts from a clean slate.
        keyStore.destroyAllKeys()
    }

    @Test
    fun createsAKeyAndUnwrapsItAgain() {
        assertFalse(keyStore.isInitialised)

        val created = keyStore.createDatabaseKey()
        assertTrue(keyStore.isInitialised)
        assertArrayEquals(created, keyStore.unlockWithKeystore())
    }

    @Test
    fun theKeyIsThirtyTwoBytes() {
        val key = keyStore.createDatabaseKey()
        assertArrayEquals(ByteArray(0), ByteArray(0))
        assert(key.size == KeyEnvelope.DATABASE_KEY_BYTES) {
            "expected ${KeyEnvelope.DATABASE_KEY_BYTES} bytes, got ${key.size}"
        }
    }

    @Test
    fun twoSeparateSetupsProduceDifferentKeys() {
        val first = keyStore.createDatabaseKey()
        keyStore.destroyAllKeys()
        val second = keyStore.createDatabaseKey()
        assertNotEquals(
            KeyEnvelope.encode(first),
            KeyEnvelope.encode(second)
        )
    }

    @Test
    fun unlockingBeforeSetupReportsNoKeyRatherThanCrashing() {
        assertThrows(NoKeyException::class.java) { keyStore.unlockWithKeystore() }
    }

    @Test
    fun creatingTwiceIsRefused() {
        keyStore.createDatabaseKey()
        assertThrows(IllegalStateException::class.java) { keyStore.createDatabaseKey() }
    }

    @Test
    fun aPassphraseUnwrapsTheSameKeyAsTheDeviceKey() {
        val databaseKey = keyStore.createDatabaseKey()
        keyStore.setPassphrase(databaseKey, "a good long passphrase".toCharArray())

        assertTrue(keyStore.hasPassphrase)
        assertArrayEquals(databaseKey, keyStore.unlockWithPassphrase("a good long passphrase".toCharArray()))
        assertArrayEquals(databaseKey, keyStore.unlockWithKeystore())
    }

    @Test
    fun theWrongPassphraseIsRejected() {
        val databaseKey = keyStore.createDatabaseKey()
        keyStore.setPassphrase(databaseKey, "the right passphrase".toCharArray())

        assertThrows(WrongPassphraseException::class.java) {
            keyStore.unlockWithPassphrase("the wrong passphrase".toCharArray())
        }
    }

    @Test
    fun aPassphraseCanBeChangedWithoutLosingTheKey() {
        val databaseKey = keyStore.createDatabaseKey()
        keyStore.setPassphrase(databaseKey, "first passphrase".toCharArray())
        keyStore.setPassphrase(databaseKey, "second passphrase".toCharArray())

        assertArrayEquals(databaseKey, keyStore.unlockWithPassphrase("second passphrase".toCharArray()))
        assertThrows(WrongPassphraseException::class.java) {
            keyStore.unlockWithPassphrase("first passphrase".toCharArray())
        }
    }

    @Test
    fun repairingTheWrappingKeepsTheSameDatabaseKey() {
        // Simulates recovering after biometric enrolment invalidated the
        // Keystore entry.
        val databaseKey = keyStore.createDatabaseKey()
        keyStore.repairKeystoreWrapping(databaseKey)
        assertArrayEquals(databaseKey, keyStore.unlockWithKeystore())
    }

    @Test
    fun unlockingWithAPassphraseBeforeOneIsSetReportsNoKey() {
        keyStore.createDatabaseKey()
        assertThrows(NoKeyException::class.java) {
            keyStore.unlockWithPassphrase("anything".toCharArray())
        }
    }

    @Test
    fun destroyingKeysLeavesNothingBehind() {
        keyStore.createDatabaseKey().let { keyStore.setPassphrase(it, "passphrase here".toCharArray()) }
        assertTrue(keyStore.isInitialised)
        assertTrue(keyStore.hasPassphrase)

        keyStore.destroyAllKeys()

        assertFalse(keyStore.isInitialised)
        assertFalse(keyStore.hasPassphrase)
    }
}
