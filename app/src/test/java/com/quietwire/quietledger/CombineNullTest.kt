package com.quietwire.quietledger

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Does combine emit when one source emits null? */
class CombineNullTest {

    @Test
    fun `two flows, second emits null`() = runTest {
        val result = withTimeoutOrNull(2_000) {
            combine(flowOf("a"), flowOf<String?>(null)) { x, y -> "$x/$y" }.first()
        }
        assertNotNull("combine did not emit at all", result)
        assertEquals("a/null", result)
    }

    @Test
    fun `vararg combine of seven with a null, as the dashboard does`() = runTest {
        val flows = listOf(
            flowOf<Any?>("debts"), flowOf<Any?>("activity"), flowOf<Any?>(1),
            flowOf<Any?>("savings"), flowOf<Any?>(2), flowOf<Any?>(3),
            flowOf<Any?>(null)
        )
        val result = withTimeoutOrNull(2_000) {
            combine(flows) { values -> values.size }.first()
        }
        assertNotNull("vararg combine did not emit with a null present", result)
        assertEquals(7, result)
    }
}
