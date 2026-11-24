package com.quietwire.quietledger.data.db

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import com.quietwire.quietledger.data.db.dao.BalanceChangeDao
import com.quietwire.quietledger.data.db.dao.HoldingDao
import com.quietwire.quietledger.data.db.dao.MetalLotDao
import com.quietwire.quietledger.data.db.dao.MetalSpotPriceDao
import com.quietwire.quietledger.data.db.dao.SavingsAccountDao
import com.quietwire.quietledger.data.db.dao.DebtDao
import com.quietwire.quietledger.data.db.dao.RecurringExpenseDao
import com.quietwire.quietledger.data.db.entity.BalanceChangeEntity
import com.quietwire.quietledger.data.db.entity.HoldingEntity
import com.quietwire.quietledger.data.db.entity.MetalLotEntity
import com.quietwire.quietledger.data.db.entity.MetalSpotPriceEntity
import com.quietwire.quietledger.data.db.entity.SavingsAccountEntity
import com.quietwire.quietledger.data.db.entity.DebtEntity
import com.quietwire.quietledger.data.db.entity.RecurringExpenseEntity

/**
 * Entities are added as their features land rather than all up front, so
 * there is no dead schema to migrate around later.
 *
 * The file is encrypted with SQLCipher. Room talks to it through the standard
 * SupportSQLite interfaces, so nothing above this layer knows or cares.
 */
@Database(
    entities = [
        DebtEntity::class,
        RecurringExpenseEntity::class,
        BalanceChangeEntity::class,
        SavingsAccountEntity::class,
        HoldingEntity::class,
        MetalLotEntity::class,
        MetalSpotPriceEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class QuietLedgerDatabase : RoomDatabase() {

    abstract fun debtDao(): DebtDao
    abstract fun recurringExpenseDao(): RecurringExpenseDao
    abstract fun balanceChangeDao(): BalanceChangeDao
    abstract fun savingsAccountDao(): SavingsAccountDao
    abstract fun holdingDao(): HoldingDao
    abstract fun metalLotDao(): MetalLotDao
    abstract fun metalSpotPriceDao(): MetalSpotPriceDao

    companion object {
        /** Kept beside the annotation so tests cannot assert a stale number. */
        const val VERSION = 8

        const val FILE_NAME = "quietledger.db"

        /**
         * Adds the promotional rate flag.
         *
         * Written rather than relying on a destructive fallback, because this
         * database holds the only copy of someone's financial records and
         * "schema changed, start again" is not an acceptable outcome.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            // The SupportSQLiteDatabase variant, because the database is opened
            // through SQLCipher's SupportSQLiteOpenHelper factory rather than a
            // Room driver.
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE debt ADD COLUMN apr_is_promotional INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Adds savings accounts, the first asset type.
         *
         * Net worth was previously always minus the total debt, because there
         * was nowhere to record anything owned.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `savings_account` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `institution` TEXT NOT NULL,
                        `current_balance_minor` INTEGER NOT NULL,
                        `apy_basis_points` INTEGER,
                        `purpose` TEXT NOT NULL,
                        `goal_minor` INTEGER,
                        `closed_at` INTEGER,
                        `notes` TEXT NOT NULL,
                        `sort_order` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Adds brokerage holdings, imported from a positions export.
         *
         * No unique index on symbol. The same symbol can legitimately appear
         * more than once across accounts, and an import replaces the whole set
         * anyway, so a constraint would only ever reject a valid file.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `holding` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `account_name` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `quantity_micros` INTEGER,
                        `last_price_minor` INTEGER,
                        `current_value_minor` INTEGER NOT NULL,
                        `cost_basis_minor` INTEGER,
                        `gain_loss_minor` INTEGER,
                        `is_cash` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `as_of` INTEGER NOT NULL,
                        `imported_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** Adds bullion purchase lots, parsed from vendor invoices. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `metal_lot` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `vendor` TEXT NOT NULL,
                        `order_number` TEXT NOT NULL,
                        `purchased_at` INTEGER NOT NULL,
                        `description` TEXT NOT NULL,
                        `metal` TEXT NOT NULL,
                        `quantity_milli` INTEGER NOT NULL,
                        `unit_weight_micro_oz` INTEGER NOT NULL,
                        `weight_label` TEXT NOT NULL,
                        `unit_price_minor` INTEGER NOT NULL,
                        `extended_price_minor` INTEGER NOT NULL,
                        `shipping_minor` INTEGER NOT NULL,
                        `spot_price_minor` INTEGER,
                        `source_file` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Adds user-entered spot prices, so metals can show a market value
         * alongside what was paid.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `metal_spot_price` (
                        `metal` TEXT PRIMARY KEY NOT NULL,
                        `price_per_oz_minor` INTEGER NOT NULL,
                        `entered_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Unhides every debt that was closed without being asked.
         *
         * Two things set `closed_at`: the old rule that closed any debt whose
         * balance was zero, and the desktop importer carrying across an
         * `is_paid` flag. Neither was a decision the user made, and there has
         * never been a button to close a debt, so nothing here can be the
         * record of a deliberate choice.
         *
         * A cleared card is still an account worth seeing. Removing an entry is
         * now explicit, via Delete, so hiding has no job left to do.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE debt SET closed_at = NULL WHERE closed_at IS NOT NULL")
            }
        }

        /**
         * Removes copper entirely.
         *
         * Dropped rather than left in place: `Metal.fromStored` no longer knows
         * it, so the rows would be silently skipped on read while still being
         * counted on the About screen and carried in every backup. A row that
         * exists but can never be displayed is worse than no row.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM metal_lot WHERE metal = 'COPPER'")
                db.execSQL("DELETE FROM metal_spot_price WHERE metal = 'COPPER'")
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
        )
    }
}
