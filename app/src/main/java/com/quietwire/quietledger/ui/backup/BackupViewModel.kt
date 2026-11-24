package com.quietwire.quietledger.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.data.backup.BackupEnvelope
import com.quietwire.quietledger.data.backup.BackupRepository
import com.quietwire.quietledger.data.backup.ImportSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which dialog, if any, the backup flow is showing. */
sealed interface BackupDialog {
    data object None : BackupDialog
    /** Collecting a passphrase to encrypt with. */
    data object ExportPassphrase : BackupDialog
    /** Collecting a passphrase to decrypt the chosen file. */
    data class ImportPassphrase(val uri: Uri) : BackupDialog
    /** Describing what is in the file before anything is overwritten. */
    data class ConfirmImport(val uri: Uri, val passphrase: String, val summary: ImportSummary) : BackupDialog
}

data class BackupUiState(
    val dialog: BackupDialog = BackupDialog.None,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: BackupRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(BackupUiState())
    val ui: StateFlow<BackupUiState> = _ui.asStateFlow()

    fun suggestedFileName(): String = backups.suggestedFileName()

    fun startExport() {
        _ui.value = BackupUiState(dialog = BackupDialog.ExportPassphrase)
    }

    fun startImport(uri: Uri) {
        _ui.value = BackupUiState(dialog = BackupDialog.ImportPassphrase(uri))
    }

    fun dismiss() {
        _ui.value = BackupUiState()
    }

    /** Called once the user has picked where to write. */
    fun writeExport(uri: Uri, passphrase: String) {
        _ui.value = _ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?.use { backups.export(it, passphrase.toCharArray()) }
                    ?: error("That location could not be opened for writing.")
            }.onSuccess {
                _ui.value = BackupUiState(
                    message = "Backup saved. Keep the passphrase safe; without it the file " +
                        "cannot be read, by you or anyone else."
                )
            }.onFailure {
                _ui.value = BackupUiState(error = it.message ?: "The backup could not be written.")
            }
        }
    }

    /** Reads the file and reports what it holds, without writing anything yet. */
    fun inspectImport(uri: Uri, passphrase: String) {
        _ui.value = _ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.use { backups.inspect(it, passphrase.toCharArray()) }
                    ?: error("That file could not be opened.")
            }.onSuccess { summary ->
                _ui.value = BackupUiState(
                    dialog = BackupDialog.ConfirmImport(uri, passphrase, summary)
                )
            }.onFailure {
                _ui.value = BackupUiState(
                    dialog = BackupDialog.ImportPassphrase(uri),
                    error = it.message ?: "That file could not be read."
                )
            }
        }
    }

    /** Only reached after the user has seen what the file contains. */
    fun applyImport(uri: Uri, passphrase: String) {
        _ui.value = _ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.use { backups.import(it, passphrase.toCharArray()) }
                    ?: error("That file could not be opened.")
            }.onSuccess { summary ->
                _ui.value = BackupUiState(
                    message = "Restored ${summary.debts} debts and " +
                        "${summary.balanceChanges} history entries."
                )
            }.onFailure {
                _ui.value = BackupUiState(error = it.message ?: "The import failed.")
            }
        }
    }

    companion object {
        const val MIN_PASSPHRASE = BackupEnvelope.MIN_PASSPHRASE_LENGTH
        /**
         * Our own files have no registered MIME type, so the picker has to
         * accept anything. Validation happens on the magic bytes instead.
         */
        const val PICKER_MIME = "*/*"
        const val EXPORT_MIME = "application/octet-stream"
    }
}
