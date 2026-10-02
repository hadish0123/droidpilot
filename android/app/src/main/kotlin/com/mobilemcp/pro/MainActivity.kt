package com.mobilemcp.pro

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobilemcp.pro.databinding.ActivityMainBinding
import com.mobilemcp.pro.model.CommandRequest
import com.mobilemcp.pro.server.WebSocketCommandServer
import com.mobilemcp.pro.service.ConnectionForegroundService
import com.mobilemcp.pro.service.MobileAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO = 6001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var authManager: OpenAIAuthManager
    private lateinit var primeAgent: PrimeAgent

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingConfirmationTask: String? = null
    private var isBusy = false

    private var wsServer: WebSocketCommandServer? = null
    private var isServerRunning = false
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = OpenAIAuthManager(applicationContext)
        primeAgent = PrimeAgent(authManager)

        setupTextToSpeech()
        setupUI()
        updateAccessibilityStatus()
        updateAuthUI()
        appendChat("PRIME", "PRIME P6 آماده است. با اکانت ChatGPT خودت وصل شو و بعد تایپ کن یا روی میکروفن بزن.")
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateIPAddress()
        updateAuthUI()
    }

    private fun setupUI() {
        binding.btnSignIn.setOnClickListener { connectChatGpt() }
        binding.btnSignOut.setOnClickListener { disconnectChatGpt() }
        binding.btnUsage.setOnClickListener { authManager.openUsageSettings() }

        binding.btnSend.setOnClickListener { sendCurrentMessage() }
        binding.btnMic.setOnClickListener { startVoiceInput() }
        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendCurrentMessage()
            true
        }

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnToggleServer.setOnClickListener {
            if (isServerRunning) stopServer() else startServer()
        }

        updateIPAddress()
        updateServerUI()
    }

    private fun connectChatGpt() {
        if (isBusy) return
        setBusy(true, "Opening ChatGPT sign-in…")
        appScope.launch {
            try {
                val profile = authManager.signIn { uri ->
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                updateAuthUI()
                val label = profile.email ?: profile.name ?: "ChatGPT account"
                appendChat("PRIME", "اکانت وصل شد: " + label + ". حالا P6 آماده اجرای دستورهاست.")
                speak("اتصال انجام شد. پرایم آماده است.")
            } catch (e: Exception) {
                appendChat("PRIME", "اتصال انجام نشد: " + (e.message ?: "خطای نامشخص"))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun disconnectChatGpt() {
        if (isBusy) return
        setBusy(true, "Signing out…")
        appScope.launch {
            val remoteConfirmed = try {
                authManager.signOut()
            } catch (_: Exception) {
                false
            }
            pendingConfirmationTask = null
            updateAuthUI()
            appendChat(
                "PRIME",
                if (remoteConfirmed) "اتصال ChatGPT قطع شد."
                else "توکن‌های محلی پاک شدند. اگر لازم بود، دسترسی PRIME را از تنظیمات ChatGPT هم قطع کن."
            )
            setBusy(false)
        }
    }

    private fun updateAuthUI() {
        val profile = authManager.currentProfile()
        val signedIn = authManager.isSignedIn()
        binding.btnSignIn.visibility = if (signedIn) View.GONE else View.VISIBLE
        binding.btnSignOut.visibility = if (signedIn) View.VISIBLE else View.GONE
        binding.btnUsage.visibility = if (signedIn) View.VISIBLE else View.GONE

        binding.tvAccountStatus.text = if (signedIn) {
            val label = profile?.email ?: profile?.name ?: "Connected ChatGPT account"
            "Connected • " + label
        } else {
            "Not connected"
        }

        binding.tvModelStatus.text = if (signedIn) {
            "PRIME • P6 • " + primeAgent.engineLabel
        } else {
            "PRIME • P6"
        }
    }

    private fun sendCurrentMessage() {
        val text = binding.etMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || isBusy) return
        binding.etMessage.setText("")
        handleInput(text)
    }

    private fun handleInput(input: String) {
        appendChat("شما", input)

        var task = input
        var confirmed = false
        val pending = pendingConfirmationTask

        if (pending != null) {
            when {
                isPositiveConfirmation(input) -> {
                    task = pending
                    confirmed = true
                    pendingConfirmationTask = null
                }
                isNegativeConfirmation(input) -> {
                    pendingConfirmationTask = null
                    appendChat("PRIME", "لغو شد.")
                    speak("لغو شد.")
                    return
                }
                else -> {
                    pendingConfirmationTask = null
                }
            }
        }

        setBusy(true, "P6 is working…")
        appScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    primeAgent.run(
                        userText = task,
                        confirmedForTask = confirmed,
                        uiProvider = { readUiState() },
                        actionRunner = { command, params -> executePrimeAction(command, params) },
                        onProgress = { message ->
                            runOnUiThread {
                                binding.tvAgentStatus.text = "P6 • " + message
                            }
                        }
                    )
                }

                if (outcome.needsConfirmation) {
                    pendingConfirmationTask = task
                }
                appendChat("PRIME", outcome.text)
                binding.tvModelStatus.text = "PRIME • P6 • " + primeAgent.engineLabel
                speak(outcome.text)
            } catch (e: Exception) {
                val message = userFriendlyError(e)
                appendChat("PRIME", message)
                speak(message)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun userFriendlyError(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("usage limit", ignoreCase = true) ||
                message.contains("subscription_sharing_usage_limit", ignoreCase = true) ->
                "سهمیه فعلی ChatGPT به حدش رسیده. بعداً دوباره امتحان کن."
            message.contains("expired", ignoreCase = true) ->
                "اتصال ChatGPT نیاز به ورود دوباره دارد."
            message.isNotBlank() -> "خطا: " + message
            else -> "یک خطای نامشخص رخ داد."
        }
    }

    private fun isPositiveConfirmation(value: String): Boolean {
        val v = value.trim().lowercase(Locale.getDefault())
        return v in setOf(
            "بله", "آره", "اره", "اوکی", "باشه", "تایید", "تأیید",
            "انجام بده", "ارسال کن", "yes", "ok", "okay", "confirm", "do it"
        )
    }

    private fun isNegativeConfirmation(value: String): Boolean {
        val v = value.trim().lowercase(Locale.getDefault())
        return v in setOf("نه", "خیر", "لغو", "بیخیال", "cancel", "no", "stop")
    }

    private suspend fun readUiState(): String = withContext(Dispatchers.Default) {
        val service = MobileAccessibilityService.instance
            ?: return@withContext "ACCESSIBILITY_OFF: PRIME cannot read or operate the current screen until Accessibility Service is enabled."

        val params = JsonObject().apply { addProperty("maxDepth", 12) }
        val response = service.handleCommand(
            CommandRequest("prime_ui", "get_ui_tree", params)
        )
        if (!response.success) {
            "UI_ERROR: " + (response.error ?: "unknown")
        } else {
            response.data?.toString() ?: "{}"
        }
    }

    private suspend fun executePrimeAction(
        command: String,
        params: JSONObject
    ): PrimeActionResult {
        return when (command) {
            "open_app" -> openApp(params)
            "open_url" -> openUrl(params)
            "get_ui_tree" -> {
                val state = readUiState()
                PrimeActionResult(!state.startsWith("UI_ERROR") && !state.startsWith("ACCESSIBILITY_OFF"), state.take(2500))
            }
            else -> executeAccessibilityCommand(command, params)
        }
    }

    private suspend fun executeAccessibilityCommand(
        command: String,
        params: JSONObject
    ): PrimeActionResult = withContext(Dispatchers.Default) {
        val allowed = setOf(
            "click_element", "tap", "long_press", "set_text", "type_text",
            "scroll", "swipe", "press_key", "wait_for_element", "get_focused"
        )
        if (command !in allowed) {
            return@withContext PrimeActionResult(false, "Unsupported local action: " + command)
        }

        val service = MobileAccessibilityService.instance
            ?: return@withContext PrimeActionResult(
                false,
                "Accessibility Service is OFF. Ask the user to enable it in PRIME."
            )

        val gsonParams = try {
            JsonParser.parseString(params.toString()).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }

        val response = service.handleCommand(
            CommandRequest("prime_action", command, gsonParams)
        )
        if (response.success) {
            PrimeActionResult(true, response.data?.toString()?.take(2500) ?: "OK")
        } else {
            PrimeActionResult(false, response.error ?: "Action failed")
        }
    }

    private suspend fun openApp(params: JSONObject): PrimeActionResult = withContext(Dispatchers.Main) {
        val explicitPackage = params.optString("package").takeIf { it.isNotBlank() }
        val requestedName = params.optString("name").takeIf { it.isNotBlank() }

        val packageName = explicitPackage ?: requestedName?.let { resolveAppPackage(it) }
        if (packageName.isNullOrBlank()) {
            return@withContext PrimeActionResult(false, "App not found")
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withContext PrimeActionResult(false, "App is not launchable: " + packageName)

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        PrimeActionResult(true, "Opened " + (requestedName ?: packageName))
    }

    private fun resolveAppPackage(name: String): String? {
        val key = name.trim().lowercase(Locale.getDefault())
        val aliases = mapOf(
            "telegram" to "org.telegram.messenger",
            "تلگرام" to "org.telegram.messenger",
            "chrome" to "com.android.chrome",
            "کروم" to "com.android.chrome",
            "settings" to "com.android.settings",
            "تنظیمات" to "com.android.settings",
            "whatsapp" to "com.whatsapp",
            "واتساپ" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "اینستاگرام" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "یوتیوب" to "com.google.android.youtube",
            "gmail" to "com.google.android.gm",
            "جیمیل" to "com.google.android.gm"
        )

        aliases[key]?.let { known ->
            if (packageManager.getLaunchIntentForPackage(known) != null) return known
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(launcherIntent, 0)

        val exact = activities.firstOrNull {
            it.loadLabel(packageManager).toString().trim().equals(name, ignoreCase = true)
        }
        if (exact != null) return exact.activityInfo.packageName

        return activities.firstOrNull {
            it.loadLabel(packageManager).toString().contains(name, ignoreCase = true)
        }?.activityInfo?.packageName
    }

    private suspend fun openUrl(params: JSONObject): PrimeActionResult = withContext(Dispatchers.Main) {
        val raw = params.optString("url")
        if (raw.isBlank()) return@withContext PrimeActionResult(false, "URL is missing")
        val uri = Uri.parse(raw)
        if (uri.scheme !in listOf("http", "https")) {
            return@withContext PrimeActionResult(false, "Only http/https URLs are allowed")
        }

        return@withContext try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            PrimeActionResult(true, "Opened " + raw)
        } catch (e: Exception) {
            PrimeActionResult(false, e.message ?: "Could not open URL")
        }
    }

    private fun setupTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.setSpeechRate(1.0f)
            }
        }
    }

    private fun speak(text: String) {
        val tts = textToSpeech ?: return
        val language = if (text.any { it.code in 0x0600..0x06FF }) Locale("fa", "IR")
        else Locale.getDefault()

        val result = tts.setLanguage(language)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
        tts.speak(text.take(2500), TextToSpeech.QUEUE_FLUSH, null, "prime-p6")
    }

    private fun startVoiceInput() {
        if (isBusy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            appendChat("PRIME", "تشخیص صدا روی این گوشی در دسترس نیست.")
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        binding.tvAgentStatus.text = "Listening…"
                    }

                    override fun onBeginningOfSpeech() {
                        binding.tvAgentStatus.text = "Listening…"
                    }

                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        binding.tvAgentStatus.text = "Understanding…"
                    }

                    override fun onError(error: Int) {
                        binding.tvAgentStatus.text = "Ready"
                        appendChat("PRIME", "صدای واضحی دریافت نشد. دوباره امتحان کن.")
                    }

                    override fun onResults(results: Bundle?) {
                        binding.tvAgentStatus.text = "Ready"
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (spoken.isNotBlank()) {
                            binding.etMessage.setText(spoken)
                            binding.etMessage.setSelection(spoken.length)
                            sendCurrentMessage()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceInput()
        }
    }

    private fun appendChat(who: String, message: String) {
        if (message.isBlank()) return
        val line = who + "\n" + message.trim() + "\n\n"
        binding.tvChat.append(line)
        binding.chatScrollView.post {
            binding.chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setBusy(busy: Boolean, status: String? = null) {
        isBusy = busy
        binding.btnSend.isEnabled = !busy
        binding.btnMic.isEnabled = !busy
        binding.btnSignIn.isEnabled = !busy
        binding.btnSignOut.isEnabled = !busy
        binding.tvAgentStatus.text = status ?: if (busy) "P6 is working…" else "Ready"
    }

    // ---- Optional local/LAN MCP bridge ----

    private fun startServer() {
        if (!MobileAccessibilityService.isRunning) {
            appendLog("ERROR: Accessibility Service is not enabled")
            return
        }

        val port = binding.etPort.text.toString().toIntOrNull() ?: 8765
        try {
            wsServer = WebSocketCommandServer(
                port = port,
                onLog = { message -> runOnUiThread { appendLog(message) } },
                onConnectionChange = { count ->
                    runOnUiThread { binding.tvConnections.text = count.toString() }
                }
            )
            wsServer?.start()

            val serviceIntent = Intent(this, ConnectionForegroundService::class.java).apply {
                putExtra(ConnectionForegroundService.EXTRA_PORT, port)
            }
            startForegroundService(serviceIntent)

            isServerRunning = true
            updateServerUI()
            appendLog("PRIME Device Bridge started on port " + port)
        } catch (e: Exception) {
            appendLog("Failed to start bridge: " + e.message)
        }
    }

    private fun stopServer() {
        try {
            wsServer?.shutdown()
            wsServer = null
            stopService(Intent(this, ConnectionForegroundService::class.java))
            isServerRunning = false
            updateServerUI()
            appendLog("PRIME Device Bridge stopped")
        } catch (e: Exception) {
            appendLog("Error stopping bridge: " + e.message)
        }
    }

    private fun updateServerUI() {
        if (isServerRunning) {
            binding.btnToggleServer.text = getString(R.string.btn_stop_server)
            binding.tvStatus.text = getString(R.string.status_waiting)
            setStatusColor(R.color.status_waiting)
            binding.etPort.isEnabled = false
        } else {
            binding.btnToggleServer.text = getString(R.string.btn_start_server)
            binding.tvStatus.text = getString(R.string.status_disconnected)
            setStatusColor(R.color.status_disconnected)
            binding.tvConnections.text = "0"
            binding.etPort.isEnabled = true
        }
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        val indicator = binding.accessibilityIndicator.background as? GradientDrawable
            ?: GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                binding.accessibilityIndicator.background = it
            }

        if (isEnabled) {
            indicator.setColor(ContextCompat.getColor(this, R.color.status_connected))
            binding.tvAccessibilityStatus.text = "Phone control: ON"
        } else {
            indicator.setColor(ContextCompat.getColor(this, R.color.status_disconnected))
            binding.tvAccessibilityStatus.text = "Phone control: OFF"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC).any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun setStatusColor(colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        val indicator = binding.statusIndicator.background as? GradientDrawable
            ?: GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                binding.statusIndicator.background = it
            }
        indicator.setColor(color)
    }

    private fun updateIPAddress() {
        binding.tvIpAddress.text = getDeviceIpAddress() ?: "No Wi-Fi"
    }

    @Suppress("DEPRECATION")
    private fun getDeviceIpAddress(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) return null
            String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun appendLog(message: String) {
        val timestamp = dateFormat.format(Date())
        binding.tvLog.append("[" + timestamp + "] " + message + "\n")
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(View.FOCUS_DOWN)
        }

        val text = binding.tvLog.text.toString()
        if (text.length > 10_000) {
            binding.tvLog.text = text.takeLast(5_000)
        }
    }

    override fun onDestroy() {
        if (isServerRunning) stopServer()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        appScope.cancel()
        super.onDestroy()
    }
}
