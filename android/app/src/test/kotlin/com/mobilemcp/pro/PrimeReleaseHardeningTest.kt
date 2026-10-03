package com.mobilemcp.pro

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrimeReleaseHardeningTest {
    @Test
    fun manifest_disables_backup_and_global_cleartext() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun extraction_rules_exclude_sensitive_app_data() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        listOf("database", "sharedpref", "file", "root").forEach {
            assertTrue(rules.contains("domain=\"$it\""))
        }
    }

    @Test
    fun gradle_distribution_is_checksum_pinned() {
        val wrapper = File("../gradle/wrapper/gradle-wrapper.properties").readText()
        assertTrue(
            wrapper.contains(
                "distributionSha256Sum=9d926787066a081739e8200858338b4a69e837c3a821a33aca9db09dd4a41026"
            )
        )
    }

    @Test
    fun release_ci_builds_release_and_runs_release_lint() {
        val workflow = File("../../.github/workflows/prime-p6-ci.yml").readText()
        assertTrue(workflow.contains("assembleRelease"))
        assertTrue(workflow.contains("lintRelease"))
        assertTrue(workflow.contains("app-release-unsigned.apk"))
    }
}
