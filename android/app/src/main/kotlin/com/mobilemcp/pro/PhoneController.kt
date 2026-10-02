package com.mobilemcp.pro

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobilemcp.pro.model.CommandRequest
import com.mobilemcp.pro.service.MobileAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** One local execution path for text chat and the persistent voice session. */
class PhoneController(private val context: Context) {
    companion object {
        private val screenCommands = setOf(
            "click_element", "tap", "long_press", "set_text", "type_text", "scroll", "swipe",
            "press_key", "wait_for_element", "get_focused", "find_element", "get_device_info"
        )
    }

    private fun installedApps(): List<InstalledApp> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    suspend fun readUiState(task: String = ""): String = withContext(Dispatchers.Default) {
        val service = MobileAccessibilityService.awaitConnected()
            ?: return@withContext "ACCESSIBILITY_OFF: دسترسی کنترل PRIME قطع است؛ آن را در تنظیمات دسترسی اندروید دوباره فعال کن."
        val apps = installedApps()
        val inventory = JSONArray()
        val normalizedTask = PersianInput.normalize(task)
        val ordered = apps.sortedWith(compareByDescending<InstalledApp> { app ->
            PersianInput.appAliases(app.label).any { normalizedTask.contains(it) }
        }.thenBy { it.label })
        var catalogSize = 0
        for (app in ordered) {
            val item = JSONObject().put("name", app.label.take(80)).put("package", app.packageName)
            val size = item.toString().length + 1
            if (catalogSize + size > 2400) continue
            inventory.put(item); catalogSize += size
        }
        var error = "صفحهٔ برنامه هنوز آماده نیست."
        repeat(3) { attempt ->
            coroutineContext.ensureActive()
            val currentService = MobileAccessibilityService.instance ?: service
            val response = currentService.handleCommand(CommandRequest("prime_ui", "get_ui_tree",
                JsonObject().apply { addProperty("maxDepth", 15) }))
            if (response.success && response.data != null) {
                return@withContext JSONObject().put("status", "ready")
                    .put("localPhoneControl", true).put("installedAppCount", apps.size)
                    .put("installedApps", inventory)
                    .put("screen", UiSnapshotFormatter.format(JSONObject(response.data.toString())))
                    .toString()
            }
            error = response.error ?: error
            if (attempt < 2) delay(180)
        }
        // PRIME's own foreground window is excluded. It is still possible to
        // open an app before an external window exists; this is not lost access.
        JSONObject().put("status", "screen_unavailable").put("localPhoneControl", true)
            .put("openAppAvailable", true).put("screenError", error)
            .put("installedAppCount", apps.size).put("installedApps", inventory).toString()
    }

    suspend fun execute(command: String, params: JSONObject): PrimeActionResult {
        coroutineContext.ensureActive()
        return when (command) {
            "open_app" -> openApp(params)
            "open_url" -> openUrl(params.optString("url"))
            "get_ui_tree" -> {
                val state = readUiState()
                PrimeActionResult(!state.startsWith("ACCESSIBILITY_OFF") &&
                    runCatching { JSONObject(state).optString("status") == "ready" }.getOrDefault(false), state)
            }
            in screenCommands -> withContext(Dispatchers.Default) {
                val service = MobileAccessibilityService.awaitConnected()
                    ?: return@withContext PrimeActionResult(false,
                        "ACCESSIBILITY_OFF: دسترسی کنترل PRIME قطع است؛ در تنظیمات دسترسی اندروید دوباره فعالش کن.")
                coroutineContext.ensureActive()
                val response = service.handleCommand(CommandRequest("prime_action", command,
                    JsonParser.parseString(params.toString()).asJsonObject))
                PrimeActionResult(response.success,
                    response.data?.toString() ?: response.error ?: "عملیات انجام نشد.")
            }
            else -> PrimeActionResult(false, "فرمان محلی پشتیبانی نمی‌شود: $command")
        }
    }

    private suspend fun openApp(params: JSONObject): PrimeActionResult {
        val packageName = params.optString("package").takeIf { it.isNotBlank() }
        val name = params.optString("name").trim()
        val match = if (packageName != null) AppMatch.Found(InstalledApp(name.ifBlank { packageName }, packageName))
            else withContext(Dispatchers.Default) { AppCatalog.match(name, installedApps()) }
        val app = when (match) {
            is AppMatch.Found -> match.app
            is AppMatch.Ambiguous -> return PrimeActionResult(false,
                "چند برنامه با این نام نصب است؛ نام دقیق را بگو: " + match.apps.joinToString("، ") { it.label })
            AppMatch.Missing -> {
                if ("google" in PersianInput.appAliases(name)) return openUrl("https://www.google.com/")
                return PrimeActionResult(false, "برنامهٔ «$name» در فهرست برنامه‌های نصب‌شده پیدا نشد؛ نام روی آیکن آن را بگو.")
            }
        }
        val launchResult = withContext(Dispatchers.Main) {
            coroutineContext.ensureActive()
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: return@withContext PrimeActionResult(false, "برنامهٔ «${app.label}» قابل باز کردن نیست.")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                // The bound Accessibility service can launch the requested app
                // even when the chat Activity is behind the persistent overlay.
                (MobileAccessibilityService.instance ?: context).startActivity(intent)
                PrimeActionResult(true, "درخواست باز کردن ${app.label} به اندروید ارسال شد.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { PrimeActionResult(false, "باز کردن ${app.label} انجام نشد: ${e.message}") }
        }
        if (!launchResult.success || MobileAccessibilityService.instance == null) return launchResult
        repeat(12) {
            coroutineContext.ensureActive()
            val observedPackage = withContext(Dispatchers.Default) {
                MobileAccessibilityService.instance?.currentTargetPackage()
            }
            if (observedPackage == app.packageName) return PrimeActionResult(true, "«${app.label}» باز شد.")
            delay(180)
        }
        return PrimeActionResult(true,
            "درخواست باز کردن ${app.label} به اندروید ارسال شد، اما نمایش صفحهٔ آن هنوز تأیید نشده است.")
    }

    private suspend fun openUrl(url: String): PrimeActionResult = withContext(Dispatchers.Main) {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank())
            return@withContext PrimeActionResult(false, "آدرس معتبر http یا https لازم است.")
        coroutineContext.ensureActive()
        try {
            (MobileAccessibilityService.instance ?: context).startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            PrimeActionResult(true, "درخواست باز کردن $url به مرورگر ارسال شد.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { PrimeActionResult(false, "باز کردن آدرس انجام نشد: ${e.message}") }
    }
}
