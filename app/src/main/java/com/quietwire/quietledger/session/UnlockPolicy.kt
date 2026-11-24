package com.quietwire.quietledger.session

/**
 * Which factors the user has chosen to require before the ledger opens.
 *
 * Both are optional, and both can be on at once. Nobody should be forced to
 * enrol a fingerprint to use a finance app, and equally nobody should have to
 * keep an authenticator to hand if they would rather just touch the sensor.
 *
 * The passphrase is deliberately not in this list, because it is not a gate in
 * the same sense. It is the thing that actually unwraps the key, and it is always
 * available, because without it a lost phone or a wiped secure element would mean
 * the data is gone for good.
 */
data class UnlockPolicy(
    val biometricRequired: Boolean = false,
    val totpRequired: Boolean = false
) {
    val hasAnyFactor: Boolean get() = biometricRequired || totpRequired

    val requiresBoth: Boolean get() = biometricRequired && totpRequired

    /** Short description for the settings screen. */
    val summary: String
        get() = when {
            requiresBoth -> "Fingerprint and a code from your authenticator"
            biometricRequired -> "Fingerprint, face or device PIN"
            totpRequired -> "A code from your authenticator"
            else -> "Passphrase only"
        }

    companion object {
        val NONE = UnlockPolicy()
    }
}

/** Progress through the factors in the current policy. */
data class UnlockProgress(
    val policy: UnlockPolicy,
    val biometricDone: Boolean = false,
    val totpDone: Boolean = false
) {
    val biometricOutstanding: Boolean get() = policy.biometricRequired && !biometricDone
    val totpOutstanding: Boolean get() = policy.totpRequired && !totpDone

    /** True once every required factor has been satisfied. */
    val satisfied: Boolean get() = !biometricOutstanding && !totpOutstanding

    fun withBiometric() = copy(biometricDone = true)
    fun withTotp() = copy(totpDone = true)
}
