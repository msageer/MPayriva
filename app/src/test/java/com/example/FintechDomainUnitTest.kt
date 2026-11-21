package com.example

import com.example.core.CurrencyFormatter
import com.example.core.PinHasher
import com.example.data.model.ServiceCatalogItem
import com.example.data.model.ServiceType
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionStatus
import com.example.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FintechDomainUnitTest {

    @Test
    fun `kobo formatter renders exact Nigerian Naira amounts`() {
        assertEquals("₦0.00", CurrencyFormatter.formatKobo(0L))
        assertEquals("₦1,500.50", CurrencyFormatter.formatKobo(150050L))
        assertEquals("₦250,000.00", CurrencyFormatter.formatKobo(25000000L))
        assertEquals(500000L, CurrencyFormatter.nairaToKobo("5,000"))
        assertEquals(370000L, CurrencyFormatter.nairaToKobo("₦3700.00"))
    }

    @Test
    fun `nigerian phone validator accepts valid prefixes and normalizes correctly`() {
        assertTrue(CurrencyFormatter.isValidNigerianPhone("08012345678"))
        assertTrue(CurrencyFormatter.isValidNigerianPhone("+2349012345678"))
        assertTrue(CurrencyFormatter.isValidNigerianPhone("2347034567890"))
        assertFalse(CurrencyFormatter.isValidNigerianPhone("05012345678"))
        assertFalse(CurrencyFormatter.isValidNigerianPhone("12345"))

        assertEquals("+2348012345678", CurrencyFormatter.normalizeNigerianPhone("08012345678"))
        assertEquals("08012345678", CurrencyFormatter.toLocalElevenDigitPhone("+2348012345678"))
    }

    @Test
    fun `salted PIN hashing verifies valid 4-digit PIN and rejects tampering`() {
        val saltA = PinHasher.generateSaltHex()
        val saltB = PinHasher.generateSaltHex()
        assertNotEquals(saltA, saltB)

        val hashA = PinHasher.hashPin("4829", saltA)
        assertEquals(64, hashA.length)
        assertTrue(PinHasher.verifyPin("4829", saltA, hashA))
        assertFalse(PinHasher.verifyPin("4828", saltA, hashA))
        assertFalse(PinHasher.verifyPin("4829", saltB, hashA))
    }

    @Test
    fun `catalog gross margin and safe enum deserialization prevent schema drift crashes`() {
        val item = ServiceCatalogItem(
            serviceType = ServiceType.DATA.name,
            providerCostKobo = 26000L,
            sellingPriceKobo = 29000L,
            feeKobo = 1000L
        )
        assertEquals(4000L, item.grossMarginKobo())

        val tx = TransactionRecord(type = "UNKNOWN_FUTURE_SERVICE", status = "PROCESSING")
        assertEquals(TransactionType.AIRTIME, tx.resolvedType())
        assertEquals(TransactionStatus.PROCESSING, tx.resolvedStatus())
    }
}
