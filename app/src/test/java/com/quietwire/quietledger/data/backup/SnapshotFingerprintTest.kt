package com.quietwire.quietledger.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * The fingerprint that decides whether a backup is needed.
 *
 * A fifteen minute timer used to make that decision, and it skipped a Fidelity
 * import because the import happened ten minutes after the previous backup. A
 * clock cannot tell a dozen pointless app switches from a real change, and
 * holding back a real change is the worse of the two failures.
 *
 * Tested against the serialisation directly rather than through the database,
 * because what matters is that the export timestamp is excluded and everything
 * else is not.
 */
class SnapshotFingerprintTest {

    private val json = kotlinx.serialization.json.Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun fingerprint(file: BackupFile): String {
        val stable = json.encodeToString(BackupFile.serializer(), file.copy(exportedAt = 0))
        return MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun debt(name: String, balanceMinor: Long) = BackupDebt(
        id = 1,
        name = name,
        category = "CREDIT_CARD",
        currentBalanceMinor = balanceMinor,
        paymentFrequency = "MONTHLY"
    )

    @Test
    fun `the export time alone does not change the fingerprint`() {
        // Otherwise every snapshot differs and the comparison is worthless, so
        // a backup would be written on every trip to the background.
        val a = BackupFile(exportedAt = 1_000, debts = listOf(debt("Visa", 100_000)))
        val b = BackupFile(exportedAt = 9_999, debts = listOf(debt("Visa", 100_000)))

        assertEquals(fingerprint(a), fingerprint(b))
    }

    @Test
    fun `a changed balance changes the fingerprint`() {
        val before = BackupFile(debts = listOf(debt("Visa", 100_000)))
        val after = BackupFile(debts = listOf(debt("Visa", 99_000)))

        assertNotEquals(fingerprint(before), fingerprint(after))
    }

    @Test
    fun `an imported holding changes the fingerprint`() {
        // The reported case. A Fidelity import replaces the holdings, and that
        // has to count as a change.
        val before = BackupFile(debts = listOf(debt("Visa", 100_000)))
        val after = before.copy(
            holdings = listOf(
                BackupHolding(
                    id = 1,
                    accountName = "SEP-IRA",
                    symbol = "FXAIX",
                    currentValueMinor = 470_775
                )
            )
        )

        assertNotEquals(fingerprint(before), fingerprint(after))
    }

    @Test
    fun `a renamed debt changes the fingerprint`() {
        val before = BackupFile(debts = listOf(debt("Visa", 100_000)))
        val after = BackupFile(debts = listOf(debt("Fidelity Visa", 100_000)))

        assertNotEquals(fingerprint(before), fingerprint(after))
    }

    @Test
    fun `an added spot price changes the fingerprint`() {
        val before = BackupFile()
        val after = BackupFile(
            metalSpotPrices = listOf(
                BackupMetalSpotPrice(metal = "GOLD", pricePerOzMinor = 412_334)
            )
        )

        assertNotEquals(fingerprint(before), fingerprint(after))
    }

    @Test
    fun `identical data gives an identical fingerprint`() {
        // So leaving the app repeatedly without changing anything writes once.
        val a = BackupFile(debts = listOf(debt("Visa", 100_000)))
        val b = BackupFile(debts = listOf(debt("Visa", 100_000)))

        assertEquals(fingerprint(a), fingerprint(b))
    }
}
