package com.quietwire.quietledger.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.BuildConfig
import com.quietwire.quietledger.core.crypto.DatabaseKeyStore
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import com.quietwire.quietledger.ui.common.ReadFailureLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** A table and how many rows it holds, for an at-a-glance check. */
data class TableCount(val name: String, val rows: Int)

data class AboutUiState(
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val commit: String = BuildConfig.GIT_COMMIT,
    val commitState: String = BuildConfig.GIT_STATE,
    val buildTime: Instant = Instant.ofEpochMilli(BuildConfig.BUILD_TIME),
    /**
     * What the open database actually reports, not what the build believes.
     *
     * This was a `buildConfigField` literal and it was wrong twice: the
     * database reached version 8 while About kept saying 6. A screen that
     * exists to state what is running cannot carry a number someone has to
     * remember to update.
     *
     * Zero when the database is closed, since there is nothing to ask.
     */
    val schemaVersion: Int = 0,
    /** What this build was compiled expecting, for comparison. */
    val expectedSchemaVersion: Int = QuietLedgerDatabase.VERSION,
    val debug: Boolean = BuildConfig.DEBUG,
    /** Null while the counts are still being read. */
    val counts: List<TableCount>? = null,
    val databaseOpen: Boolean = false,
    val countError: String? = null,
    val recentFailures: List<String> = emptyList(),
    /**
     * Where the key that unlocks the database actually lives.
     *
     * Read from the key rather than from what was asked for. Some devices
     * accept a StrongBox request and quietly produce a trusted environment
     * key, so the request is not evidence of the result.
     */
    val keySecurityLevel: String = "unknown",
    val securityChipAvailable: Boolean = false
) {
    val totalRows: Int get() = counts?.sumOf { it.rows } ?: 0

    /**
     * The database is not the version this build expects.
     *
     * Means a migration did not run, which is worth seeing rather than
     * discovering through a column that is missing.
     */
    val schemaMismatch: Boolean
        get() = databaseOpen && schemaVersion != expectedSchemaVersion

    /** Built from uncommitted work, so the commit does not fully describe it. */
    val builtFromModifiedTree: Boolean get() = commitState == "modified"
}

/**
 * Reports what this build is and what it can currently see.
 *
 * Exists because of a long evening spent guessing. Which APK was installed had
 * to be established by hashing the file over adb, whether the data was present
 * needed a custom instrumented test, and why a screen was blank was recorded
 * nowhere at all. All three are things the app can simply state.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val databases: DatabaseHolder,
    private val keyStore: DatabaseKeyStore
) : ViewModel() {

    private val refresh = MutableStateFlow(0)

    val uiState: StateFlow<AboutUiState> = combine(
        databases.observe(),
        refresh
    ) { database, _ -> database }
        .map { database ->
            if (database == null) {
                return@map AboutUiState(
                    databaseOpen = false,
                    recentFailures = ReadFailureLog.recent()
                )
            }

            try {
                AboutUiState(
                    databaseOpen = true,
                    schemaVersion = database.openHelper.readableDatabase.version,
                    keySecurityLevel = keyStore.securityLevel(),
                    securityChipAvailable = keyStore.strongBoxAvailable,
                    counts = listOf(
                        TableCount("Debts", database.debtDao().getAll().size),
                        TableCount("Recurring expenses", database.recurringExpenseDao().getAllIncludingInactive().size),
                        TableCount("Savings accounts", database.savingsAccountDao().getAll().size),
                        TableCount("Retirement positions", database.holdingDao().getAll().size),
                        TableCount("Metal lots", database.metalLotDao().getAll().size),
                        TableCount("Metal spot prices", database.metalSpotPriceDao().getAll().size),
                        TableCount("Recorded changes", database.balanceChangeDao().getAll().size)
                    ),
                    recentFailures = ReadFailureLog.recent()
                )
            } catch (failure: Throwable) {
                // Reported rather than rendered as zeros. "Cannot count" and
                // "nothing to count" are the distinction this whole screen
                // exists to make visible.
                AboutUiState(
                    databaseOpen = true,
                    schemaVersion = database.openHelper.readableDatabase.version,
                    keySecurityLevel = keyStore.securityLevel(),
                    securityChipAvailable = keyStore.strongBoxAvailable,
                    countError = "${failure.javaClass.simpleName}: ${failure.message}",
                    recentFailures = ReadFailureLog.recent()
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AboutUiState())

    fun refresh() {
        viewModelScope.launch { refresh.value += 1 }
    }
}
