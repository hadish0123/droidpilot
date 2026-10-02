package com.mobilemcp.pro.tools

internal enum class ToolRisk {
    SAFE,
    READ,
    WRITE,
    SENSITIVE,
    DESTRUCTIVE
}

internal data class ToolDefinition(
    val name: String,
    val risk: ToolRisk,
    val promptSyntax: String
)

internal class ToolRegistry private constructor(
    definitions: List<ToolDefinition>
) {
    private val byName = definitions.associateBy { it.name }
    val tools: List<ToolDefinition> = definitions.toList()

    fun find(name: String): ToolDefinition? = byName[name]

    fun promptCatalog(): String =
        tools.joinToString("\n") { tool ->
            "- ${tool.name} params: ${tool.promptSyntax} [risk=${tool.risk.name.lowercase()}]"
        }

    companion object {
        fun default(): ToolRegistry = ToolRegistry(
            listOf(
                ToolDefinition("open_app", ToolRisk.SAFE, """{"name":"Telegram"} or {"package":"org.telegram.messenger"}"""),
                ToolDefinition("open_url", ToolRisk.SAFE, """{"url":"https://example.com"}"""),
                ToolDefinition("click_element", ToolRisk.WRITE, """{"text":"..."} or {"id":"..."} or {"contentDescription":"..."}"""),
                ToolDefinition("tap", ToolRisk.WRITE, """{"x":123,"y":456}"""),
                ToolDefinition("long_press", ToolRisk.WRITE, """{"x":123,"y":456,"duration":1000}"""),
                ToolDefinition("set_text", ToolRisk.WRITE, """{"text":"..."}"""),
                ToolDefinition("type_text", ToolRisk.WRITE, """{"text":"..."}"""),
                ToolDefinition("scroll", ToolRisk.WRITE, """{"direction":"up|down|left|right","amount":500}"""),
                ToolDefinition("swipe", ToolRisk.WRITE, """{"startX":1,"startY":2,"endX":3,"endY":4,"duration":300}"""),
                ToolDefinition("press_key", ToolRisk.WRITE, """{"key":"back|home|recents|notifications|quick_settings"}"""),
                ToolDefinition("wait_for_element", ToolRisk.READ, """{"text":"...","timeout":10000}"""),
                ToolDefinition("get_ui_tree", ToolRisk.READ, "{}"),
                ToolDefinition("get_focused", ToolRisk.READ, "{}"),
                ToolDefinition("find_element", ToolRisk.READ, """{"text":"..."} or {"id":"..."} or {"contentDescription":"..."}"""),
                ToolDefinition("get_device_info", ToolRisk.READ, "{}")
            )
        )
    }
}
