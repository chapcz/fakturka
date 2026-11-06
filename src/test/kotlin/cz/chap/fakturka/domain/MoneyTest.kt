package cz.chap.fakturka.domain

import java.math.BigDecimal.ZERO
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun formatsAmounts() {
        assertEquals("30 580.50", formatAmount("30580.5".toBigDecimal()))
        assertEquals("7 260.00", formatAmount("7260".toBigDecimal()))
        assertEquals("0.00", formatAmount(ZERO))
        assertEquals("-1 000.00", formatAmount("-1000".toBigDecimal()))
        assertEquals("1 234 567.89", formatAmount("1234567.891".toBigDecimal()))
    }

    @Test
    fun roundsHalfUp() {
        assertEquals("10028".toBigDecimal(), "10027.50".toBigDecimal().wholeCrowns())
        assertEquals("6421.91".toBigDecimal(), "6421.905".toBigDecimal().money())
        assertEquals("12.35".toBigDecimal(), "12.345".toBigDecimal().money())
    }

    @Test
    fun parsesMoney() {
        assertEquals("30580.50".toBigDecimal(), "30580.5".toMoney())
    }

    @Test
    fun quarterOfDate() {
        assertEquals(Quarter(2026, 4), Quarter.of(LocalDate.of(2026, 10, 1)))
        assertEquals(Quarter(2026, 1), Quarter.of(LocalDate.of(2026, 3, 31)))
        assertEquals(7..9, Quarter(2026, 3).months)
    }

    @Test
    fun quarterContains() {
        assertTrue(LocalDate.of(2026, 9, 30) in Quarter(2026, 3))
        assertFalse(LocalDate.of(2026, 10, 1) in Quarter(2026, 3))
        assertFalse(LocalDate.of(2025, 8, 1) in Quarter(2026, 3))
    }

    @Test
    fun contactVatId() {
        val address = Address("Ulice", "1", "Praha", "11000", "CZ")
        val c = Contact("1", "X", address, "123", "cz12345678", null, null, ContactType.DOMESTIC)
        assertTrue(c.hasCzVatId)
        assertEquals("12345678", c.dicWithoutPrefix())
        val eu = c.copy(dic = "SK2020202020", type = ContactType.EU)
        assertFalse(eu.hasCzVatId)
        assertEquals("2020202020", eu.dicWithoutPrefix())
        val none = c.copy(dic = null)
        assertFalse(none.hasCzVatId)
        assertEquals(null, none.dicWithoutPrefix())
    }
}
