package br.com.fatec.syncro

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DueDatesTest {
    @Test fun acceptsTodayAndFutureButRejectsPastAndMissingDates() {
        val today = LocalDate.of(2026, 9, 21)
        assertTrue(DueDates.isValid("21/09/2026", today))
        assertTrue(DueDates.isValid("01/01/2027", today))
        assertFalse(DueDates.isValid("20/09/2026", today))
        assertFalse(DueDates.isValid("", today))
    }

    @Test fun rejectsImpossibleDatesAndTrailingText() {
        assertNull(DueDates.parse("31/02/2026"))
        assertNull(DueDates.parse("29/02/2026"))
        assertNull(DueDates.parse("21/09/2026 extra"))
        assertEquals(LocalDate.of(2028, 2, 29), DueDates.parse("29/02/2028"))
    }

    @Test fun handlesYearBoundary() {
        val today = LocalDate.of(2027, 1, 1)
        assertFalse(DueDates.isValid("31/12/2026", today))
        assertTrue(DueDates.isValid("01/01/2027", today))
    }

    @Test fun marksOnlyDatesLessThanOneWeekAwayAsDueSoon() {
        val today = LocalDate.of(2026, 9, 21)
        assertTrue(DueDates.isDueSoon("21/09/2026", today))
        assertTrue(DueDates.isDueSoon("27/09/2026", today))
        assertFalse(DueDates.isDueSoon("28/09/2026", today))
        assertFalse(DueDates.isDueSoon("20/09/2026", today))
        assertFalse(DueDates.isDueSoon("data inválida", today))
    }
}
