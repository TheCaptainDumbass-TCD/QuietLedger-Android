package com.quietwire.quietledger.domain.model

import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.model.ChangeSource
import com.quietwire.quietledger.data.model.SubjectType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Whether a movement is good news depends on what moved.
 *
 * The activity feed coloured every decrease green, so a withdrawal from
 * savings appeared as progress alongside a debt payment.
 */
class BalanceChangeProgressTest {

    private fun change(type: SubjectType, delta: String) = BalanceChange(
        id = 1,
        subjectId = 1,
        subjectType = type,
        subjectName = "Thing",
        fieldName = "balance",
        previous = Money.parseOrNull("100.00"),
        current = Money.parseOrNull("100.00")!!,
        delta = Money.parseOrNull(delta)!!,
        source = ChangeSource.MANUAL,
        note = null,
        occurredAt = Instant.EPOCH
    )

    @Test
    fun `a debt going down is progress`() {
        assertTrue(change(SubjectType.DEBT, "-500.00").isProgress)
    }

    @Test
    fun `a debt going up is not`() {
        assertFalse(change(SubjectType.DEBT, "250.00").isProgress)
    }

    @Test
    fun `savings going down is not progress`() {
        // The reported case: 360 Checking down $35 was shown in green.
        assertFalse(change(SubjectType.SAVINGS_ACCOUNT, "-35.00").isProgress)
    }

    @Test
    fun `savings going up is progress`() {
        assertTrue(change(SubjectType.SAVINGS_ACCOUNT, "250.00").isProgress)
    }
}
