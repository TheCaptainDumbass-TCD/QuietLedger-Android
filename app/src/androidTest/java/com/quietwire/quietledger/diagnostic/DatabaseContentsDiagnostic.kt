package com.quietwire.quietledger.diagnostic

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quietwire.quietledger.core.crypto.DatabaseKeyStore
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reports what is actually in the real encrypted database on this device.
 *
 * **Strictly read only.** It opens the live database, runs COUNT queries and
 * logs them. It never writes, never migrates and never touches the keystore
 * beyond unwrapping the existing key.
 *
 * This exists because the JVM unit tests substitute plain in-memory Room for
 * SQLCipher, Hilt and the migrations, so a suite of them can pass while the
 * installed app shows nothing. Reading the real file is the only way to tell
 * whether an import wrote anything.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseContentsDiagnostic {

    private val tag = "QLDiag"

    @Test
    fun reportContents() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val keyStore = DatabaseKeyStore(context)

        System.loadLibrary("sqlcipher")

        val key = try {
            keyStore.unlockWithKeystore()
        } catch (t: Throwable) {
            Log.e(tag, "COULD NOT UNWRAP KEY: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        val path = context.getDatabasePath("quietledger.db").absolutePath
        Log.i(tag, "database path: $path")
        Log.i(tag, "exists: ${java.io.File(path).exists()}  size: ${java.io.File(path).length()}")

        val db = SQLiteDatabase.openDatabase(path, key, null, SQLiteDatabase.OPEN_READONLY, null)
        try {
            db.rawQuery("PRAGMA user_version", null).use { c ->
                if (c.moveToFirst()) Log.i(tag, "schema user_version = ${c.getInt(0)}")
            }

            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null
            ).use { c ->
                val tables = mutableListOf<String>()
                while (c.moveToNext()) tables.add(c.getString(0))
                Log.i(tag, "tables: ${tables.joinToString(", ")}")

                for (table in tables) {
                    if (table.startsWith("sqlite_") || table.startsWith("android_")) continue
                    runCatching {
                        db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { rows ->
                            if (rows.moveToFirst()) {
                                Log.i(tag, "ROWCOUNT $table = ${rows.getInt(0)}")
                            }
                        }
                    }.onFailure { Log.e(tag, "count failed for $table: ${it.message}") }
                }
            }

            // Duplicates are what an accumulating import looks like, so they
            // are worth naming rather than inferring from a row count.
            runCatching {
                db.rawQuery(
                    "SELECT account_name, symbol, COUNT(*) c FROM holding " +
                        "GROUP BY account_name, symbol ORDER BY c DESC, symbol", null
                ).use { c ->
                    while (c.moveToNext()) {
                        Log.i(tag, "HOLDING ${c.getString(0)} | ${c.getString(1)} x${c.getInt(2)}")
                    }
                }
            }.onFailure { Log.e(tag, "holding breakdown failed: ${it.message}") }

            runCatching {
                db.rawQuery(
                    "SELECT metal, COUNT(*) n, " +
                        "SUM(CASE WHEN spot_price_minor IS NULL THEN 1 ELSE 0 END) nulls, " +
                        "MIN(spot_price_minor), MAX(spot_price_minor), " +
                        "MIN(unit_price_minor), MAX(unit_price_minor), " +
                        "MIN(unit_weight_micro_oz), MAX(unit_weight_micro_oz) " +
                        "FROM metal_lot GROUP BY metal", null
                ).use { c ->
                    while (c.moveToNext()) {
                        Log.i(
                            tag,
                            "METALSPOT ${c.getString(0)} n=${c.getInt(1)} nullSpot=${c.getInt(2)} " +
                                "spot=${c.getLong(3)}..${c.getLong(4)} " +
                                "unit=${c.getLong(5)}..${c.getLong(6)} " +
                                "wt=${c.getLong(7)}..${c.getLong(8)}"
                        )
                    }
                }
            }.onFailure { Log.e(tag, "metal spot breakdown failed: ${it.message}") }

            // The identity Room checks on open. A mismatch here is what makes
            // every screen unreadable at once.
            runCatching {
                db.rawQuery("SELECT identity_hash FROM room_master_table", null).use { c ->
                    if (c.moveToFirst()) Log.i(tag, "room identity_hash = ${c.getString(0)}")
                }
            }.onFailure { Log.e(tag, "no room_master_table: ${it.message}") }
        } finally {
            db.close()
            key.fill(0)
        }
    }
}
