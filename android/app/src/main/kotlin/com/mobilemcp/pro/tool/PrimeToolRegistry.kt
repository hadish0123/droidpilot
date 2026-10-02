package com.mobilemcp.pro.tool

internal enum class ToolRisk {
    READ,
    NAVIGATION,
    WRITE,
    SENSITIVE,
    DESTRUCTIVE
}

internal enum class ConfirmationPolicy {
    NEVER,
    CONTEXTUAL,
    ALWAYS
}

internal data class ToolSpec(
    val name: String,
    val risk: ToolRisk,
    val confirmationPolicy: ConfirmationPolicy
)

/**
 * Single source of truth for commands the model is allowed to send to the
 * local Android runtime. Unknown commands fail closed before execution.
 */
internal object PrimeToolRegistry {
    private val specs = listOf(
        ToolSpec("open_app", ToolRisk.NAVIGATION, ConfirmationPolicy.NEVER),
        ToolSpec("open_url", ToolRisk.NAVIGATION, ConfirmationPolicy.NEVER),
        ToolSpec("click_element", ToolRisk.WRITE, ConfirmationPolicy.CONTEXTUAL),
        ToolSpec("tap", ToolRisk.WRITE, ConfirmationPolicy.CONTEXTUAL),
        ToolSpec("long_press", ToolRisk.WRITE, ConfirmationPolicy.CONTEXTUAL),
        ToolSpec("set_text", ToolRisk.WRITE, ConfirmationPolicy.CONTEXTUAL),
        ToolSpec("type_text", ToolRisk.WRITE, ConfirmationPolicy.CONTEXTUAL),
        ToolSpec("scroll", ToolRisk.NAVIGATION, ConfirmationPolicy.NEVER),
        ToolSpec("swipe", ToolRisk.NAVIGATION, ConfirmationPolicy.NEVER),
        ToolSpec("press_key", ToolRisk.NAVIGATION, ConfirmationPolicy.CONTEXTUAL),
        ToolSpec("wait_for_element", ToolRisk.READ, ConfirmationPolicy.NEVER),
        ToolSpec("get_ui_tree", ToolRisk.READ, ConfirmationPolicy.NEVER),
        ToolSpec("get_focused", ToolRisk.READ, ConfirmationPolicy.NEVER),
        ToolSpec("find_element", ToolRisk.READ, ConfirmationPolicy.NEVER),
        ToolSpec("get_device_info", ToolRisk.READ, ConfirmationPolicy.NEVER)
    ).associateBy(ToolSpec::name)

    fun find(name: String): ToolSpec? = specs[name]

    fun isSupported(name: String): Boolean = name in specs

    fun all(): List<ToolSpec> = specs.values.sortedBy(ToolSpec::name)
}
