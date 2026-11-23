package com.example

import com.example.core.VoucherSheetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoucherSheetLayoutTest {

    @Test
    fun a4SheetIsStrictlyFixedTo30EpinsPerPage() {
        assertEquals(3, VoucherSheetLayout.COLUMNS)
        assertEquals(10, VoucherSheetLayout.ROWS)
        assertEquals(30, VoucherSheetLayout.CARDS_PER_PAGE)
        assertEquals(595, VoucherSheetLayout.PAGE_WIDTH)
        assertEquals(842, VoucherSheetLayout.PAGE_HEIGHT)
    }

    @Test
    fun paginationHandles1To50EpinsAccurately() {
        assertEquals(1, VoucherSheetLayout.pageCountFor(1))
        assertEquals(1, VoucherSheetLayout.pageCountFor(3))
        assertEquals(1, VoucherSheetLayout.pageCountFor(10))
        assertEquals(1, VoucherSheetLayout.pageCountFor(30))
        assertEquals(2, VoucherSheetLayout.pageCountFor(31))
        assertEquals(2, VoucherSheetLayout.pageCountFor(50))
        assertEquals(3, VoucherSheetLayout.pageCountFor(61))
    }

    @Test
    fun everySlotHasIdenticalFixedDimensionsAndStaysInsideA4Bounds() {
        val firstSlot = VoucherSheetLayout.slotBounds(0)
        val thirdSlot = VoucherSheetLayout.slotBounds(2)
        val lastSlot = VoucherSheetLayout.slotBounds(29)

        // Whether printing 3 PINs (slots 0..2) or 30 PINs (slots 0..29), slot dimensions are identical
        assertEquals(firstSlot.width, thirdSlot.width, 0.01f)
        assertEquals(firstSlot.height, thirdSlot.height, 0.01f)
        assertEquals(firstSlot.width, lastSlot.width, 0.01f)
        assertEquals(firstSlot.height, lastSlot.height, 0.01f)

        // All 30 slots must fit strictly inside the A4 page margins without overlapping
        for (i in 0 until VoucherSheetLayout.CARDS_PER_PAGE) {
            val s = VoucherSheetLayout.slotBounds(i)
            assertTrue("Left margin intact for slot $i", s.left >= VoucherSheetLayout.MARGIN_X - 0.1f)
            assertTrue("Right margin intact for slot $i", s.right <= VoucherSheetLayout.PAGE_WIDTH - VoucherSheetLayout.MARGIN_X + 0.1f)
            assertTrue("Top margin intact for slot $i", s.top >= VoucherSheetLayout.MARGIN_TOP + VoucherSheetLayout.HEADER_HEIGHT - 0.1f)
            assertTrue("Bottom margin intact for slot $i", s.bottom <= VoucherSheetLayout.PAGE_HEIGHT - VoucherSheetLayout.MARGIN_BOTTOM + 0.1f)
        }
    }
}
