package com.quietwire.quietledger.domain.model

import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.model.ChangeSource
import com.quietwire.quietledger.data.model.SubjectType
import java.time.Instant

/** One recorded movement of an amount, read back from the change journal. */
data class BalanceChange(
    val id: Long,
    val subjectId: Long,
    /** What kind of thing moved. A debt and a savings account are opposites. */
    val subjectType: SubjectType,
    val subjectName: String,
    /**
     * Which amount moved. Deliberately not called `field`: inside a property
     * accessor Kotlin reserves that identifier for the backing field, so a
     * property of that name silently resolves to the wrong thing and the
     * compiler reports an unrelated "property must be initialized" error.
     */
    val fieldName: String,
    val previous: Money?,
    val current: Money,
    val delta: Money,
    val source: ChangeSource,
    val note: String?,
    val occurredAt: Instant
) {
    val isDecrease: Boolean get() = delta.isNegative

    /**
     * Whether this change is good news, which depends on what moved.
     *
     * A debt falling is progress; a savings balance falling is not. The
     * activity feed coloured every decrease green, so a withdrawal from
     * savings read as though it were a payment made.
     */
    val isProgress: Boolean
        get() = when (subjectType) {
            SubjectType.DEBT -> isDecrease
            else -> !isDecrease
        }

    /** Reads as a sentence in the history list. */
    val description: String
        get() = when {
            source == ChangeSource.INITIAL -> "Opened at ${current.format()}"
            source == ChangeSource.PAYMENT -> "Paid ${delta.absoluteValue.format()}"
            fieldName == FIELD_BALANCE && isDecrease -> "Balance down ${delta.absoluteValue.format()}"
            fieldName == FIELD_BALANCE -> "Balance up ${delta.absoluteValue.format()}"
            else -> "${fieldLabel()} changed to ${current.format()}"
        }

    private fun fieldLabel(): String = when (fieldName) {
        FIELD_SCHEDULED_PAYMENT -> "Payment"
        FIELD_MINIMUM_PAYMENT -> "Minimum payment"
        else -> fieldName.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    companion object {
        const val FIELD_BALANCE = "balance"
        const val FIELD_SCHEDULED_PAYMENT = "scheduled_payment"
        const val FIELD_MINIMUM_PAYMENT = "minimum_payment"
    }
}
