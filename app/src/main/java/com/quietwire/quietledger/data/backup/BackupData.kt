package com.quietwire.quietledger.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shape of an export.
 *
 * Amounts are minor units, so nothing is ever a floating point number, not even
 * in transit. Timestamps are epoch milliseconds.
 *
 * Every field has a default so that an export written by an older version still
 * parses here. New fields can be added without invalidating old files, which is
 * the point of exporting JSON rather than a copy of the database.
 */
@Serializable
data class BackupFile(
    @SerialName("schema") val schema: Int = SCHEMA_VERSION,
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("exported_at") val exportedAt: Long = 0,
    @SerialName("debts") val debts: List<BackupDebt> = emptyList(),
    @SerialName("recurring_expenses") val recurringExpenses: List<BackupRecurringExpense> = emptyList(),
    @SerialName("balance_changes") val balanceChanges: List<BackupBalanceChange> = emptyList(),
    @SerialName("savings_accounts") val savingsAccounts: List<BackupSavingsAccount> = emptyList(),
    @SerialName("metal_lots") val metalLots: List<BackupMetalLot> = emptyList(),
    @SerialName("metal_spot_prices")
    val metalSpotPrices: List<BackupMetalSpotPrice> = emptyList(),
    /**
     * Retirement positions.
     *
     * Added late, and its absence was a real defect: a backup did not contain
     * them, so a restore silently dropped the whole IRA. They are re-importable
     * from a broker CSV, but "you can get it again from elsewhere" is not what
     * a backup is for.
     */
    val holdings: List<BackupHolding> = emptyList(),
    /**
     * The payoff projection, as the phone computed it.
     *
     * Carried rather than recomputed elsewhere, deliberately. The projection is
     * 382 lines of tested logic, and a second implementation on the server
     * would be two engines that can disagree about a debt-free date. That is
     * not hypothetical: two implementations of a metal weight disagreed by
     * $5.66 on the same data, and only a side-by-side comparison found it.
     *
     * So the phone is the only thing that projects, and anything else displays.
     * Null in a backup written before this existed, and the viewer says the
     * projection is unavailable rather than inventing one.
     */
    @SerialName("payoff") val payoff: BackupPayoff? = null
) {
    val totalRecords: Int
        get() = debts.size + recurringExpenses.size + balanceChanges.size +
            savingsAccounts.size + metalLots.size + metalSpotPrices.size + holdings.size

    companion object {
        /**
         * Bumped only for a change old readers could not cope with. Adding a
         * field does not need a bump, because every field has a default.
         */
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class BackupDebt(
    val id: Long = 0,
    val name: String = "",
    val category: String = "OTHER",
    @SerialName("current_balance_minor") val currentBalanceMinor: Long = 0,
    @SerialName("original_balance_minor") val originalBalanceMinor: Long? = null,
    @SerialName("minimum_payment_minor") val minimumPaymentMinor: Long = 0,
    @SerialName("scheduled_payment_minor") val scheduledPaymentMinor: Long = 0,
    @SerialName("payment_frequency") val paymentFrequency: String = "MONTHLY",
    @SerialName("apr_basis_points") val aprBasisPoints: Int? = null,
    @SerialName("apr_is_promotional") val aprIsPromotional: Boolean = false,
    @SerialName("due_day_of_month") val dueDayOfMonth: Int? = null,
    @SerialName("closed_at") val closedAt: Long? = null,
    val notes: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0
)

@Serializable
data class BackupRecurringExpense(
    val id: Long = 0,
    val name: String = "",
    val category: String = "OTHER",
    @SerialName("amount_minor") val amountMinor: Long = 0,
    val frequency: String = "MONTHLY",
    @SerialName("due_day_of_month") val dueDayOfMonth: Int? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("ended_at") val endedAt: Long? = null,
    val notes: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0
)

@Serializable
data class BackupBalanceChange(
    val id: Long = 0,
    @SerialName("subject_type") val subjectType: String = "DEBT",
    @SerialName("subject_id") val subjectId: Long = 0,
    @SerialName("field") val fieldName: String = "",
    @SerialName("previous_minor") val previousMinor: Long? = null,
    @SerialName("new_minor") val newMinor: Long = 0,
    @SerialName("delta_minor") val deltaMinor: Long = 0,
    val source: String = "MANUAL",
    @SerialName("subject_name") val subjectName: String = "",
    val note: String? = null,
    @SerialName("occurred_at") val occurredAt: Long = 0
)

@Serializable
data class BackupSavingsAccount(
    val id: Long = 0,
    val name: String = "",
    val institution: String = "",
    @SerialName("current_balance_minor") val currentBalanceMinor: Long = 0,
    @SerialName("apy_basis_points") val apyBasisPoints: Int? = null,
    val purpose: String = "GENERAL",
    @SerialName("goal_minor") val goalMinor: Long? = null,
    @SerialName("closed_at") val closedAt: Long? = null,
    val notes: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0
)

@Serializable
data class BackupMetalLot(
    val id: Long = 0,
    val vendor: String = "",
    @SerialName("order_number") val orderNumber: String = "",
    @SerialName("purchased_at") val purchasedAt: Long = 0,
    val description: String = "",
    val metal: String = "",
    @SerialName("quantity_milli") val quantityMilli: Long = 0,
    @SerialName("unit_weight_micro_oz") val unitWeightMicroOz: Long = 0,
    @SerialName("weight_label") val weightLabel: String = "",
    @SerialName("unit_price_minor") val unitPriceMinor: Long = 0,
    @SerialName("extended_price_minor") val extendedPriceMinor: Long = 0,
    @SerialName("shipping_minor") val shippingMinor: Long = 0,
    @SerialName("spot_price_minor") val spotPriceMinor: Long? = null,
    @SerialName("source_file") val sourceFile: String = "",
    val notes: String = "",
    @SerialName("created_at") val createdAt: Long = 0
)

@Serializable
data class BackupMetalSpotPrice(
    val metal: String = "",
    @SerialName("price_per_oz_minor") val pricePerOzMinor: Long = 0,
    @SerialName("entered_at") val enteredAt: Long = 0
)

@Serializable
data class BackupHolding(
    val id: Long = 0,
    @SerialName("account_name") val accountName: String = "",
    val symbol: String = "",
    val description: String = "",
    @SerialName("quantity_micros") val quantityMicros: Long? = null,
    @SerialName("last_price_minor") val lastPriceMinor: Long? = null,
    @SerialName("current_value_minor") val currentValueMinor: Long = 0,
    @SerialName("cost_basis_minor") val costBasisMinor: Long? = null,
    @SerialName("gain_loss_minor") val gainLossMinor: Long? = null,
    @SerialName("is_cash") val isCash: Boolean = false,
    val type: String = "",
    @SerialName("as_of") val asOf: Long = 0,
    @SerialName("imported_at") val importedAt: Long = 0
)

/**
 * A payoff projection, both ways round.
 *
 * Both scenarios are exported so the viewer can offer the rollover toggle
 * without doing any arithmetic of its own.
 */
@Serializable
data class BackupPayoff(
    @SerialName("computed_at") val computedAt: Long = 0,
    @SerialName("with_rollover") val withRollover: BackupPayoffPlan,
    @SerialName("without_rollover") val withoutRollover: BackupPayoffPlan
)

@Serializable
data class BackupPayoffPlan(
    /** Null when something never clears, which is a real answer. */
    @SerialName("months_to_debt_free") val monthsToDebtFree: Int? = null,
    /** ISO date, so the server does not have to agree about month arithmetic. */
    @SerialName("debt_free_date") val debtFreeDate: String? = null,
    @SerialName("clears_completely") val clearsCompletely: Boolean = false,
    @SerialName("is_debt_free") val isDebtFree: Boolean = false,
    @SerialName("total_paid_minor") val totalPaidMinor: Long = 0,
    @SerialName("total_interest_minor") val totalInterestMinor: Long = 0,
    @SerialName("starting_monthly_outlay_minor") val startingMonthlyOutlayMinor: Long = 0,
    @SerialName("debts") val debts: List<BackupPayoffDebt> = emptyList()
)

@Serializable
data class BackupPayoffDebt(
    @SerialName("name") val name: String = "",
    @SerialName("balance_minor") val balanceMinor: Long = 0,
    /** 1 is the next to clear. Null when it never does. */
    @SerialName("payoff_order") val payoffOrder: Int? = null,
    @SerialName("months_to_payoff") val monthsToPayoff: Int? = null,
    @SerialName("payoff_date") val payoffDate: String? = null,
    @SerialName("total_paid_minor") val totalPaidMinor: Long = 0,
    @SerialName("interest_paid_minor") val interestPaidMinor: Long = 0,
    @SerialName("months_saved_by_rollover") val monthsSavedByRollover: Int? = null,
    /** Why it never clears, in the phone's own words. */
    @SerialName("unpayable_reason") val unpayableReason: String? = null
)
