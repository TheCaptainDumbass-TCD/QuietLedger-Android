package com.quietwire.quietledger.data.importer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parses a Fidelity "Portfolio_Positions" CSV export.
 *
 * The file is not a clean table. After the data rows there is a blank line,
 * two quoted disclaimer paragraphs, another blank line, and a "Date
 * downloaded" footer. Columns are matched by header name rather than
 * position, so Fidelity adding or reordering a column does not silently
 * shift every value by one.
 */
object FidelityPositionsParser {

    private const val COL_ACCOUNT_NAME = "account name"
    private const val COL_SYMBOL = "symbol"
    private const val COL_DESCRIPTION = "description"
    private const val COL_QUANTITY = "quantity"
    private const val COL_LAST_PRICE = "last price"
    private const val COL_CURRENT_VALUE = "current value"
    private const val COL_TOTAL_GAIN_DOLLAR = "total gain/loss dollar"
    private const val COL_COST_BASIS_TOTAL = "cost basis total"
    private const val COL_AVERAGE_COST_BASIS = "average cost basis"
    private const val COL_TYPE = "type"

    private val REQUIRED = listOf(COL_SYMBOL, COL_CURRENT_VALUE)

    /** Values Fidelity uses to mean "we do not have this". */
    private val UNAVAILABLE = setOf("", "--", "-", "n/a", "na")

    private val PENDING_ACTIVITY = "pending activity"

    private val DOWNLOADED_AT = Regex(
        """date downloaded\s+([A-Za-z]{3}-\d{1,2}-\d{4})\s+(\d{1,2}):(\d{2})\s*([ap])\.?m\.?""",
        RegexOption.IGNORE_CASE
    )

    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM-d-uuuu", Locale.ENGLISH)

    fun parse(text: String): FidelityImport {
        val rows = CsvReader.parse(text)

        val headerIndex = rows.indexOfFirst { row ->
            val lower = row.map { it.trim().lowercase() }
            lower.contains(COL_SYMBOL) && lower.contains(COL_ACCOUNT_NAME)
        }
        if (headerIndex < 0) {
            throw FidelityParseException(
                "No header row found. Expected a row containing 'Symbol' and 'Account name'."
            )
        }

        val columns = rows[headerIndex]
            .mapIndexed { index, name -> name.trim().lowercase() to index }
            .toMap()

        REQUIRED.firstOrNull { it !in columns }?.let {
            throw FidelityParseException("Export is missing the '$it' column.")
        }

        val positions = mutableListOf<FidelityPosition>()
        val skipped = mutableListOf<SkippedRow>()

        for (rowIndex in (headerIndex + 1) until rows.size) {
            val row = rows[rowIndex]

            val accountName = row.value(columns, COL_ACCOUNT_NAME)
            val rawSymbol = row.value(columns, COL_SYMBOL)

            // Data ends at the first row with neither an account nor a symbol.
            // Everything after that is blank lines, disclaimers and the footer.
            if (accountName.isBlank() && rawSymbol.isBlank()) break

            if (rawSymbol.trim().lowercase() == PENDING_ACTIVITY) {
                skipped.add(
                    SkippedRow(
                        label = rawSymbol.trim(),
                        reason = "Unsettled activity, not a holding"
                    )
                )
                continue
            }

            // Fidelity marks money market and certain other rows with a
            // trailing asterisk footnote, e.g. "SPAXX**".
            val symbol = rawSymbol.trim().trimEnd('*').trim()
            if (symbol.isBlank()) {
                skipped.add(SkippedRow(label = "row ${rowIndex + 1}", reason = "No symbol"))
                continue
            }

            val description = row.value(columns, COL_DESCRIPTION).trim()
            val quantity = parseNumber(row.value(columns, COL_QUANTITY))
            val lastPrice = parseNumber(row.value(columns, COL_LAST_PRICE))
            val type = row.value(columns, COL_TYPE).trim()

            // A money market position has a value but no quantity and no
            // price. Treating it as a holding would mean trying to fetch a
            // NAV for it forever.
            val isCash = quantity == null && lastPrice == null &&
                (type.equals("Cash", ignoreCase = true) ||
                    description.contains("MONEY MARKET", ignoreCase = true))

            positions.add(
                FidelityPosition(
                    accountName = accountName.trim(),
                    symbol = symbol.uppercase(),
                    description = description,
                    quantity = quantity,
                    lastPrice = lastPrice,
                    currentValue = parseNumber(row.value(columns, COL_CURRENT_VALUE)),
                    totalGainLossDollar = parseNumber(row.value(columns, COL_TOTAL_GAIN_DOLLAR)),
                    costBasisTotal = parseNumber(row.value(columns, COL_COST_BASIS_TOTAL)),
                    averageCostBasis = parseNumber(row.value(columns, COL_AVERAGE_COST_BASIS)),
                    type = type,
                    isCash = isCash
                )
            )
        }

        if (positions.isEmpty()) {
            throw FidelityParseException("No positions found in the export.")
        }

        return FidelityImport(
            positions = positions,
            skipped = skipped,
            downloadedAt = parseDownloadedAt(text)
        )
    }

    private fun List<String>.value(columns: Map<String, Int>, name: String): String {
        val index = columns[name] ?: return ""
        return getOrNull(index) ?: ""
    }

    /**
     * Handles the shapes Fidelity writes: "$34.54", "+$13.76", "-$0.08",
     * "$1,234.56", "-0.24%", bare decimals, and the "--" placeholder.
     */
    internal fun parseNumber(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.lowercase() in UNAVAILABLE) return null

        val cleaned = trimmed
            .replace("$", "")
            .replace(",", "")
            .replace("%", "")
            .replace("+", "")
            .replace(" ", "")

        if (cleaned.isEmpty() || cleaned == "-") return null

        // Accounting style negatives, e.g. ($12.34)
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            return cleaned.substring(1, cleaned.length - 1).toDoubleOrNull()?.let { -it }
        }

        return cleaned.toDoubleOrNull()
    }

    /**
     * Reads the footer, e.g. "Date downloaded Oct-02-2026 08:15 p.m ET".
     * Note Fidelity writes "p.m" with one full stop, which trips up the
     * standard time parsers, so this is matched by hand.
     */
    internal fun parseDownloadedAt(text: String): LocalDateTime? {
        val match = DOWNLOADED_AT.find(text) ?: return null
        val (datePart, hourPart, minutePart, meridiem) = match.destructured

        val date = runCatching { LocalDate.parse(datePart, DATE_FORMAT) }.getOrNull() ?: return null

        var hour = hourPart.toIntOrNull() ?: return null
        val minute = minutePart.toIntOrNull() ?: return null
        if (hour !in 1..12 || minute !in 0..59) return null

        if (meridiem.equals("p", ignoreCase = true) && hour != 12) hour += 12
        if (meridiem.equals("a", ignoreCase = true) && hour == 12) hour = 0

        return date.atTime(hour, minute)
    }
}
