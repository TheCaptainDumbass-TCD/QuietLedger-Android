package com.quietwire.quietledger.data.importer

/**
 * Minimal RFC 4180 style CSV splitter, with limits.
 *
 * Splitting on commas is not good enough here. Fidelity appends disclaimer
 * paragraphs that are quoted and contain commas, and a naive split turns one of
 * those into fifty columns.
 *
 * The limits are the security-relevant part. This is the one parser in the app
 * that handles completely untrusted input with no cryptographic gate in front of
 * it: the user picks a file through the system picker and it gets parsed. Whether
 * the file is hostile or merely wrong, an unbounded parser that accumulates
 * everything in memory will take the process down with an OutOfMemoryError.
 *
 * A real Fidelity positions export is around 2.5 KB. The caps below leave three
 * orders of magnitude of headroom while still refusing anything absurd, and they
 * fail with a message a person can act on rather than a crash.
 */
object CsvReader {

    /** 8 MB of text. A genuine broker export is a few kilobytes. */
    const val MAX_INPUT_CHARS = 8 * 1024 * 1024

    /** Generous for a positions file; a holdings list does not have 100,000 rows. */
    const val MAX_ROWS = 100_000

    /** Fidelity uses 16. Something with thousands of columns is not a positions file. */
    const val MAX_COLUMNS_PER_ROW = 512

    /** One cell. The longest real field is a disclaimer paragraph. */
    const val MAX_FIELD_CHARS = 64 * 1024

    fun parse(text: String): List<List<String>> {
        if (text.length > MAX_INPUT_CHARS) {
            throw CsvTooLargeException(
                "That file is ${text.length / 1024 / 1024} MB, which is far larger than " +
                    "any positions export. Check you picked the right file."
            )
        }

        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        fun endField() {
            if (field.length > MAX_FIELD_CHARS) {
                throw CsvTooLargeException("A single value in that file is implausibly long.")
            }
            if (row.size >= MAX_COLUMNS_PER_ROW) {
                throw CsvTooLargeException(
                    "A row in that file has more than $MAX_COLUMNS_PER_ROW columns, " +
                        "which is not a positions export."
                )
            }
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            if (rows.size >= MAX_ROWS) {
                throw CsvTooLargeException(
                    "That file has more than $MAX_ROWS rows, which is not a positions export."
                )
            }
            rows.add(row)
            row = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> endField()
                (c == '\n' || c == '\r') && !inQuotes -> {
                    // Swallow CRLF as a single break.
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                }
                else -> field.append(c)
            }
            i++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) {
            endRow()
        }

        return rows
    }
}

/**
 * The input exceeded a parser limit. A distinct type so the UI can say "that is
 * not the right file" rather than surfacing a crash.
 */
class CsvTooLargeException(message: String) : Exception(message)
