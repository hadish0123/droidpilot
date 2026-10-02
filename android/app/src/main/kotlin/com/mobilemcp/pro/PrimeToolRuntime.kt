package com.mobilemcp.pro

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.Locale

internal enum class ToolRisk(val level: Int) {
    SAFE(0),
    READ(1),
    WRITE(2),
    SENSITIVE(3),
    DESTRUCTIVE(4);

    companion object {
        fun parse(value: String?): ToolRisk? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT).orEmpty()
            if (normalized.isBlank()) return null
            return values().firstOrNull { it.name == normalized }
        }

        fun max(first: ToolRisk, second: ToolRisk?): ToolRisk =
            if (second != null && second.level > first.level) second else first
    }
}

internal data class PrimeToolDefinition(
    val name: String,
    val minimumRisk: ToolRisk,
    val paramsHint: String
)

internal object PrimeToolRegistry {
    private val definitions = listOf(
        PrimeToolDefinition(
            "open_app",
            ToolRisk.SAFE,
            """{"name":"Telegram"} or {"package":"org.telegram.messenger"}"""
        ),
        PrimeToolDefinition(
            "open_url",
            ToolRisk.SAFE,
            """{"url":"https://example.com"}"""
        ),
        PrimeToolDefinition(
            "click_element",
            ToolRisk.WRITE,
            """{"text":"..."} or {"id":"..."} or {"contentDescription":"..."}"""
        ),
        PrimeToolDefinition(
            "tap",
            ToolRisk.WRITE,
            """{"x":123,"y":456}"""
        ),
        PrimeToolDefinition(
            "long_press",
            ToolRisk.WRITE,
            """{"x":123,"y":456,"duration":1000}"""
        ),
        PrimeToolDefinition(
            "set_text",
            ToolRisk.WRITE,
            """{"text":"..."}"""
        ),
        PrimeToolDefinition(
            "type_text",
            ToolRisk.WRITE,
            """{"text":"..."}"""
        ),
        PrimeToolDefinition(
            "scroll",
            ToolRisk.SAFE,
            """{"direction":"up|down|left|right","amount":500}"""
        ),
        PrimeToolDefinition(
            "swipe",
            ToolRisk.SAFE,
            """{"startX":1,"startY":2,"endX":3,"endY":4,"duration":300}"""
        ),
        PrimeToolDefinition(
            "press_key",
            ToolRisk.SAFE,
            """{"key":"back|home|recents|notifications|quick_settings"}"""
        ),
        PrimeToolDefinition(
            "wait_for_element",
            ToolRisk.READ,
            """{"text":"...","timeout":10000}"""
        ),
        PrimeToolDefinition(
            "get_ui_tree",
            ToolRisk.READ,
            "{}"
        ),
        PrimeToolDefinition(
            "get_focused",
            ToolRisk.READ,
            "{}"
        ),
        PrimeToolDefinition(
            "find_element",
            ToolRisk.READ,
            """{"text":"..."} or {"id":"..."} or {"contentDescription":"..."}"""
        ),
        PrimeToolDefinition(
            "get_device_info",
            ToolRisk.READ,
            "{}"
        )
    )

    private val byName = definitions.associateBy { it.name }

    fun find(name: String): PrimeToolDefinition? = byName[name]

    fun promptContract(): String = definitions.joinToString("\n") { tool ->
        "- ${tool.name} [minimum_risk=${tool.minimumRisk.name.lowercase(Locale.ROOT)}] params: ${tool.paramsHint}"
    }
}

internal data class ToolRiskAssessment(
    val risk: ToolRisk,
    val requiresConfirmation: Boolean,
    val reason: String
)

internal object PrimeToolPolicy {
    private val destructiveHints = listOf(
        "delete",
        "remove",
        "erase",
        "trash",
        "حذف",
        "لغو حساب",
        "بستن حساب"
    )

    private val sensitiveHints = listOf(
        "send",
        "submit",
        "publish",
        "share",
        "pay",
        "purchase",
        "buy",
        "checkout",
        "transfer",
        "confirm",
        "security",
        "ارسال",
        "بفرست",
        "فرست",
        "انتشار",
        "منتشر",
        "اشتراک",
        "پرداخت",
        "خرید",
        "سفارش",
        "انتقال",
        "تأیید",
        "تاييد",
        "ثبت نهایی",
        "امنیت"
    )

    fun assess(
        command: String,
        params: JSONObject,
        declaredRisk: String?
    ): ToolRiskAssessment? {
        val definition = PrimeToolRegistry.find(command) ?: return null

        var risk = ToolRisk.max(
            definition.minimumRisk,
            ToolRisk.parse(declaredRisk)
        )

        if (command == "click_element") {
            val selector = listOf(
                params.optString("text"),
                params.optString("id"),
                params.optString("contentDescription")
            ).joinToString(" ")

            val normalized = selector
                .replace('\u200c', ' ')
                .lowercase(Locale.ROOT)

            risk = when {
                destructiveHints.any { normalized.contains(it) } ->
                    ToolRisk.DESTRUCTIVE
                sensitiveHints.any { normalized.contains(it) } &&
                    risk.level < ToolRisk.SENSITIVE.level ->
                    ToolRisk.SENSITIVE
                else -> risk
            }
        }

        val requiresConfirmation =
            risk == ToolRisk.SENSITIVE || risk == ToolRisk.DESTRUCTIVE

        val reason = when (risk) {
            ToolRisk.DESTRUCTIVE -> "destructive action"
            ToolRisk.SENSITIVE -> "sensitive final action"
            ToolRisk.WRITE -> "local write action"
            ToolRisk.READ -> "read-only action"
            ToolRisk.SAFE -> "safe navigation action"
        }

        return ToolRiskAssessment(risk, requiresConfirmation, reason)
    }

    fun confirmationMessage(
        command: String,
        params: JSONObject,
        assessment: ToolRiskAssessment
    ): String {
        val target = listOf(
            params.optString("text"),
            params.optString("contentDescription"),
            params.optString("id")
        ).firstOrNull { it.isNotBlank() }
            ?.take(80)
            ?: command

        return when (assessment.risk) {
            ToolRisk.DESTRUCTIVE ->
                "این مرحله ممکن است «$target» را به‌صورت مخرب یا غیرقابل‌برگشت انجام دهد. تأیید می‌کنی؟"
            ToolRisk.SENSITIVE ->
                "این مرحله یک اقدام حساس نهایی («$target») است. انجامش بدهم؟"
            else ->
                "این مرحله نیاز به تأیید دارد. انجامش بدهم؟"
        }
    }
}

internal sealed class PrimeToolExecution {
    data class Completed(
        val result: PrimeActionResult,
        val assessment: ToolRiskAssessment
    ) : PrimeToolExecution()

    data class NeedsConfirmation(
        val message: String,
        val assessment: ToolRiskAssessment
    ) : PrimeToolExecution()

    data class Rejected(
        val message: String
    ) : PrimeToolExecution()
}

internal class PrimeToolRuntime(
    private val runner: suspend (String, JSONObject) -> PrimeActionResult
) {
    suspend fun execute(
        command: String,
        params: JSONObject,
        declaredRisk: String?,
        confirmedForTask: Boolean
    ): PrimeToolExecution {
        val assessment = PrimeToolPolicy.assess(
            command = command,
            params = params,
            declaredRisk = declaredRisk
        ) ?: return PrimeToolExecution.Rejected(
            "Tool is not registered: $command"
        )

        if (assessment.requiresConfirmation && !confirmedForTask) {
            return PrimeToolExecution.NeedsConfirmation(
                PrimeToolPolicy.confirmationMessage(
                    command,
                    params,
                    assessment
                ),
                assessment
            )
        }

        val result = try {
            runner(command, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PrimeActionResult(false, e.message ?: "Action failed")
        }

        return PrimeToolExecution.Completed(result, assessment)
    }
}
