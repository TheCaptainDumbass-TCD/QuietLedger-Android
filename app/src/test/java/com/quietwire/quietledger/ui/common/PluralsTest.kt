package com.quietwire.quietledger.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PluralsTest {

    @Test
    fun `one is singular`() {
        // "saves 1 months" was on screen before this existed.
        assertEquals("1 month", months(1))
        assertEquals("1 debt", debts(1))
    }

    @Test
    fun `everything else is plural`() {
        assertEquals("0 months", months(0))
        assertEquals("2 months", months(2))
        assertEquals("10 months", months(10))
        assertEquals("0 debts", debts(0))
        assertEquals("3 debts", debts(3))
    }

    @Test
    fun `irregular plurals can be given explicitly`() {
        assertEquals("1 entry", pluralise(1, "entry", "entries"))
        assertEquals("2 entries", pluralise(2, "entry", "entries"))
    }
}
