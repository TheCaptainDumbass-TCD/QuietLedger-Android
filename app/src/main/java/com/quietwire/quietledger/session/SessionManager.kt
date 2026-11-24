package com.quietwire.quietledger.session

import android.util.Log
import com.quietwire.quietledger.BuildConfig
import com.quietwire.quietledger.core.crypto.DatabaseKeyStore
import com.quietwire.quietledger.core.crypto.KeystoreUnavailableException
import com.quietwire.quietledger.core.crypto.NoKeyException
import com.quietwire.quietledger.data.db.DatabaseProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SessionState {
    /** Working out whether this is a first run. */
    data object Starting : SessionState

    /** No key yet, so the user has to set the app up. */
    data object NeedsSetup : SessionState

    /** Key exists and the database is waiting to be unlocked. */
    data class Locked(val message: String? = null) : SessionState

    /** Database is open. */
    data object Unlocked : SessionState

    /** Something is wrong that the user cannot simply retry past. */
    data class Unrecoverable(val message: String) : SessionState
}

/**
 * Owns whether the database is open, and is the only thing that opens or
 * closes it.
 *
 * Keeping this in one place means locking is a single call, and nothing else
 * in the app has to reason about whether the key is available.
 */
@Singleton
class SessionManager @Inject constructor(
    private val keyStore: DatabaseKeyStore,
    private val databases: DatabaseProvider,
    private val io: CoroutineDispatcher
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Debug-only trace of session transitions.
     *
     * Whether the database is open decides whether any screen can show
     * anything, and that was previously invisible from outside.
     */
    private fun trace(what: String) {
        if (BuildConfig.DEBUG) {
            Log.i(
                "QLSession",
                "$what | state=${_state.value} dbOpen=${databases.isOpen} " +
                    "keyInitialised=${keyStore.isInitialised} hasPassphrase=${keyStore.hasPassphrase}"
            )
        }
    }

    fun refresh() {
        trace("refresh:before")
        _state.value = when {
            databases.isOpen -> SessionState.Unlocked
            keyStore.isInitialised -> SessionState.Locked()
            else -> SessionState.NeedsSetup
        }
        trace("refresh:after")
    }

    val hasPassphrase: Boolean get() = keyStore.hasPassphrase

    /**
     * Moves the wrapping into the device's security chip, once, quietly.
     *
     * Failure is not surfaced and not retried loudly: the app works exactly as
     * before if this does not succeed, so interrupting an unlock to report it
     * would be noise. It is logged, and the About screen shows where the key
     * actually ended up.
     */
    private fun moveKeyIntoSecurityChipIfPossible(databaseKey: ByteArray) {
        if (keyStore.usingStrongBox || !keyStore.strongBoxAvailable) return

        val moved = runCatching { keyStore.upgradeToStrongBox(databaseKey.copyOf()) }
            .getOrDefault(false)

        Log.i(
            "QLKeys",
            if (moved) {
                "wrapping key moved into the security chip"
            } else {
                "security chip upgrade not applied; the existing key still works"
            }
        )
    }

    /** First run. Creates the key, optionally adds a passphrase route. */
    suspend fun setUp(passphrase: CharArray?): Result<Unit> = withContext(io) {
        runCatching {
            // Tolerate a key left behind by a setup that got part way. Without
            // this, a single failure makes the app permanently unusable.
            val key = if (keyStore.isInitialised) {
                keyStore.unlockWithKeystore()
            } else {
                keyStore.createDatabaseKey()
            }

            passphrase?.let { keyStore.setPassphrase(key, it) }
            // open() zeroes the array it is given, so hand it a copy.
            databases.open(key.copyOf())
            key.fill(0)
            passphrase?.fill('\u0000')
            _state.value = SessionState.Unlocked
        }.onFailure { error ->
            passphrase?.fill('\u0000')
            // If setup failed before a key existed, the honest state is still
            // "needs setting up" so the user can simply try again. Sending
            // them to an Unlock button with nothing to unlock is a dead end.
            _state.value = if (keyStore.isInitialised) {
                SessionState.Locked(error.message)
            } else {
                SessionState.NeedsSetup
            }
        }
    }

    suspend fun unlockWithDeviceKey(): Result<Unit> = withContext(io) {
        runCatching {
            val key = keyStore.unlockWithKeystore()

            // Moved into the security chip the first time this runs on a device
            // that has one. Done here because it needs the unwrapped key, and
            // this is the only place that holds it.
            //
            // The copy matters: open() zeroes the array it is handed, and the
            // upgrade needs the same bytes. Order was reversed once and the
            // upgrade silently wrapped a zeroed key.
            moveKeyIntoSecurityChipIfPossible(key)

            databases.open(key)
            _state.value = SessionState.Unlocked
        }.onFailure { error ->
            _state.value = when {
                // Nothing to unlock, so go where the user can actually act.
                error is NoKeyException -> SessionState.NeedsSetup

                error is KeystoreUnavailableException && keyStore.hasPassphrase ->
                    SessionState.Locked(error.message)

                error is KeystoreUnavailableException ->
                    SessionState.Unrecoverable(error.message ?: "The device key is unavailable.")

                else -> SessionState.Locked(error.message)
            }
        }
    }

    suspend fun unlockWithPassphrase(passphrase: CharArray): Result<Unit> = withContext(io) {
        runCatching {
            val key = keyStore.unlockWithPassphrase(passphrase)
            databases.open(key.copyOf())

            // A passphrase unlock usually means the Keystore wrapping broke,
            // so quietly repair it and biometrics work again next time.
            runCatching { keyStore.repairKeystoreWrapping(key) }
            key.fill(0)
            passphrase.fill('\u0000')
            _state.value = SessionState.Unlocked
        }.onFailure {
            passphrase.fill('\u0000')
            _state.value = SessionState.Locked(it.message)
        }
    }

    /** Adds or replaces the passphrase while unlocked. */
    suspend fun setPassphrase(passphrase: CharArray): Result<Unit> = withContext(io) {
        runCatching {
            val key = keyStore.unlockWithKeystore()
            keyStore.setPassphrase(key, passphrase)
            key.fill(0)
            passphrase.fill('\u0000')
        }
    }

    fun lock() {
        databases.close()
        _state.value = SessionState.Locked()
    }
}
