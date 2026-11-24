package com.quietwire.quietledger.data.backup

import com.quietwire.quietledger.data.db.QuietLedgerDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every table is watched for changes.
 *
 * A table missing from the watch list is a change that silently never gets
 * backed up, and nothing would report it: the app would appear to be working
 * while quietly losing one kind of edit.
 *
 * Compared against Room's exported schema, which is generated from the real
 * database definition. Reflection was tried first and does not work: Room's
 * `@Entity` is not retained at runtime, so `getAnnotation` returns null. A
 * second test guarding the first is what caught that, rather than the
 * comparison silently passing against an empty set.
 */
class WatchedTablesTest {

    private fun schemaTables(): Set<String> {
        val schema = File(
            "schemas/com.quietwire.quietledger.data.db.QuietLedgerDatabase/" +
                "${QuietLedgerDatabase.VERSION}.json"
        )
        assertTrue(
            "Room's exported schema for version ${QuietLedgerDatabase.VERSION} " +
                "is missing at ${schema.absolutePath}",
            schema.isFile
        )

        // kotlinx rather than org.json: Android's org.json is a non-functional
        // stub on the JVM unit test classpath and returns null for everything,
        // which the guard test below caught.
        return Json.parseToJsonElement(schema.readText())
            .jsonObject["database"]!!
            .jsonObject["entities"]!!
            .jsonArray
            .map { it.jsonObject["tableName"]!!.jsonPrimitive.content }
            .toSet()
    }

    @Test
    fun `the watch list covers every table in the database`() {
        assertEquals(
            schemaTables().toSortedSet(),
            AutoBackupTrigger.WATCHED_TABLES.toSortedSet()
        )
    }

    @Test
    fun `the schema really was read, so the comparison is not vacuous`() {
        val tables = schemaTables()

        assertEquals(7, tables.size)
        assertTrue(tables.contains("metal_spot_price"))
        assertTrue(tables.contains("debt"))
    }
}
