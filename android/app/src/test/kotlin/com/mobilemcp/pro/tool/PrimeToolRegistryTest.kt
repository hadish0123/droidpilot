package com.mobilemcp.pro.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeToolRegistryTest {
    @Test fun registryContainsOnlyTheExpectedLocalCommands() {
        val expected = setOf(
            "open_app", "open_url", "click_element", "tap", "long_press",
            "set_text", "type_text", "scroll", "swipe", "press_key",
            "wait_for_element", "get_ui_tree", "get_focused", "find_element",
            "get_device_info"
        )

        assertEquals(expected, PrimeToolRegistry.all().map { it.name }.toSet())
        assertFalse(PrimeToolRegistry.isSupported("shell_exec"))
        assertNull(PrimeToolRegistry.find("shell_exec"))
    }

    @Test fun registryCarriesRiskAndConfirmationMetadata() {
        val read = PrimeToolRegistry.find("get_ui_tree")!!
        assertEquals(ToolRisk.READ, read.risk)
        assertEquals(ConfirmationPolicy.NEVER, read.confirmationPolicy)

        val write = PrimeToolRegistry.find("click_element")!!
        assertEquals(ToolRisk.WRITE, write.risk)
        assertEquals(ConfirmationPolicy.CONTEXTUAL, write.confirmationPolicy)

        assertTrue(PrimeToolRegistry.isSupported("open_app"))
    }
}
