package com.quietwire.quietledger.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Singleton

/**
 * Writes a sealed backup into a folder you chose once.
 *
 * The folder is picked through the system picker and the permission is
 * persisted, so nothing is asked again. Put it inside a Syncthing share and the
 * file reaches the server without the app having any network access at all:
 * Syncthing does the moving and only ever sees a blob it cannot read.
 *
 * No passphrase, because the backup is sealed to the server's public key. The
 * phone cannot read what it writes, which is why the viewer reports the age and
 * contents of every backup rather than the phone claiming a success it has no
 * way to verify.
 */
@Suppress("ApplySharedPref")
@Singleton
class AutoBackup(
    private val context: Context,
    private val backups: BackupRepository,
    private val serverKey: ServerPublicKey
) {

    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether a folder has been chosen and is still reachable. */
    val configured: Boolean
        get() = folder() != null && serverKey.available

    /** Whether this build can seal at all. */
    val serverKeyAvailable: Boolean get() = serverKey.available

    val folderLabel: String
        get() = folder()?.name ?: "not set"

    val lastWrittenAt: Instant?
        get() = prefs.getLong(KEY_LAST_WRITTEN, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    val lastError: String? get() = prefs.getString(KEY_LAST_ERROR, null)

    /** Fingerprint of the data in the last successful backup. */
    private val lastFingerprint: String?
        get() = prefs.getString(KEY_LAST_FINGERPRINT, null)

    /**
     * Remembers the folder the picker returned.
     *
     * The permission has to be taken persistably or it dies with the process,
     * and then the next export would silently have nowhere to go.
     */
    fun setFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit(commit = true) {
            putString(KEY_FOLDER, uri.toString())
            remove(KEY_LAST_ERROR)
        }
    }

    fun clearFolder() {
        folder()?.uri?.let {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit(commit = true) { remove(KEY_FOLDER); remove(KEY_LAST_ERROR) }
    }

    /**
     * Writes a backup if anything has changed since the last one.
     *
     * Compares a fingerprint of the data rather than the clock. This used to be
     * a fifteen minute timer, which skipped a Fidelity import because it
     * happened ten minutes after the previous backup. A timer cannot tell a
     * dozen pointless app switches from a real change, and holding back a
     * genuine change is the worse failure of the two.
     *
     * Serialising on every trip to the background sounds wasteful and is not:
     * the whole ledger is a few kilobytes of JSON.
     */
    suspend fun writeIfChanged(now: Instant = Instant.now()): Boolean {
        if (!configured) {
            Log.i(TAG, "skipped: no folder configured")
            return false
        }

        val snapshot = try {
            backups.snapshot()
        } catch (t: Throwable) {
            return fail("Could not read the ledger: ${t.javaClass.simpleName}")
        }

        if (snapshot.fingerprint == lastFingerprint) {
            Log.i(TAG, "skipped: nothing has changed since the last backup")
            return false
        }

        return write(now, snapshot)
    }

    /** Writes regardless of whether anything changed, for an explicit request. */
    suspend fun write(
        now: Instant = Instant.now(),
        snapshot: BackupRepository.Snapshot? = null
    ): Boolean {
        val target = folder() ?: return fail("No backup folder is set.")

        val content = snapshot ?: try {
            backups.snapshot()
        } catch (t: Throwable) {
            return fail("Could not read the ledger: ${t.javaClass.simpleName}")
        }

        val name = "quietledger-" +
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(now) +
            ".qlbk"

        return try {
            val file = target.createFile(MIME, name)
                ?: return fail("Could not create a file in ${target.name}.")

            context.contentResolver.openOutputStream(file.uri)?.use {
                backups.seal(it, serverKey, content.json)
            } ?: return fail("Could not open ${file.name} for writing.")

            // Checked rather than assumed. A zero length file would otherwise
            // count as a successful backup.
            val written = DocumentFile.fromSingleUri(context, file.uri)?.length() ?: 0
            if (written <= 0) {
                file.delete()
                return fail("The backup came out empty, so it was removed.")
            }

            prefs.edit(commit = true) {
                putLong(KEY_LAST_WRITTEN, now.toEpochMilli())
                // Recorded only after the file is proven non-empty, so a failed
                // write cannot make the next change look unchanged.
                putString(KEY_LAST_FINGERPRINT, content.fingerprint)
                remove(KEY_LAST_ERROR)
            }
            Log.i(TAG, "sealed backup written: $name, $written bytes")

            prune(target)
            true
        } catch (t: Throwable) {
            fail("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Keeps the newest few and deletes the rest.
     *
     * Without this a background export every few minutes fills the folder, and
     * Syncthing would carry every copy. The server only ever reads the newest.
     */
    private fun prune(folder: DocumentFile, keep: Int = KEEP) {
        runCatching {
            folder.listFiles()
                .filter { it.isFile && it.name?.startsWith("quietledger-") == true }
                .sortedByDescending { it.lastModified() }
                .drop(keep)
                .forEach { it.delete() }
        }
    }

    private fun fail(message: String): Boolean {
        Log.w(TAG, "auto backup failed: $message")
        // Recorded so the Security screen can say what went wrong. The phone
        // cannot read its own sealed backups, so a silent failure here would be
        // invisible from this end entirely.
        prefs.edit(commit = true) { putString(KEY_LAST_ERROR, message) }
        return false
    }

    private fun folder(): DocumentFile? {
        val stored = prefs.getString(KEY_FOLDER, null) ?: return null
        val uri = runCatching { stored.toUri() }.getOrNull() ?: return null

        // The permission can be revoked, and the folder can be deleted or an SD
        // card removed. Checked every time rather than trusted.
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!held) return null

        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canWrite() }
    }

    companion object {
        private const val TAG = "QLAutoBackup"
        private const val PREFS_NAME = "quietledger_autobackup"
        private const val KEY_FOLDER = "folder_uri"
        private const val KEY_LAST_WRITTEN = "last_written_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_FINGERPRINT = "last_fingerprint"

        private const val MIME = "application/octet-stream"

        /** Enough to fall back if the newest turns out to be bad. */
        const val KEEP = 5

        fun documentsContractAvailable(): Boolean = DocumentsContract.EXTRA_INFO != null
    }
}
