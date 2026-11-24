package com.quietwire.quietledger.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.quietwire.quietledger.data.importer.FidelityPositionsParser
import com.quietwire.quietledger.data.repository.HoldingsRepository
import com.quietwire.quietledger.data.repository.MetalsRepository
import com.quietwire.quietledger.data.repository.SavingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Exercises the upgrade path a real install takes, rather than a fresh database.
 *
 * Every other test here builds a current-version database in memory, so the
 * migrations never actually ran. That is the one path a phone with existing
 * data is guaranteed to take, and a table created by a migration that disagrees
 * with its entity in any detail fails only there: the import succeeds, and then
 * the screen cannot read it back.
 *
 * [MigrationTestHelper] wants the exported schemas as Android assets, which AGP
 * does not merge for unit tests. So the old database is built from Room's own
 * committed schema JSON instead, which is the same source of truth without the
 * asset plumbing, and nothing here is hand-copied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationPathTest {

    private val schemaDir = "com.quietwire.quietledger.data.db.QuietLedgerDatabase"
    private lateinit var file: File

    @After
    fun tearDown() {
        if (::file.isInitialized) {
            file.delete()
            File("${file.path}-shm").delete()
            File("${file.path}-wal").delete()
        }
    }

    private fun schema(version: Int): JSONObject = JSONObject(
        checkNotNull(
            javaClass.classLoader?.getResourceAsStream("$schemaDir/$version.json")
        ) { "schema $version.json is not on the test classpath" }
            .bufferedReader().readText()
    ).getJSONObject("database")

    /**
     * Builds a database exactly as that version of the app would have left it,
     * including the identity hash Room checks on open.
     */
    private fun createAtVersion(version: Int, seed: (android.database.sqlite.SQLiteDatabase) -> Unit = {}) {
        val old = schema(version)
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)

        db.version = version
        val entities = old.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))

            // Indices as well as tables. Room's TableInfo validation covers
            // them, so a database missing its indices fails validation for
            // reasons that have nothing to do with the migration under test.
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(
                    indices.getJSONObject(j).getString("createSql")
                        .replace("\${TABLE_NAME}", table)
                )
            }
        }

        // Room refuses to open a database whose recorded identity does not match
        // the schema it expects for that version, so this has to be right.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table " +
                "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(old.getString("identityHash"))
        )

        seed(db)
        db.close()
    }

    private fun open(): QuietLedgerDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.databaseBuilder(context, QuietLedgerDatabase::class.java, file.path)
            .addMigrations(*QuietLedgerDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
    }

    private fun holder(database: QuietLedgerDatabase) = object : DatabaseHolder {
        override fun require() = database
    }

    private fun setUpFile(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, name)
        file.delete()
    }

    @Test
    fun `a version 2 database upgrades and keeps its data`() = runTest {
        setUpFile("upgrade-keeps-data.db")
        createAtVersion(2) { db ->
            db.execSQL(
                "INSERT INTO debt (name, category, current_balance_minor, " +
                    "minimum_payment_minor, scheduled_payment_minor, " +
                    "payment_frequency, apr_is_promotional, notes, sort_order, " +
                    "created_at, updated_at) VALUES " +
                    "('Existing card', 'CREDIT_CARD', 998597, 0, 12500, " +
                    "'WEEKLY', 0, '', 0, 1, 1)"
            )
        }

        val database = open()
        // Opening is what triggers Room's schema validation against every
        // migrated table, so reaching this line is itself the assertion.
        val debts = database.debtDao().getAll()
        assertEquals(1, debts.size)
        assertEquals("Existing card", debts.single().name)
        assertEquals(998597L, debts.single().currentBalanceMinor)
        database.close()
    }

    /**
     * The reported failure: an import reports success and the screen then shows
     * nothing.
     */
    @Test
    fun `holdings import and read back after upgrading from version 2`() = runTest {
        setUpFile("upgrade-holdings.db")
        createAtVersion(2)

        val database = open()
        val holdings = HoldingsRepository(holder(database))

        val csv = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "fidelity/positions.redacted.csv"
            )
        ).bufferedReader().readText()

        val result = holdings.applyImport(FidelityPositionsParser.parse(csv))
        assertTrue("the import found positions", result.total.isPositive)

        assertEquals(result.total, holdings.observeTotal().first())
        assertTrue(holdings.observeAll().first().isNotEmpty())
        database.close()
    }

    @Test
    fun `savings and metals are usable after upgrading from version 2`() = runTest {
        setUpFile("upgrade-assets.db")
        createAtVersion(2)

        val database = open()
        val savings = SavingsRepository(holder(database))
        val metals = MetalsRepository(holder(database))

        savings.add(
            com.quietwire.quietledger.domain.model.SavingsAccount(
                name = "Emergency",
                currentBalance = com.quietwire.quietledger.core.money.Money.parseOrNull("5000.00")!!
            )
        )

        assertEquals(1, savings.observeOpen().first().size)
        assertEquals(
            com.quietwire.quietledger.core.money.Money.parseOrNull("5000.00"),
            savings.observeTotal().first()
        )
        // Zero rather than a failure, which is the distinction that matters:
        // an empty table is not an unreadable one.
        assertEquals(
            com.quietwire.quietledger.core.money.Money.ZERO,
            metals.observeTotalCost().first()
        )
        database.close()
    }

    /**
     * Debts hidden by the old auto-close rule come back on upgrade.
     *
     * The fix stopped new ones being hidden; it could not unhide the rows
     * already written. Two of six debts were invisible on a real device.
     */
    @Test
    fun `upgrading unhides debts that were closed automatically`() = runTest {
        setUpFile("upgrade-unhide.db")
        createAtVersion(6) { db ->
            db.execSQL(
                "INSERT INTO debt (name, category, current_balance_minor, " +
                    "minimum_payment_minor, scheduled_payment_minor, " +
                    "payment_frequency, apr_is_promotional, notes, sort_order, " +
                    "created_at, updated_at, closed_at) VALUES " +
                    "('Fidelity Visa', 'CREDIT_CARD', 0, 0, 0, 'MONTHLY', 0, '', 0, 1, 1, 1700)"
            )
            db.execSQL(
                "INSERT INTO debt (name, category, current_balance_minor, " +
                    "minimum_payment_minor, scheduled_payment_minor, " +
                    "payment_frequency, apr_is_promotional, notes, sort_order, " +
                    "created_at, updated_at) VALUES " +
                    "('Northbridge CU', 'PERSONAL_LOAN', 998597, 0, 12500, 'WEEKLY', 0, '', 1, 1, 1)"
            )
        }

        val database = open()
        val visible = database.debtDao().getOpen().map { it.name }.sorted()
        assertEquals(
            "a debt closed by the old rule must be visible again",
            listOf("Fidelity Visa", "Northbridge CU"),
            visible
        )
        database.close()
    }

    @Test
    fun `upgrading removes copper entirely`() = runTest {
        setUpFile("upgrade-drop-copper.db")
        createAtVersion(7) { db ->
            db.execSQL(
                "INSERT INTO metal_spot_price (metal, price_per_oz_minor, entered_at) " +
                    "VALUES ('COPPER', 658, 1)"
            )
            db.execSQL(
                "INSERT INTO metal_spot_price (metal, price_per_oz_minor, entered_at) " +
                    "VALUES ('SILVER', 6190, 1)"
            )
            db.execSQL(
                "INSERT INTO metal_lot (vendor, order_number, purchased_at, description, " +
                    "metal, quantity_milli, unit_weight_micro_oz, weight_label, " +
                    "unit_price_minor, extended_price_minor, shipping_minor, " +
                    "spot_price_minor, source_file, notes, created_at) VALUES " +
                    "('SD Bullion','1',1,'1 oz Christmas Tree Copper Round','COPPER'," +
                    "5000,1000000,'1 oz',229,1145,270,10,'x','',1)"
            )
        }

        val database = open()

        // Dropped rather than orphaned. Metal.fromStored no longer knows
        // COPPER, so these rows would be invisible on screen while still being
        // counted on the About screen and carried into every backup.
        assertTrue(database.metalLotDao().getAll().none { it.metal == "COPPER" })
        val prices = database.metalSpotPriceDao().getAll().associate { it.metal to it.pricePerOzMinor }
        assertEquals(null, prices["COPPER"])
        assertEquals(6190L, prices["SILVER"])
        database.close()
    }

    @Test
    fun `every intermediate version upgrades to current`() = runTest {
        // A phone could be on any released version, so each starting point is
        // checked rather than only the oldest.
        for (from in 1 until QuietLedgerDatabase.VERSION) {
            setUpFile("upgrade-from-$from.db")
            createAtVersion(from)

            val database = open()
            database.savingsAccountDao().getAll()
            database.holdingDao().getAll()
            database.metalLotDao().getAll()
            database.debtDao().getAll()
            database.close()
        }
    }
}
