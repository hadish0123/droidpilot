package com.mobilemcp.pro.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    private val registry = ToolRegistry.default()

    @Test
    fun exposesOnlyRegisteredTools() {
        assertEquals(ToolRisk.SAFE, registry.find("open_app")?.risk)
        assertEquals(ToolRisk.READ, registry.find("get_ui_tree")?.risk)
        assertEquals(ToolRisk.WRITE, registry.find("click_element")?.risk)
        assertNull(registry.find("shell_exec"))
    }

    @Test
    fun promptCatalogIsGeneratedFromRegistry() {
        val catalog = registry.promptCatalog()

        assertTrue(catalog.contains("open_app"))
        assertTrue(catalog.contains("get_ui_tree"))
        assertTrue(catalog.contains("risk=write"))
    }
}
