package com.quietwire.quietledger.diagnostic

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quietwire.quietledger.BuildConfig
import com.quietwire.quietledger.core.crypto.DatabaseKeyStore
import com.quietwire.quietledger.data.backup.BackupRepository
import com.quietwire.quietledger.data.db.DatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Writes an encrypted backup to a file the host can pull over adb.
 *
 * Why this exists: the viewer on the home server needs the current ledger, and
 * the only portable form of it is a backup. Producing one by hand means opening
 * the app, tapping through a file picker and moving the file, every time. This
 * lets the server ask for one.
 *
 * Read only with respect to the ledger. It opens the database, serialises it and
 * encrypts it, and changes nothing.
 *
 * The passphrase is supplied as an instrumentation argument rather than being
 * hardcoded, so it does not live in the repository:
 *
 *     adb shell am instrument -w \
 *       -e class com.quietwire.quietledger.diagnostic.ExportBackupDiagnostic \
 *       -e passphrase 'the passphrase' \
 *       com.quietwire.quietledger.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The file is written to the app's own external files directory, which is
 * readable over adb without granting any permission, and is removed by
 * `scripts/pull-from-phone.sh` once it has been collected.
 *
 * Debug builds only, guarded below. An instrumented test cannot run against a
 * release build anyway, but the guard states the intent.
 */
@RunWith(AndroidJUnit4::class)
class ExportBackupDiagnostic {

    private val tag = "QLExport"

    @Test
    fun exportForHost() {
        check(BuildConfig.DEBUG) { "Refusing to export from a non-debug build" }

        val arguments = InstrumentationRegistry.getArguments()
        val passphrase = arguments.getString("passphrase")
        if (passphrase.isNullOrBlank()) {
            Log.e(tag, "FAILED no passphrase given; pass -e passphrase '...'")
            return
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val keyStore = DatabaseKeyStore(context)
        val provider = DatabaseProvider(context, keyStore)

        val key = try {
            keyStore.unlockWithKeystore()
        } catch (t: Throwable) {
            Log.e(tag, "FAILED key unwrap: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        try {
            provider.open(key)
        } catch (t: Throwable) {
            Log.e(tag, "FAILED open: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        try {
            val target = File(
                context.getExternalFilesDir(null),
                "quietledger-export.qlbk"
            )

            runBlocking {
                // DatabaseProvider is itself a DatabaseHolder, so it is handed
                // over directly rather than wrapped.
                BackupRepository(
                    databases = provider,
                    appVersion = BuildConfig.VERSION_NAME
                ).export(target.outputStream(), passphrase.toCharArray())
            }

            // The host reads these two lines to know where to pull from and to
            // confirm it got the whole file.
            Log.i(tag, "OK path=${target.absolutePath}")
            Log.i(tag, "OK bytes=${target.length()}")
        } catch (t: Throwable) {
            Log.e(tag, "FAILED export: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            // Closed so the app's own session is not left holding this handle.
            runCatching { provider.close() }
        }
    }
}
