package com.quietwire.quietledger.ui.common

/**
 * "1 months" appeared on screen because counts were interpolated straight into
 * strings. Small, but it is the kind of thing that makes an app look unfinished.
 *
 * These stay in Kotlin rather than moving to `plurals.xml` while the app is
 * English only. Worth revisiting if it is ever localised, since other languages
 * have more than two plural forms.
 */
fun pluralise(count: Int, singular: String, plural: String = "${singular}s"): String =
    if (count == 1) "$count $singular" else "$count $plural"

fun months(count: Int): String = pluralise(count, "month")

fun debts(count: Int): String = pluralise(count, "debt")
