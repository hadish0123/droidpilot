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
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import com.google.android.material.button.MaterialButton
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobilemcp.pro.databinding.ActivityMainBinding
import com.mobilemcp.pro.model.CommandRequest
import com.mobilemcp.pro.server.WebSocketCommandServer
import com.mobilemcp.pro.service.ConnectionForegroundService
import com.mobilemcp.pro.service.MobileAccessibilityService
import com.mobilemcp.pro.service.VoiceSessionForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private lateinit var chatStore: PrimeChatStore
    private var currentChatId: Long = -1L
    private var renderedMessageCount: Int = 0

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingConfirmationTask: String? = null
    private var isBusy = false

    private var voiceModeActive = false
    private var voiceAutoSend = false
    private var pendingVoiceAutoSend = false
    private var pendingStartPersistentVoice = false

    private var wsServer: WebSocketCommandServer? = null
    private var isServerRunning = false
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = OpenAIAuthManager(applicationContext)
        primeAgent = PrimeAgent(authManager)
        chatStore = PrimeChatStore(applicationContext)

        setupTextToSpeech()
        setupUI()
        updateAccessibilityStatus()
        updateAuthUI()
        openInitialChat()
        warmUpPrime()

        if (intent?.data?.scheme == "primep6") {
            binding.tvAgentStatus.text = "Finishing ChatGPT connection…"
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.data?.scheme == "primep6" &&
            intent.data?.host == "auth-complete"
        ) {
            binding.drawerLayout.closeDrawers()
            binding.tvAgentStatus.text = "Finishing ChatGPT connection…"
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateIPAddress()
        updateAuthUI()
        syncChatIfChanged()

        if (
            pendingStartPersistentVoice &&
            Settings.canDrawOverlays(this) &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartPersistentVoice = false
            launchPersistentVoiceOverlay()
        }
    }

    private fun setupUI() {
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.closeDrawer(Gravity.LEFT, false)
            binding.drawerLayout.openDrawer(Gravity.RIGHT)
        }
        binding.btnNewChat.setOnClickListener {
            renderChatHistory()
            binding.drawerLayout.closeDrawer(Gravity.RIGHT, false)
            binding.drawerLayout.openDrawer(Gravity.LEFT)
        }

        binding.btnCreateNewChat.setOnClickListener {
            createNewChat()
        }
        binding.btnOpenChatHistory.setOnClickListener {
            renderChatHistory()
            binding.drawerLayout.closeDrawer(Gravity.RIGHT, false)
            binding.drawerLayout.openDrawer(Gravity.LEFT)
        }
        binding.btnDeleteAllChats.setOnClickListener {
            confirmDeleteAllChats()
        }
        binding.btnOverlayPermission.setOnClickListener {
            openOverlayPermission()
        }
        binding.btnAboutPrime.setOnClickListener {
            showAboutPrime()
        }

        binding.btnConnectBanner.setOnClickListener { connectChatGpt() }
        binding.btnSignIn.setOnClickListener { connectChatGpt() }
        binding.btnSignOut.setOnClickListener { disconnectChatGpt() }
        binding.btnUsage.setOnClickListener { authManager.openUsageSettings() }
        binding.btnTestOpenAi.setOnClickListener { testOpenAiConnection() }

        binding.btnSend.setOnClickListener { sendCurrentMessage() }
        binding.btnMic.setOnClickListener { startVoiceInput(autoSend = false) }
        binding.btnVoice.setOnClickListener { enterVoiceMode() }
        binding.btnExitVoice.setOnClickListener { exitVoiceMode() }
        binding.btnVoiceMic.setOnClickListener {
            textToSpeech?.stop()
            speechRecognizer?.cancel()
            startVoiceInput(autoSend = true)
        }
        binding.btnVoiceSend.setOnClickListener { sendVoiceMessage() }
        binding.btnPlus.setOnClickListener { showQuickActions() }

        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendCurrentMessage()
            true
        }
        binding.etVoiceMessage.setOnEditorActionListener { _, _, _ ->
            sendVoiceMessage()
            true
        }
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateComposerButtons()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnToggleServer.setOnClickListener {
            if (isServerRunning) stopServer() else startServer()
        }

        binding.tvAppVersion.text = "P6 • PRIME " + BuildConfig.VERSION_NAME
        updateComposerButtons()
        updateIPAddress()
        updateServerUI()
        renderChatHistory()
    }

    private fun warmUpPrime() {
        if (!authManager.isSignedIn()) return

        appScope.launch(Dispatchers.IO) {
            try {
                primeAgent.warmUp()
            } catch (_: Exception) {
                // Warm-up is best effort. Real requests still have retry and
                // user-facing diagnostics.
            }
        }
    }

    private fun connectChatGpt() {
        if (isBusy) return
        binding.drawerLayout.closeDrawers()
        setBusy(true, "Opening ChatGPT sign-in…")

        appScope.launch {
            try {
                binding.tvAgentStatus.text = "Checking OpenAI connection…"
                withContext(Dispatchers.IO) {
                    authManager.testOpenAiConnection()
                }

                val profile = authManager.signIn(
                    openBrowser = { uri ->
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                    onCallbackReceived = {
                        binding.tvAgentStatus.text = "Authorization received • finishing connection…"
                    }
                )

                primeAgent.resetSession()
                updateAuthUI()
                warmUpPrime()
                showPlanWelcomeOnce()

                val label = profile.email ?: profile.name ?: "ChatGPT account"
                appendChat("PRIME", "اکانت ChatGPT وصل شد: $label")
                speak("اتصال انجام شد. پرایم آماده است.")
            } catch (e: Exception) {
                val message = authFriendlyError(e)
                appendChat("PRIME", message)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun authFriendlyError(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("PRIME_NETWORK_DNS") ->
                "مرحله ورود در مرورگر انجام شد، ولی خود برنامه PRIME نتوانست به auth.openai.com وصل شود. " +
                    "اگر VPN یا Proxy روشن است، PRIME را هم داخل لیست برنامه‌های VPN قرار بده و دوباره Continue with ChatGPT را بزن."
            message.contains("PRIME_NETWORK_OPENAI") ->
                "مرورگر مجوز را دریافت کرد، اما اتصال شبکه خود PRIME به OpenAI کامل نشد. اینترنت/VPN را بررسی کن و دوباره امتحان کن."
            message.contains("invalid_grant", ignoreCase = true) ->
                "کد ورود منقضی شد. یک بار دیگر Continue with ChatGPT را بزن؛ ثبت PRIME حفظ شده و دوباره از صفر ساخته نمی‌شود."
            message.isNotBlank() -> "اتصال ChatGPT کامل نشد: $message"
            else -> "اتصال ChatGPT کامل نشد."
        }
    }

    private fun testOpenAiConnection() {
        if (isBusy) return
        setBusy(true, "Testing OpenAI connection…")
        appScope.launch {
            try {
                val authResult = withContext(Dispatchers.IO) {
                    authManager.testOpenAiConnection()
                }
                val apiResult = if (authManager.isSignedIn()) {
                    withContext(Dispatchers.IO) {
                        primeAgent.testApiConnection()
                    }
                } else {
                    "API test requires a connected ChatGPT account"
                }
                appendChat(
                    "PRIME",
                    "اتصال شبکه PRIME به OpenAI سالم است.\n$authResult\n$apiResult"
                )
            } catch (e: Exception) {
                appendChat("PRIME", authFriendlyError(e))
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
            primeAgent.resetSession()
            updateAuthUI()
            appendChat(
                "PRIME",
                if (remoteConfirmed) "اتصال ChatGPT قطع شد."
                else "ورود محلی پاک شد. برای قطع کامل دسترسی می‌توانی از تنظیمات ChatGPT هم PRIME را Disconnect کنی."
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
        binding.btnConnectBanner.visibility = if (signedIn) View.GONE else View.VISIBLE

        binding.tvAccountStatus.text = if (signedIn) {
            val label = profile?.email ?: profile?.name ?: "Connected ChatGPT account"
            "Connected • $label"
        } else {
            "Not connected"
        }

        binding.tvModelStatus.text = if (signedIn) {
            "P6 • ChatGPT plan"
        } else {
            "P6"
        }
    }

    private fun showPlanWelcomeOnce() {
        val prefs = getSharedPreferences("prime_p6_ux", Context.MODE_PRIVATE)
        if (prefs.getBoolean("chatgpt_plan_welcome_shown", false)) return

        AlertDialog.Builder(this)
            .setTitle("ChatGPT plan connected")
            .setMessage(
                "درخواست‌های واجد شرایط PRIME P6 از سهمیه ChatGPT Plus/Pro متصل‌شده استفاده می‌کنند. " +
                    "از Manage usage می‌توانی مصرف را ببینی."
            )
            .setPositiveButton("باشه") { dialog, _ ->
                prefs.edit().putBoolean("chatgpt_plan_welcome_shown", true).apply()
                dialog.dismiss()
            }
            .setNeutralButton("Manage usage") { _, _ ->
                authManager.openUsageSettings()
            }
            .show()
    }

    private fun openInitialChat() {
        val prefs = getSharedPreferences(
            PrimeChatStore.PREFS,
            Context.MODE_PRIVATE
        )
        val saved = prefs.getLong(
            PrimeChatStore.ACTIVE_CHAT_ID,
            -1L
        )

        val chats = chatStore.listChats()
        val target = when {
            saved > 0 && chatStore.chatExists(saved) -> saved
            chats.isNotEmpty() -> chats.first().id
            else -> chatStore.createChat()
        }

        loadChat(target, showGreetingWhenEmpty = true)
    }

    private fun createNewChat() {
        val chatId = chatStore.createChat()
        loadChat(chatId, showGreetingWhenEmpty = false)
        renderChatHistory()
        binding.drawerLayout.closeDrawer(Gravity.LEFT)
    }

    private fun loadChat(
        chatId: Long,
        showGreetingWhenEmpty: Boolean = true
    ) {
        if (!chatStore.chatExists(chatId)) return

        currentChatId = chatId
        getSharedPreferences(
            PrimeChatStore.PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putLong(PrimeChatStore.ACTIVE_CHAT_ID, chatId)
            .apply()

        pendingConfirmationTask = null
        binding.chatMessages.removeAllViews()
        binding.etMessage.setText("")
        binding.tvAgentStatus.text = "Ready"

        val messages = chatStore.messages(chatId)
        primeAgent.restoreConversation(
            messages.map { it.role to it.content }
        )

        renderedMessageCount = messages.size
        messages.forEach { stored ->
            appendChat(
                if (stored.role == "user") "شما" else "PRIME",
                stored.content,
                persist = false
            )
        }

        if (messages.isEmpty()) {
            appendChat(
                "PRIME",
                if (showGreetingWhenEmpty) {
                    "PRIME P6 آماده است. می‌تونی مثل ChatGPT تایپ کنی، با میکروفن متن بگی، یا Voice Mode رو روشن کنی."
                } else {
                    "چت جدید آماده است."
                },
                persist = false
            )
        }

        renderChatHistory()
    }

    private fun syncChatIfChanged() {
        if (currentChatId <= 0L || !chatStore.chatExists(currentChatId)) return

        val messages = chatStore.messages(currentChatId)
        if (messages.size != renderedMessageCount) {
            loadChat(currentChatId, showGreetingWhenEmpty = true)
        }
    }

    private fun renderChatHistory() {
        if (!::chatStore.isInitialized) return

        binding.chatHistoryList.removeAllViews()
        val chats = chatStore.listChats()

        if (chats.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No saved chats yet"
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        R.color.text_muted
                    )
                )
                textSize = 13f
                setPadding(dp(12), dp(18), dp(12), dp(18))
            }
            binding.chatHistoryList.addView(empty)
            return
        }

        chats.forEach { chat ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }

            val openButton = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (chat.id == currentChatId) {
                    "• " + chat.title
                } else {
                    chat.title
                }
                textAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener {
                    loadChat(
                        chat.id,
                        showGreetingWhenEmpty = true
                    )
                    binding.drawerLayout.closeDrawer(Gravity.LEFT)
                }
                setOnLongClickListener {
                    showRenameChat(chat)
                    true
                }
            }

            val deleteButton = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonTextStyle
            ).apply {
                text = "×"
                textSize = 22f
                contentDescription = "Delete chat"
                minWidth = dp(46)
                layoutParams = LinearLayout.LayoutParams(
                    dp(52),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    confirmDeleteChat(chat)
                }
            }

            row.addView(openButton)
            row.addView(deleteButton)
            binding.chatHistoryList.addView(row)
        }
    }

    private fun confirmDeleteChat(chat: PrimeChatSummary) {
        AlertDialog.Builder(this)
            .setTitle("Delete chat?")
            .setMessage(chat.title)
            .setPositiveButton("Delete") { _, _ ->
                val deletingCurrent = chat.id == currentChatId
                chatStore.deleteChat(chat.id)

                if (deletingCurrent) {
                    val remaining = chatStore.listChats()
                    if (remaining.isEmpty()) {
                        val next = chatStore.createChat()
                        loadChat(
                            next,
                            showGreetingWhenEmpty = true
                        )
                    } else {
                        loadChat(
                            remaining.first().id,
                            showGreetingWhenEmpty = true
                        )
                    }
                } else {
                    renderChatHistory()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteAllChats() {
        AlertDialog.Builder(this)
            .setTitle("Delete all chats?")
            .setMessage(
                "همه چت‌های ذخیره‌شده روی این گوشی حذف می‌شوند. ورود ChatGPT و تنظیمات PRIME پاک نمی‌شوند."
            )
            .setPositiveButton("Delete all") { _, _ ->
                chatStore.deleteAllChats()
                val chatId = chatStore.createChat()
                loadChat(
                    chatId,
                    showGreetingWhenEmpty = true
                )
                binding.drawerLayout.closeDrawer(Gravity.RIGHT)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameChat(chat: PrimeChatSummary) {
        val input = android.widget.EditText(this).apply {
            setText(chat.title)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename chat")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                chatStore.renameChat(
                    chat.id,
                    input.text?.toString().orEmpty()
                )
                renderChatHistory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun showAboutPrime() {
        AlertDialog.Builder(this)
            .setTitle("PRIME P6")
            .setMessage(
                "Version ${BuildConfig.VERSION_NAME}\n\n" +
                    "PRIME is a private Android action assistant. " +
                    "P6 is the PRIME product identity.\n\n" +
                    "Chats are saved locally on this phone. " +
                    "Phone control requires Accessibility and persistent Voice requires Display over other apps."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun sendCurrentMessage() {
        val text = binding.etMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || isBusy) return
        binding.etMessage.setText("")
        handleInput(text, fromVoiceMode = false)
    }

    private fun sendVoiceMessage() {
        val text = binding.etVoiceMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || isBusy) return

        textToSpeech?.stop()
        speechRecognizer?.cancel()
        binding.etVoiceMessage.setText("")
        handleInput(text, fromVoiceMode = true)
    }

    private fun handleInput(
        input: String,
        fromVoiceMode: Boolean = voiceModeActive
    ) {
        if (fromVoiceMode) {
            binding.tvVoiceStatus.text = "You: " + input.take(120)
        } else {
            appendChat("شما", input)
        }

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
                    if (fromVoiceMode) {
                        binding.tvVoiceStatus.text = "Cancelled"
                        speak("لغو شد.")
                    } else {
                        appendChat("PRIME", "لغو شد.")
                    }
                    return
                }
                else -> pendingConfirmationTask = null
            }
        }

        setBusy(true, "P6 is working…")
        if (fromVoiceMode) binding.tvVoiceStatus.text = "Thinking…"

        appScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    primeAgent.run(
                        userText = task,
                        confirmedForTask = confirmed,
                        uiProvider = { readUiState() },
                        actionRunner = { command, params ->
                            executePrimeAction(command, params)
                        },
                        onProgress = { message ->
                            runOnUiThread {
                                binding.tvAgentStatus.text = "P6 • $message"
                                if (fromVoiceMode) {
                                    binding.tvVoiceStatus.text = message
                                }
                            }
                        }
                    )
                }

                if (outcome.needsConfirmation) {
                    pendingConfirmationTask = task
                }

                if (fromVoiceMode) {
                    binding.tvVoiceStatus.text = "PRIME is speaking…"
                    speak(outcome.text)
                } else {
                    appendChat("PRIME", outcome.text)
                }
            } catch (e: Exception) {
                val message = userFriendlyError(e)
                if (fromVoiceMode) {
                    binding.tvVoiceStatus.text = "Connection issue • retry when ready"
                    speak(message)
                } else {
                    appendChat("PRIME", message)
                }
            } finally {
                setBusy(false)
            }
        }
    }

    private fun userFriendlyError(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("PRIME_API_DNS") ||
                message.contains("PRIME_NETWORK_DNS") ||
                message.contains("Unable to resolve host", ignoreCase = true) ->
                "اتصال PRIME به OpenAI از سمت DNS/VPN قطع شده. اگر VPN یا Split Tunnel داری، PRIME را هم داخل VPN فعال کن و دوباره امتحان کن."
            message.contains("PRIME_API_NETWORK") ||
                message.contains("unexpected end of stream", ignoreCase = true) ->
                "ارتباط PRIME با OpenAI لحظه‌ای قطع شد. برنامه چند بار خودکار تلاش کرد؛ دوباره امتحان کن."
            message.contains("usage limit", ignoreCase = true) ||
                message.contains("subscription_sharing_usage_limit", ignoreCase = true) ->
                "سهمیه فعلی ChatGPT به حدش رسیده. از Manage usage وضعیت مصرف را بررسی کن."
            message.contains("expired", ignoreCase = true) ->
                "اتصال ChatGPT نیاز به ورود دوباره دارد."
            message.isNotBlank() -> "خطا: $message"
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
                PrimeActionResult(
                    !state.startsWith("UI_ERROR") &&
                        !state.startsWith("ACCESSIBILITY_OFF"),
                    state.take(2500)
                )
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
            return@withContext PrimeActionResult(
                false,
                "Unsupported local action: $command"
            )
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
            PrimeActionResult(
                true,
                response.data?.toString()?.take(2500) ?: "OK"
            )
        } else {
            PrimeActionResult(false, response.error ?: "Action failed")
        }
    }

    private suspend fun openApp(
        params: JSONObject
    ): PrimeActionResult = withContext(Dispatchers.Main) {
        val explicitPackage = params.optString("package").takeIf { it.isNotBlank() }
        val requestedName = params.optString("name").takeIf { it.isNotBlank() }

        val packageName = explicitPackage ?: requestedName?.let { resolveAppPackage(it) }
        if (packageName.isNullOrBlank()) {
            return@withContext PrimeActionResult(false, "App not found")
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withContext PrimeActionResult(
                false,
                "App is not launchable: $packageName"
            )

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

        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(launcherIntent, 0)

        val exact = activities.firstOrNull {
            it.loadLabel(packageManager)
                .toString()
                .trim()
                .equals(name, ignoreCase = true)
        }
        if (exact != null) return exact.activityInfo.packageName

        return activities.firstOrNull {
            it.loadLabel(packageManager)
                .toString()
                .contains(name, ignoreCase = true)
        }?.activityInfo?.packageName
    }

    private suspend fun openUrl(
        params: JSONObject
    ): PrimeActionResult = withContext(Dispatchers.Main) {
        val raw = params.optString("url")
        if (raw.isBlank()) return@withContext PrimeActionResult(false, "URL is missing")

        val uri = Uri.parse(raw)
        if (uri.scheme !in listOf("http", "https")) {
            return@withContext PrimeActionResult(
                false,
                "Only http/https URLs are allowed"
            )
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            PrimeActionResult(true, "Opened $raw")
        } catch (e: Exception) {
            PrimeActionResult(false, e.message ?: "Could not open URL")
        }
    }

    private fun setupTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.setSpeechRate(1.0f)
                textToSpeech?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onError(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (voiceModeActive) {
                                runOnUiThread {
                                    appScope.launch {
                                        delay(350)
                                        if (voiceModeActive && !isBusy) {
                                            startVoiceInput(autoSend = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private fun speak(text: String) {
        val tts = textToSpeech ?: return
        val language = if (text.any { it.code in 0x0600..0x06FF }) {
            Locale("fa", "IR")
        } else {
            Locale.getDefault()
        }

        val result = tts.setLanguage(language)
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            tts.setLanguage(Locale.getDefault())
        }

        tts.speak(
            text.take(2500),
            TextToSpeech.QUEUE_FLUSH,
            null,
            "prime-p6-" + System.currentTimeMillis()
        )
    }

    private fun enterVoiceMode() {
        if (!authManager.isSignedIn()) {
            appendChat("PRIME", "اول ChatGPT را از منوی کناری وصل کن.")
            binding.drawerLayout.openDrawer(GravityCompat.START)
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartPersistentVoice = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            pendingStartPersistentVoice = true
            AlertDialog.Builder(this)
                .setTitle("اجازه PRIME Voice")
                .setMessage(
                    "برای اینکه Voice Mode هنگام رفتن داخل تلگرام، سایت یا هر برنامه‌ای روی صفحه بماند، " +
                        "یک‌بار اجازه «Display over other apps» را برای PRIME فعال کن."
                )
                .setPositiveButton("فعال کردن") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("لغو", null)
                .show()
            return
        }

        launchPersistentVoiceOverlay()
    }

    private fun launchPersistentVoiceOverlay() {
        pendingStartPersistentVoice = false
        binding.voiceOverlay.visibility = View.GONE

        val intent = Intent(
            this,
            VoiceSessionForegroundService::class.java
        ).apply {
            action = VoiceSessionForegroundService.ACTION_START
        }

        startForegroundService(intent)
        binding.tvAgentStatus.text = "Persistent PRIME Voice active"
    }

    private fun exitVoiceMode() {
        voiceModeActive = false
        voiceAutoSend = false
        pendingStartPersistentVoice = false

        val intent = Intent(
            this,
            VoiceSessionForegroundService::class.java
        ).apply {
            action = VoiceSessionForegroundService.ACTION_STOP
        }

        startService(intent)
        speechRecognizer?.cancel()
        textToSpeech?.stop()
        binding.voiceOverlay.visibility = View.GONE
        binding.tvAgentStatus.text = "Ready"
    }

    private fun startVoiceInput(autoSend: Boolean) {
        if (isBusy) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingVoiceAutoSend = autoSend
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            appendChat("PRIME", "تشخیص صدا روی این گوشی در دسترس نیست.")
            if (voiceModeActive) binding.tvVoiceStatus.text = "Speech recognition unavailable"
            return
        }

        voiceAutoSend = autoSend
        if (voiceModeActive && autoSend) {
            ensureVoiceForegroundService()
        }
        ensureSpeechRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault().toLanguageTag()
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun ensureVoiceForegroundService() {
        try {
            startForegroundService(
                Intent(this, VoiceSessionForegroundService::class.java)
            )
        } catch (_: Exception) {
            // PRIME can still listen while it is foreground. Some Android
            // builds may block microphone FGS until all device permissions
            // are fully granted.
        }
    }

    private fun ensureSpeechRecognizer() {
        if (speechRecognizer != null) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    binding.tvAgentStatus.text = "Listening…"
                    if (voiceModeActive) binding.tvVoiceStatus.text = "Listening…"
                }

                override fun onBeginningOfSpeech() {
                    binding.tvAgentStatus.text = "Listening…"
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    binding.tvAgentStatus.text = "Understanding…"
                    if (voiceModeActive) binding.tvVoiceStatus.text = "Understanding…"
                }

                override fun onError(error: Int) {
                    binding.tvAgentStatus.text = "Ready"
                    if (voiceModeActive) {
                        binding.tvVoiceStatus.text = "Listening…"
                        appScope.launch {
                            delay(700)
                            if (voiceModeActive && !isBusy) {
                                startVoiceInput(autoSend = true)
                            }
                        }
                    } else if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        appendChat("PRIME", "صدای واضحی دریافت نشد. دوباره امتحان کن.")
                    }
                }

                override fun onResults(results: Bundle?) {
                    binding.tvAgentStatus.text = "Ready"
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    if (spoken.isBlank()) {
                        if (voiceModeActive) {
                            appScope.launch {
                                delay(500)
                                startVoiceInput(autoSend = true)
                            }
                        }
                        return
                    }

                    if (voiceAutoSend) {
                        if (voiceModeActive) binding.tvVoiceStatus.text = "You: " + spoken.take(120)
                        handleInput(spoken, fromVoiceMode = true)
                    } else {
                        binding.etMessage.setText(spoken)
                        binding.etMessage.setSelection(spoken.length)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (
            requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            if (pendingStartPersistentVoice) {
                enterVoiceMode()
            } else {
                startVoiceInput(autoSend = pendingVoiceAutoSend)
            }
        }
    }

    private fun updateComposerButtons() {
        val hasText = !binding.etMessage.text.isNullOrBlank()
        binding.btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
        binding.btnVoice.visibility = if (hasText) View.GONE else View.VISIBLE
    }

    private fun appendChat(
        who: String,
        message: String,
        persist: Boolean = true
    ) {
        if (message.isBlank()) return

        val isUser = who == "شما"

        if (
            persist &&
            currentChatId > 0L &&
            chatStore.chatExists(currentChatId)
        ) {
            chatStore.appendMessage(
                currentChatId,
                if (isUser) "user" else "assistant",
                message
            )
            renderedMessageCount += 1
            if (isUser) renderChatHistory()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        }

        val messageView = TextView(this).apply {
            text = if (isUser) message.trim() else "PRIME\n" + message.trim()
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            setPadding(
                if (isUser) dp(14) else 0,
                if (isUser) dp(10) else 0,
                if (isUser) dp(14) else 0,
                if (isUser) dp(10) else 0
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            if (isUser) {
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    R.drawable.user_message_bg
                )
            }
            if (message.any { it.code in 0x0600..0x06FF }) {
                textDirection = View.TEXT_DIRECTION_RTL
            }
        }

        row.addView(messageView)
        binding.chatMessages.addView(row)
        binding.chatScrollView.post {
            binding.chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showQuickActions() {
        val options = arrayOf(
            if (authManager.isSignedIn()) "ChatGPT account" else "Connect ChatGPT",
            "Phone control",
            "Android settings",
            "Device Bridge"
        )

        AlertDialog.Builder(this)
            .setTitle("PRIME actions")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (authManager.isSignedIn()) {
                            binding.drawerLayout.openDrawer(GravityCompat.START)
                        } else {
                            connectChatGpt()
                        }
                    }
                    1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    2 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                    3 -> binding.drawerLayout.openDrawer(GravityCompat.START)
                }
            }
            .show()
    }

    private fun setBusy(busy: Boolean, status: String? = null) {
        isBusy = busy
        binding.btnSend.isEnabled = !busy
        binding.btnMic.isEnabled = !busy
        binding.btnVoice.isEnabled = !busy
        binding.btnVoiceMic.isEnabled = !busy
        binding.btnVoiceSend.isEnabled = !busy
        binding.etVoiceMessage.isEnabled = !busy
        binding.btnSignIn.isEnabled = !busy
        binding.btnSignOut.isEnabled = !busy
        binding.tvAgentStatus.text = status ?: if (busy) "P6 is working…" else "Ready"
    }

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
                    runOnUiThread {
                        binding.tvConnections.text = count.toString()
                    }
                }
            )
            wsServer?.start()

            val serviceIntent = Intent(
                this,
                ConnectionForegroundService::class.java
            ).apply {
                putExtra(ConnectionForegroundService.EXTRA_PORT, port)
            }
            startForegroundService(serviceIntent)

            isServerRunning = true
            updateServerUI()
            appendLog("PRIME Device Bridge started on port $port")
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
            indicator.setColor(
                ContextCompat.getColor(this, R.color.status_connected)
            )
            binding.tvAccessibilityStatus.text = "ON"
        } else {
            indicator.setColor(
                ContextCompat.getColor(this, R.color.status_disconnected)
            )
            binding.tvAccessibilityStatus.text = "OFF"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        ).any {
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
            val wifiManager = applicationContext.getSystemService(
                Context.WIFI_SERVICE
            ) as WifiManager
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
        binding.tvLog.append("[$timestamp] $message\n")
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(View.FOCUS_DOWN)
        }

        val text = binding.tvLog.text.toString()
        if (text.length > 10_000) {
            binding.tvLog.text = text.takeLast(5_000)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (isServerRunning) stopServer()
        voiceModeActive = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        appScope.cancel()
        super.onDestroy()
    }
}
