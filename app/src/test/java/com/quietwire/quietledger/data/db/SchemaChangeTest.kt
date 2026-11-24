package com.quietwire.quietledger.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compares Room's exported schemas between versions.
 *
 * This database holds the only copy of someone's financial records, and
 * `fallbackToDestructiveMigration` is deliberately not configured, so a schema
 * change without a matching migration crashes on upgrade rather than quietly
 * wiping everything. That is the right trade, and it puts the burden on
 * noticing schema changes.
 *
 * Running the migration against real SQLite lives in the instrumented tests,
 * because Room's `MigrationTestHelper` reads the exported schemas from Android
 * assets and the bundled SQLite driver has no JVM native library. What is
 * checked here needs neither: comparing the committed JSON catches a dropped
 * column, an unintended addition, or a migration that was never written, and it
 * runs on every build rather than only when a device is attached.
 */
class SchemaChangeTest {

    private val schemaDir = File("schemas/com.quietwire.quietledger.data.db.QuietLedgerDatabase")

    private fun schema(version: Int) = Json
        .parseToJsonElement(File(schemaDir, "$version.json").readText())
        .jsonObject["database"]!!.jsonObject

    private fun tables(version: Int): Set<String> =
        schema(version)["entities"]!!.jsonArray
            .map { it.jsonObject["tableName"]!!.jsonPrimitive.content }
            .toSet()

    private fun columns(version: Int, table: String): Set<String> =
        schema(version)["entities"]!!.jsonArray
            .single { it.jsonObject["tableName"]!!.jsonPrimitive.content == table }
            .jsonObject["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
            .toSet()

    private fun latestVersion(): Int =
        schemaDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.maxOrNull()
            ?: error("no exported schemas found in ${schemaDir.absolutePath}")

    @Test
    fun `every version has an exported schema`() {
        assertTrue("schema directory missing", schemaDir.isDirectory)
        val versions = schemaDir.listFiles { f -> f.extension == "json" }!!
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .sorted()

        // No gaps. A missing file means a version shipped without its schema
        // being recorded, and the migration for it cannot be validated.
        assertEquals((1..versions.max()).toList(), versions)
    }

    @Test
    fun `version 2 adds the promotional flag and drops nothing`() {
        val added = columns(2, "debt") - columns(1, "debt")
        val removed = columns(1, "debt") - columns(2, "debt")

        assertEquals(setOf("apr_is_promotional"), added)
        assertEquals("a dropped column is data loss", emptySet<String>(), removed)
    }

    @Test
    fun `no table is ever dropped between versions`() {
        for (version in 2..latestVersion()) {
            val removed = tables(version - 1) - tables(version)
            assertEquals(
                "table dropped between v${version - 1} and v$version",
                emptySet<String>(),
                removed
            )
        }
    }

    @Test
    fun `no column is ever dropped between versions`() {
        for (version in 2..latestVersion()) {
            for (table in tables(version - 1).intersect(tables(version))) {
                val removed = columns(version - 1, table) - columns(version, table)
                assertEquals(
                    "column dropped from $table between v${version - 1} and v$version",
                    emptySet<String>(),
                    removed
                )
            }
        }
    }

    @Test
    fun `the database version matches the newest exported schema`() {
        // Bumping the version without exporting, or exporting without bumping,
        // both end with an upgrade that cannot be validated.
        assertEquals(
            latestVersion(),
            schema(latestVersion())["version"]!!.jsonPrimitive.content.toInt()
        )
    }
}
