package com.quietwire.quietledger.data.mapper

import com.quietwire.quietledger.core.money.Frequency
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.db.entity.BalanceChangeEntity
import com.quietwire.quietledger.data.db.entity.DebtEntity
import com.quietwire.quietledger.data.db.entity.RecurringExpenseEntity
import com.quietwire.quietledger.data.model.ChangeSource
import com.quietwire.quietledger.data.model.SubjectType
import com.quietwire.quietledger.data.model.DebtCategory
import com.quietwire.quietledger.data.model.RecurringExpenseCategory
import com.quietwire.quietledger.domain.model.BalanceChange
import com.quietwire.quietledger.domain.model.Debt
import com.quietwire.quietledger.domain.model.RecurringExpense
import java.time.Instant

fun DebtEntity.toDomain(): Debt = Debt(
    id = id,
    name = name,
    category = DebtCategory.fromStored(category),
    currentBalance = Money.ofMinor(currentBalanceMinor),
    originalBalance = originalBalanceMinor?.let { Money.ofMinor(it) },
    minimumPayment = Money.ofMinor(minimumPaymentMinor),
    scheduledPayment = Money.ofMinor(scheduledPaymentMinor),
    paymentFrequency = Frequency.fromStored(paymentFrequency),
    aprBasisPoints = aprBasisPoints,
    aprIsPromotional = aprIsPromotional,
    dueDayOfMonth = dueDayOfMonth,
    closedAt = closedAt?.let { Instant.ofEpochMilli(it) },
    notes = notes,
    sortOrder = sortOrder,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt)
)

fun Debt.toEntity(): DebtEntity = DebtEntity(
    id = id,
    name = name,
    category = category.name,
    currentBalanceMinor = currentBalance.minorUnits,
    originalBalanceMinor = originalBalance?.minorUnits,
    minimumPaymentMinor = minimumPayment.minorUnits,
    scheduledPaymentMinor = scheduledPayment.minorUnits,
    paymentFrequency = paymentFrequency.name,
    aprBasisPoints = aprBasisPoints,
    aprIsPromotional = aprIsPromotional,
    dueDayOfMonth = dueDayOfMonth,
    closedAt = closedAt?.toEpochMilli(),
    notes = notes,
    sortOrder = sortOrder,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)

fun RecurringExpenseEntity.toDomain(): RecurringExpense = RecurringExpense(
    id = id,
    name = name,
    category = RecurringExpenseCategory.fromStored(category),
    amount = Money.ofMinor(amountMinor),
    frequency = Frequency.fromStored(frequency),
    dueDayOfMonth = dueDayOfMonth,
    isActive = isActive,
    endedAt = endedAt?.let { Instant.ofEpochMilli(it) },
    notes = notes,
    sortOrder = sortOrder,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt)
)

fun RecurringExpense.toEntity(): RecurringExpenseEntity = RecurringExpenseEntity(
    id = id,
    name = name,
    category = category.name,
    amountMinor = amount.minorUnits,
    frequency = frequency.name,
    dueDayOfMonth = dueDayOfMonth,
    isActive = isActive,
    endedAt = endedAt?.toEpochMilli(),
    notes = notes,
    sortOrder = sortOrder,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)

fun BalanceChangeEntity.toDomain(): BalanceChange = BalanceChange(
    id = id,
    subjectId = subjectId,
    subjectType = SubjectType.fromStored(subjectType),
    subjectName = subjectName,
    fieldName = field,
    previous = previousMinor?.let { Money.ofMinor(it) },
    current = Money.ofMinor(newMinor),
    delta = Money.ofMinor(deltaMinor),
    source = ChangeSource.fromStored(source),
    note = note,
    occurredAt = Instant.ofEpochMilli(occurredAt)
)
