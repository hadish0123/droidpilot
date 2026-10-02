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
    @Test fun launchesContinueWithShortAndPoliteCommands() {
        assertEquals("Google", PersianInput.simpleAppTarget("برو گوگل"))
        assertEquals("Telegram", PersianInput.simpleAppTarget("حالا برو تلگرام"))
        assertEquals("Rubika", PersianInput.simpleAppTarget("لطفاً برو تو روبیکا"))
        assertEquals("Rubika", PersianInput.simpleAppTarget("برنامه روبیکا رو باز کن"))
        assertEquals("یادداشت من", PersianInput.simpleAppTarget("اپ یادداشت من رو باز کن"))
        assertEquals("my notes", PersianInput.simpleAppTarget("Please open My Notes"))
        assertEquals("Rubika", PersianInput.simpleAppTarget("می‌تونی بری روبیکا؟"))
        assertEquals("Telegram", PersianInput.simpleAppTarget("وارد تِلگرام شو"))
        assertEquals("Google", PersianInput.simpleAppTarget("Go to Google"))
    }
    @Test fun routesUnknownAppsAndScreenFollowupsAsPhoneTasks() {
        assertTrue(PersianInput.isPhoneTask("برو اپ سفارشی من"))
        assertTrue(PersianInput.isPhoneTask("برو اپ سفارشی من و یک کار انجام بده"))
        assertTrue(PersianInput.isPhoneTask("روی علی بزن", phoneContext = true))
        assertTrue(PersianInput.isPhoneTask("به علی بنویس سلام", phoneContext = true))
        assertFalse(PersianInput.isPhoneTask("یک شعر فارسی بنویس", phoneContext = true))
        assertFalse(PersianInput.isPhoneTask("کار روبیکا چیست"))
    }
    @Test fun preservesCompoundNavigationAndWebsiteRequests() {
        assertNull(PersianInput.simpleAppTarget("برو گوگل و آب و هوا رو سرچ کن"))
        assertNull(PersianInput.simpleAppTarget("برو تلگرام یا روبیکا"))
        assertNull(PersianInput.simpleAppTarget("برو تو چت علی"))
        assertNull(PersianInput.simpleAppTarget("برو تلگرام علی رو بزن"))
        assertNull(PersianInput.simpleAppTarget("برو https://example.com"))
        assertNull(PersianInput.simpleAppTarget("برو example.com"))
        assertNull(PersianInput.simpleAppTarget("تنظیمات بلوتوث رو باز کن"))
    }
    @Test fun recognizesGlobalKeysWithoutOpeningAnAppNamedHome() {
        assertEquals("home", PersianInput.simpleKeyTarget("حالا برو خونه"))
        assertEquals("back", PersianInput.simpleKeyTarget("برگرد"))
        assertEquals("recents", PersianInput.simpleKeyTarget("برنامه‌های اخیر رو باز کن"))
        assertNull(PersianInput.simpleAppTarget("برو صفحه اصلی"))
    }
}
