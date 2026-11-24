package com.quietwire.quietledger.data.model

/**
 * A debt is something with a balance that goes down and eventually reaches
 * zero. Rent does not belong here, which is why it lives in
 * [RecurringExpenseCategory] instead.
 *
 * The desktop app keeps both in one table and separates them with hardcoded
 * category lists, which is what forces every query to carry a filter.
 */
enum class DebtCategory(val label: String, val snowballEligible: Boolean = true) {
    CREDIT_CARD("Credit card"),
    VEHICLE_LOAN("Vehicle loan"),
    TAX("Taxes owed"),
    MEDICAL("Medical"),
    STUDENT_LOAN("Student loan"),
    PERSONAL_LOAN("Personal loan"),
    MORTGAGE("Mortgage", snowballEligible = false),
    OTHER("Other");

    companion object {
        fun fromStored(value: String): DebtCategory =
            entries.firstOrNull { it.name == value } ?: OTHER

        val snowball: List<DebtCategory> get() = entries.filter { it.snowballEligible }
    }
}

/**
 * Rolls granular categories up for reporting. Without this, grouping spend by
 * category would produce thirty rows of a few dollars each, which tells you
 * nothing. Analytics groups by this; the editor picks the specific category.
 */
enum class ExpenseGroup(val label: String) {
    HOUSING("Housing"),
    UTILITIES("Utilities"),
    INSURANCE("Insurance"),
    TRANSPORT("Transport"),
    SERVICES("Services"),
    HEALTH("Health"),
    SAVINGS("Savings"),
    OTHER("Other")
}

/**
 * A recurring obligation with no balance. It repeats until you cancel it.
 *
 * Deliberately specific rather than a handful of coarse buckets. "Utilities"
 * as a single category cannot answer "what am I paying for electricity", which
 * is most of the reason to track this at all. Each one carries an
 * [ExpenseGroup] so reporting can still roll up.
 *
 * Note on mortgages. A mortgage is a debt, not an expense, because it has a
 * balance that amortises, so it lives in [DebtCategory]. If your mortgage
 * payment includes escrow for property tax and homeowners insurance, do not
 * also add those here, or they will be counted twice. See the note in the
 * expense editor.
 */
enum class RecurringExpenseCategory(
    val label: String,
    val group: ExpenseGroup
) {
    // Housing
    RENT("Rent", ExpenseGroup.HOUSING),
    LEASE("Lease", ExpenseGroup.HOUSING),
    HOA_FEES("HOA or condo fees", ExpenseGroup.HOUSING),
    PROPERTY_TAX("Property tax", ExpenseGroup.HOUSING),
    HOME_MAINTENANCE("Home maintenance", ExpenseGroup.HOUSING),

    // Utilities
    ELECTRIC("Electric", ExpenseGroup.UTILITIES),
    NATURAL_GAS("Natural gas", ExpenseGroup.UTILITIES),
    PROPANE("Propane", ExpenseGroup.UTILITIES),
    HEATING_OIL("Heating oil", ExpenseGroup.UTILITIES),
    WATER("Water", ExpenseGroup.UTILITIES),
    SEWER("Sewer", ExpenseGroup.UTILITIES),
    TRASH("Trash and recycling", ExpenseGroup.UTILITIES),
    INTERNET("Internet", ExpenseGroup.UTILITIES),
    MOBILE_PHONE("Mobile phone", ExpenseGroup.UTILITIES),
    LANDLINE("Landline", ExpenseGroup.UTILITIES),

    // Insurance
    HOMEOWNERS_INSURANCE("Homeowners insurance", ExpenseGroup.INSURANCE),
    RENTERS_INSURANCE("Renters insurance", ExpenseGroup.INSURANCE),
    AUTO_INSURANCE("Car or truck insurance", ExpenseGroup.INSURANCE),
    MOTORCYCLE_INSURANCE("Motorcycle insurance", ExpenseGroup.INSURANCE),
    RECREATIONAL_INSURANCE("Boat or RV insurance", ExpenseGroup.INSURANCE),
    LIFE_INSURANCE("Life insurance", ExpenseGroup.INSURANCE),
    UMBRELLA_INSURANCE("Umbrella insurance", ExpenseGroup.INSURANCE),
    OTHER_INSURANCE("Other insurance", ExpenseGroup.INSURANCE),

    // Health. Insurance and care are separated on purpose: a telehealth
    // subscription is a service you buy, not cover you hold, and lumping them
    // together distorts any question about what insurance actually costs.
    HEALTH_INSURANCE("Health insurance", ExpenseGroup.HEALTH),
    DENTAL_INSURANCE("Dental insurance", ExpenseGroup.HEALTH),
    VISION_INSURANCE("Vision insurance", ExpenseGroup.HEALTH),
    MEDICAL("Medical or telehealth", ExpenseGroup.HEALTH),
    PHARMACY("Pharmacy or prescriptions", ExpenseGroup.HEALTH),
    GYM("Gym or fitness", ExpenseGroup.HEALTH),

    // Transport
    VEHICLE_LEASE("Vehicle lease", ExpenseGroup.TRANSPORT),
    FUEL("Fuel", ExpenseGroup.TRANSPORT),
    VEHICLE_REGISTRATION("Vehicle registration", ExpenseGroup.TRANSPORT),
    PUBLIC_TRANSPORT("Public transport", ExpenseGroup.TRANSPORT),

    // Services
    SUBSCRIPTION("Subscriptions", ExpenseGroup.SERVICES),
    STREAMING("Streaming", ExpenseGroup.SERVICES),
    LAWN_CARE("Lawn or snow service", ExpenseGroup.SERVICES),
    CLEANING("Cleaning", ExpenseGroup.SERVICES),
    CHILDCARE("Childcare", ExpenseGroup.SERVICES),
    PET_CARE("Pet care", ExpenseGroup.SERVICES),

    // Other
    SAVINGS_TRANSFER("Savings transfer", ExpenseGroup.SAVINGS),
    CHARITY("Charity or giving", ExpenseGroup.OTHER),
    OTHER("Other", ExpenseGroup.OTHER);

    companion object {
        /**
         * The coarse categories this enum replaced, mapped so anything stored
         * under an old name still reads back as something sensible rather than
         * silently collapsing to OTHER. Kept because the desktop app still uses
         * the coarse set and an import has to land somewhere reasonable.
         */
        private val legacyNames = mapOf(
            "HOUSING" to RENT,
            "INSURANCE" to OTHER_INSURANCE,
            "UTILITIES" to ELECTRIC,
            "SERVICES" to SUBSCRIPTION,
            "TRANSPORT" to FUEL
        )

        fun fromStored(value: String): RecurringExpenseCategory =
            entries.firstOrNull { it.name == value }
                ?: legacyNames[value]
                ?: OTHER

        /** Grouped for a picker, in the order the groups are declared. */
        fun grouped(): Map<ExpenseGroup, List<RecurringExpenseCategory>> =
            entries.groupBy { it.group }
                .toSortedMap(compareBy { it.ordinal })
    }
}

/**
 * What a savings account is for.
 *
 * Changes how a balance should be read: an emergency fund at three months of
 * expenses means something different from a holiday fund at the same figure.
 */
enum class SavingsPurpose(val label: String) {
    EMERGENCY("Emergency fund"),
    GENERAL("General savings"),
    SINKING("Sinking fund"),
    RETIREMENT("Retirement"),
    EDUCATION("Education"),
    VEHICLE("Vehicle"),
    HOME("Home or renovation"),
    TRAVEL("Travel"),
    TAX("Set aside for tax"),
    OTHER("Other");

    companion object {
        fun fromStored(value: String): SavingsPurpose =
            entries.firstOrNull { it.name == value } ?: GENERAL
    }
}

/** What caused a recorded change, so history can be read back honestly. */
enum class ChangeSource(val label: String) {
    MANUAL("Edited"),
    INITIAL("Created"),
    IMPORT("Imported"),
    PAYMENT("Payment"),
    CORRECTION("Correction");

    companion object {
        fun fromStored(value: String): ChangeSource =
            entries.firstOrNull { it.name == value } ?: MANUAL
    }
}

/** Which kind of record a change refers to. */
enum class SubjectType {
    DEBT,
    RECURRING_EXPENSE,
    SAVINGS_ACCOUNT,
    CASH_ACCOUNT,
    HOLDING,
    PRECIOUS_METAL;

    companion object {
        fun fromStored(value: String): SubjectType =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Unknown subject type: $value")
    }
}
