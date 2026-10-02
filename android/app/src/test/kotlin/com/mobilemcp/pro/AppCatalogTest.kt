package com.mobilemcp.pro

import org.junit.Assert.*
import org.junit.Test

class AppCatalogTest {
    @Test fun resolvesPersianAndEnglishLabelsInEitherPhoneLanguage() {
        val apps = listOf(InstalledApp("Google", "test.google"), InstalledApp("روبیکا", "test.rubika"))
        assertEquals("test.google", (AppCatalog.match("گوگل", apps) as AppMatch.Found).app.packageName)
        assertEquals("test.rubika", (AppCatalog.match("Rubika", apps) as AppMatch.Found).app.packageName)
    }
    @Test fun resolvesArbitraryInstalledAppsAndArabicLetterVariants() {
        val apps = listOf(InstalledApp("يادداشت من", "test.notes"))
        assertEquals("test.notes", (AppCatalog.match("یادداشت من", apps) as AppMatch.Found).app.packageName)
        assertEquals("test.notes", (AppCatalog.match("test.notes", apps) as AppMatch.Found).app.packageName)
    }
    @Test fun doesNotPickTheFirstOfSeveralSimilarApps() {
        val apps = listOf(InstalledApp("روبیکا اصلی", "test.one"), InstalledApp("روبیکا دوم", "test.two"))
        assertTrue(AppCatalog.match("روبیکا", apps) is AppMatch.Ambiguous)
        assertEquals("test.two", (AppCatalog.match("روبیکا دوم", apps) as AppMatch.Found).app.packageName)
    }
    @Test fun prefersExactGoogleAndNeverConfusesDriveWithGoogle() {
        val drive = InstalledApp("Google Drive", "test.drive")
        assertTrue(AppCatalog.match("گوگل", listOf(drive)) is AppMatch.Missing)
        val google = InstalledApp("Google", "test.google")
        assertEquals(google, (AppCatalog.match("Google", listOf(drive, google)) as AppMatch.Found).app)
    }
    @Test fun deduplicatesLauncherActivitiesAndReportsMissingApps() {
        val app = InstalledApp("My App", "test.app")
        assertEquals(app, (AppCatalog.match("My App", listOf(app, app)) as AppMatch.Found).app)
        assertTrue(AppCatalog.match("Missing App", listOf(app)) is AppMatch.Missing)
    }
    @Test fun prefersOfficialTelegramWhenSeveralVariantsAreInstalled() {
        val official = InstalledApp("Telegram", "org.telegram.messenger")
        val clone = InstalledApp("Telegram", "test.clone")
        assertEquals(official, (AppCatalog.match("تلگرام", listOf(clone, official)) as AppMatch.Found).app)
    }
}
