package com.mobilemcp.pro

import org.junit.Assert.*
import org.junit.Test

class PersianInputTest {
    @Test fun matchesOnlySimpleAppLaunches() {
        assertEquals("Telegram", PersianInput.simpleAppTarget("تلگرام رو باز کن."))
        assertEquals("Chrome", PersianInput.simpleAppTarget("Open Chrome"))
        assertNull(PersianInput.simpleAppTarget("برو داخل تلگرام و چت علی رو باز کن"))
        assertNull(PersianInput.simpleAppTarget("کروم رو باز کن و برو example.com"))
        assertNull(PersianInput.simpleAppTarget("گوگل رو باز کن اینو سرچ کن"))
    }
    @Test fun normalizesPersianConfirmations() {
        assertTrue(PersianInput.isPositiveConfirmation("بله!"))
        assertTrue(PersianInput.isPositiveConfirmation("تاييد"))
        assertTrue(PersianInput.isNegativeConfirmation("بی‌خیال"))
        assertFalse(PersianInput.isPositiveConfirmation("بله ولی هنوز نفرست"))
    }
    @Test fun writingContentIsNormalConversation() {
        assertFalse(PersianInput.isPhoneTask("یک شعر فارسی بنویس"))
        assertFalse(PersianInput.isPhoneTask("درباره تلگرام توضیح بده"))
        assertTrue(PersianInput.isPhoneTask("برو داخل گوگل قیمت را سرچ کن"))
        assertTrue(PersianInput.isPhoneTask("حالا بفرست", phoneContext = true))
        assertTrue(PersianInput.isPhoneTask("برگرد"))
    }
}
