package com.quietwire.quietledger.data.backup

import androidx.room.withTransaction
import com.quietwire.quietledger.data.mapper.toDomain
import com.quietwire.quietledger.domain.model.Debt
import com.quietwire.quietledger.domain.payoff.DebtProjection
import com.quietwire.quietledger.domain.payoff.PayoffCalculator
import com.quietwire.quietledger.domain.payoff.PayoffPlan
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.entity.BalanceChangeEntity
import com.quietwire.quietledger.data.db.entity.DebtEntity
import com.quietwire.quietledger.data.db.entity.RecurringExpenseEntity
import com.quietwire.quietledger.data.db.entity.HoldingEntity
import com.quietwire.quietledger.data.db.entity.MetalLotEntity
import com.quietwire.quietledger.data.db.entity.MetalSpotPriceEntity
import com.quietwire.quietledger.data.db.entity.SavingsAccountEntity
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class ImportSummary(
    val debts: Int,
    val recurringExpenses: Int,
    val balanceChanges: Int,
    val savingsAccounts: Int = 0,
    val metalLots: Int = 0,
    val holdings: Int = 0,
    val exportedAt: Instant?
) {
    val total: Int
        get() = debts + recurringExpenses + balanceChanges + savingsAccounts +
            metalLots + holdings
}

/**
 * Encrypted export and import.
 *
 * This is the only route data has off the device. Android's own backup is
 * deliberately disabled, so without this a new phone, or a reinstall forced by
 * a lost signing key, means starting over.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val databases: DatabaseHolder,
    private val appVersion: String
) {

    private val json = Json {
        prettyPrint = false
        // An export written by a newer build must still load here rather than
        // throwing on a field this version has never heard of.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Writes a backup only the server's private key can open.
     *
     * No passphrase, which is the point: a routine backup should cost nothing
     * to make. The trade is that the phone cannot read its own backups, so the
     * server reports their age and contents rather than the phone claiming
     * success it cannot verify.
     *
     * @throws IllegalStateException if no public key is bundled in this build.
     */
    suspend fun seal(output: OutputStream, serverKey: ServerPublicKey) {
        seal(output, serverKey, snapshot().json)
    }

    /** Seals an already-serialised snapshot, so it is not built twice. */
    fun seal(output: OutputStream, serverKey: ServerPublicKey, json: String) {
        val key = serverKey.key
        val id = serverKey.id
        checkNotNull(key) { "This build has no server public key, so it cannot seal." }
        checkNotNull(id)

        BackupEnvelope.seal(output, json, key, id)
    }

    suspend fun export(output: OutputStream, passphrase: CharArray) {
        try {
            BackupEnvelope.write(output, buildJson(), passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /**
     * The database serialised, with a fingerprint of its contents.
     *
     * The fingerprint ignores the export timestamp, so two snapshots of
     * unchanged data compare equal. That is what lets a backup be skipped
     * because nothing changed rather than because a timer has not elapsed.
     */
    data class Snapshot(val json: String, val fingerprint: String)

    suspend fun snapshot(): Snapshot {
        val file = buildFile()

        // Hashed with the timestamp zeroed. Including it would make every
        // fingerprint unique and the comparison worthless.
        val stable = json.encodeToString(file.copy(exportedAt = 0))
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(Charsets.UTF_8))

        return Snapshot(
            json = json.encodeToString(file),
            fingerprint = digest.joinToString("") { "%02x".format(it) }
        )
    }

    /**
     * Serialises everything in the database.
     *
     * Shared by both the passphrase and sealed paths on purpose. Two copies of
     * this would drift, and the symptom would be a sealed backup missing a
     * table that the passphrase one carries, discovered during a restore.
     */
    private suspend fun buildJson(): String = json.encodeToString(buildFile())

    private suspend fun buildFile(): BackupFile {
        val db = databases.require()
        val debtRows = db.debtDao().getAll()

        val file = BackupFile(
            payoff = project(debtRows.map { it.toDomain() }),
            appVersion = appVersion,
            exportedAt = Instant.now().toEpochMilli(),
            debts = debtRows.map { it.toBackup() },
            recurringExpenses = db.recurringExpenseDao().getAllIncludingInactive().map { it.toBackup() },
            balanceChanges = db.balanceChangeDao().getAll().map { it.toBackup() },
            savingsAccounts = db.savingsAccountDao().getAll().map { it.toBackup() },
            metalLots = db.metalLotDao().getAll().map { it.toBackup() },
            metalSpotPrices = db.metalSpotPriceDao().getAll().map { it.toBackup() },
            holdings = db.holdingDao().getAll().map { it.toBackup() }
        )

        return file
    }

    /**
     * Runs the projection so the backup carries it.
     *
     * The phone is the only thing that projects. A second implementation on the
     * server would be two engines able to disagree about a debt-free date, and
     * two implementations of a metal weight already disagreed by $5.66 on
     * identical data.
     *
     * Both scenarios are computed so a viewer can offer the rollover toggle
     * without doing arithmetic of its own.
     */
    private fun project(debts: List<Debt>): BackupPayoff? {
        // Null rather than an empty projection when there is nothing to pay
        // off. The viewer then says so instead of showing a plan for no debts.
        if (debts.none { it.isOpen && it.currentBalance.isPositive }) return null

        val calculator = PayoffCalculator()
        return BackupPayoff(
            computedAt = Instant.now().toEpochMilli(),
            withRollover = calculator
                .project(debts, rollOverClearedPayments = true)
                .toBackup(),
            withoutRollover = calculator
                .project(debts, rollOverClearedPayments = false)
                .toBackup()
        )
    }

    /**
     * Reads an export and replaces everything currently stored.
     *
     * All or nothing, in one transaction. A half-applied import would leave
     * balances that do not match their own history, which is worse than a
     * failed import.
     */
    suspend fun import(input: InputStream, passphrase: CharArray): ImportSummary {
        val payload = try {
            BackupEnvelope.read(input, passphrase)
        } finally {
            passphrase.fill('\u0000')
        }

        val file = json.decodeFromString<BackupFile>(payload)
        if (file.schema > BackupFile.SCHEMA_VERSION) {
            throw BackupFormatException(
                "This export needs a newer version of QuietLedger " +
                    "(data version ${file.schema}, this build reads ${BackupFile.SCHEMA_VERSION})."
            )
        }

        val db = databases.require()
        db.withTransaction {
            db.balanceChangeDao().deleteAll()
            db.debtDao().deleteAll()
            db.recurringExpenseDao().deleteAll()
            db.savingsAccountDao().deleteAll()
            db.metalLotDao().deleteAll()
            db.metalSpotPriceDao().deleteAll()

            file.debts.forEach { db.debtDao().insert(it.toEntity()) }
            file.recurringExpenses.forEach { db.recurringExpenseDao().insert(it.toEntity()) }
            file.savingsAccounts.forEach { db.savingsAccountDao().insert(it.toEntity()) }
            file.metalLots.forEach { db.metalLotDao().insert(it.toEntity()) }
            file.metalSpotPrices.forEach { db.metalSpotPriceDao().upsert(it.toEntity()) }
            // Replaced only when the file actually carries them.
            //
            // Retirement positions come from a broker CSV, not from the desktop
            // ledger, so a converted desktop file has no holdings section at
            // all. Wiping them for such a file would destroy data that the
            // backup never claimed to hold, and the IRA would vanish on a
            // restore that looked entirely successful.
            if (file.holdings.isNotEmpty()) {
                db.holdingDao().deleteAll()
                file.holdings.forEach { db.holdingDao().insert(it.toEntity()) }
            }
            file.balanceChanges.forEach { db.balanceChangeDao().insert(it.toEntity()) }
        }

        return ImportSummary(
            debts = file.debts.size,
            recurringExpenses = file.recurringExpenses.size,
            balanceChanges = file.balanceChanges.size,
            savingsAccounts = file.savingsAccounts.size,
            metalLots = file.metalLots.size,
            holdings = file.holdings.size,
            exportedAt = file.exportedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
        )
    }

    /** Reads the header only, to describe a file before overwriting anything. */
    suspend fun inspect(input: InputStream, passphrase: CharArray): ImportSummary {
        val payload = try {
            BackupEnvelope.read(input, passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
        val file = json.decodeFromString<BackupFile>(payload)
        return ImportSummary(
            debts = file.debts.size,
            recurringExpenses = file.recurringExpenses.size,
            balanceChanges = file.balanceChanges.size,
            savingsAccounts = file.savingsAccounts.size,
            metalLots = file.metalLots.size,
            holdings = file.holdings.size,
            exportedAt = file.exportedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
        )
    }

    fun suggestedFileName(): String {
        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
        return "quietledger-$stamp.${BackupEnvelope.FILE_EXTENSION}"
    }
}

private fun DebtEntity.toBackup() = BackupDebt(
    id = id, name = name, category = category,
    currentBalanceMinor = currentBalanceMinor, originalBalanceMinor = originalBalanceMinor,
    minimumPaymentMinor = minimumPaymentMinor, scheduledPaymentMinor = scheduledPaymentMinor,
    paymentFrequency = paymentFrequency, aprBasisPoints = aprBasisPoints,
    aprIsPromotional = aprIsPromotional,
    dueDayOfMonth = dueDayOfMonth, closedAt = closedAt, notes = notes,
    sortOrder = sortOrder, createdAt = createdAt, updatedAt = updatedAt
)

private fun BackupDebt.toEntity() = DebtEntity(
    id = id, name = name, category = category,
    currentBalanceMinor = currentBalanceMinor, originalBalanceMinor = originalBalanceMinor,
    minimumPaymentMinor = minimumPaymentMinor, scheduledPaymentMinor = scheduledPaymentMinor,
    paymentFrequency = paymentFrequency, aprBasisPoints = aprBasisPoints,
    aprIsPromotional = aprIsPromotional,
    dueDayOfMonth = dueDayOfMonth, closedAt = closedAt, notes = notes,
    sortOrder = sortOrder, createdAt = createdAt, updatedAt = updatedAt
)

private fun RecurringExpenseEntity.toBackup() = BackupRecurringExpense(
    id = id, name = name, category = category, amountMinor = amountMinor,
    frequency = frequency, dueDayOfMonth = dueDayOfMonth, isActive = isActive,
    endedAt = endedAt, notes = notes, sortOrder = sortOrder,
    createdAt = createdAt, updatedAt = updatedAt
)

private fun BackupRecurringExpense.toEntity() = RecurringExpenseEntity(
    id = id, name = name, category = category, amountMinor = amountMinor,
    frequency = frequency, dueDayOfMonth = dueDayOfMonth, isActive = isActive,
    endedAt = endedAt, notes = notes, sortOrder = sortOrder,
    createdAt = createdAt, updatedAt = updatedAt
)

private fun BalanceChangeEntity.toBackup() = BackupBalanceChange(
    id = id, subjectType = subjectType, subjectId = subjectId, fieldName = field,
    previousMinor = previousMinor, newMinor = newMinor, deltaMinor = deltaMinor,
    source = source, subjectName = subjectName, note = note, occurredAt = occurredAt
)

private fun BackupBalanceChange.toEntity() = BalanceChangeEntity(
    id = id, subjectType = subjectType, subjectId = subjectId, field = fieldName,
    previousMinor = previousMinor, newMinor = newMinor, deltaMinor = deltaMinor,
    source = source, subjectName = subjectName, note = note, occurredAt = occurredAt
)

private fun SavingsAccountEntity.toBackup() = BackupSavingsAccount(
    id = id, name = name, institution = institution,
    currentBalanceMinor = currentBalanceMinor, apyBasisPoints = apyBasisPoints,
    purpose = purpose, goalMinor = goalMinor, closedAt = closedAt, notes = notes,
    sortOrder = sortOrder, createdAt = createdAt, updatedAt = updatedAt
)

private fun BackupSavingsAccount.toEntity() = SavingsAccountEntity(
    id = id, name = name, institution = institution,
    currentBalanceMinor = currentBalanceMinor, apyBasisPoints = apyBasisPoints,
    purpose = purpose, goalMinor = goalMinor, closedAt = closedAt, notes = notes,
    sortOrder = sortOrder, createdAt = createdAt, updatedAt = updatedAt
)

private fun MetalLotEntity.toBackup() = BackupMetalLot(
    id = id, vendor = vendor, orderNumber = orderNumber, purchasedAt = purchasedAt,
    description = description, metal = metal, quantityMilli = quantityMilli,
    unitWeightMicroOz = unitWeightMicroOz, weightLabel = weightLabel,
    unitPriceMinor = unitPriceMinor, extendedPriceMinor = extendedPriceMinor,
    shippingMinor = shippingMinor, spotPriceMinor = spotPriceMinor,
    sourceFile = sourceFile, notes = notes, createdAt = createdAt
)

private fun BackupMetalLot.toEntity() = MetalLotEntity(
    id = id, vendor = vendor, orderNumber = orderNumber, purchasedAt = purchasedAt,
    description = description, metal = metal, quantityMilli = quantityMilli,
    unitWeightMicroOz = unitWeightMicroOz, weightLabel = weightLabel,
    unitPriceMinor = unitPriceMinor, extendedPriceMinor = extendedPriceMinor,
    shippingMinor = shippingMinor, spotPriceMinor = spotPriceMinor,
    sourceFile = sourceFile, notes = notes, createdAt = createdAt
)

private fun MetalSpotPriceEntity.toBackup() = BackupMetalSpotPrice(
    metal = metal, pricePerOzMinor = pricePerOzMinor, enteredAt = enteredAt
)

private fun BackupMetalSpotPrice.toEntity() = MetalSpotPriceEntity(
    metal = metal, pricePerOzMinor = pricePerOzMinor, enteredAt = enteredAt
)

private fun HoldingEntity.toBackup() = BackupHolding(
    id = id, accountName = accountName, symbol = symbol, description = description,
    quantityMicros = quantityMicros, lastPriceMinor = lastPriceMinor,
    currentValueMinor = currentValueMinor, costBasisMinor = costBasisMinor,
    gainLossMinor = gainLossMinor, isCash = isCash, type = type,
    asOf = asOf, importedAt = importedAt
)

private fun BackupHolding.toEntity() = HoldingEntity(
    id = id, accountName = accountName, symbol = symbol, description = description,
    quantityMicros = quantityMicros, lastPriceMinor = lastPriceMinor,
    currentValueMinor = currentValueMinor, costBasisMinor = costBasisMinor,
    gainLossMinor = gainLossMinor, isCash = isCash, type = type,
    asOf = asOf, importedAt = importedAt
)

private fun PayoffPlan.toBackup() = BackupPayoffPlan(
    monthsToDebtFree = monthsToDebtFree,
    // ISO, so nothing on the other side has to agree about month arithmetic.
    debtFreeDate = debtFreeDate?.toString(),
    clearsCompletely = clearsCompletely,
    isDebtFree = isDebtFree,
    totalPaidMinor = totalPaid.minorUnits,
    totalInterestMinor = totalInterest.minorUnits,
    startingMonthlyOutlayMinor = startingMonthlyOutlay.minorUnits,
    debts = projections.map { it.toBackup() }
)

private fun DebtProjection.toBackup() = BackupPayoffDebt(
    name = debt.name,
    balanceMinor = debt.currentBalance.minorUnits,
    payoffOrder = payoffOrder,
    monthsToPayoff = monthsToPayoff,
    payoffDate = payoffDate?.toString(),
    totalPaidMinor = totalPaid.minorUnits,
    interestPaidMinor = interestPaid.minorUnits,
    monthsSavedByRollover = monthsSavedByRollover,
    unpayableReason = unpayableReason?.explanation
)
