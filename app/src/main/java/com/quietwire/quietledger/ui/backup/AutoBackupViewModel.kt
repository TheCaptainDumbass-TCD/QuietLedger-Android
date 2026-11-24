package com.quietwire.quietledger.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.data.backup.AutoBackup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class AutoBackupUiState(
    val configured: Boolean = false,
    val folderLabel: String = "not set",
    val lastWrittenAt: Instant? = null,
    val lastError: String? = null,
    val serverKeyAvailable: Boolean = false
)

@HiltViewModel
class AutoBackupViewModel @Inject constructor(
    private val autoBackup: AutoBackup
) : ViewModel() {

    private val _ui = MutableStateFlow(read())
    val ui: StateFlow<AutoBackupUiState> = _ui.asStateFlow()

    fun refresh() {
        _ui.value = read()
    }

    fun setFolder(uri: Uri) {
        autoBackup.setFolder(uri)
        // Written immediately, so choosing a folder proves it works rather
        // than leaving you to wonder until the next time you leave the app.
        writeNow()
    }

    fun clearFolder() {
        autoBackup.clearFolder()
        refresh()
    }

    fun writeNow() {
        viewModelScope.launch {
            autoBackup.write()
            refresh()
        }
    }

    private fun read() = AutoBackupUiState(
        configured = autoBackup.configured,
        folderLabel = autoBackup.folderLabel,
        lastWrittenAt = autoBackup.lastWrittenAt,
        lastError = autoBackup.lastError,
        serverKeyAvailable = autoBackup.serverKeyAvailable
    )
}
