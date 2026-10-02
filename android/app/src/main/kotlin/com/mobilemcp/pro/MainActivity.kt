package com.mobilemcp.pro

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
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
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import android.view.inputmethod.EditorInfo
import com.mobilemcp.pro.voice.PersianSpeech
import com.mobilemcp.pro.voice.PersianVoicePack
import com.mobilemcp.pro.voice.VoicePreferences
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.mobilemcp.pro.databinding.ActivityMainBinding
import com.mobilemcp.pro.server.WebSocketCommandServer
import com.mobilemcp.pro.server.RemoteBridgeClient
import com.mobilemcp.pro.service.ConnectionForegroundService
import com.mobilemcp.pro.service.RemoteBridgeForegroundService
import com.mobilemcp.pro.service.MobileAccessibilityService
import com.mobilemcp.pro.service.VoiceSessionForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    private lateinit var bridgeSecurity: DeviceBridgeSecurity
    private lateinit var remoteBridgeSecurity: RemoteBridgeSecurity
    private var bridgeAuthToken: String = ""
    private var currentChatId: Long = -1L
    private var renderedMessageCount: Int = 0

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var voiceSpeech: PersianSpeech? = null
    private var pendingSpeech: String? = null
    private var voiceDownload: Job? = null
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
        bridgeSecurity = DeviceBridgeSecurity(SecureStore(applicationContext))
        bridgeAuthToken = bridgeSecurity.getOrCreateToken()
        remoteBridgeSecurity = RemoteBridgeSecurity(SecureStore(applicationContext))

        setupTextToSpeech()
        setupUI()
        updateAccessibilityStatus()
        updateAuthUI()
        openInitialChat()
        warmUpPrime()

        if (intent?.data?.scheme == "primep6") {
            binding.tvAgentStatus.text = "در حال تکمیل اتصال ChatGPT…"
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.data?.scheme == "primep6" &&
            intent.data?.host == "auth-complete"
        ) {
            binding.drawerLayout.closeDrawers()
            binding.tvAgentStatus.text = "در حال تکمیل اتصال ChatGPT…"
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

            // Samsung/Android 14 can briefly report the Activity resumed while
            // the special-permission screen is still transitioning away.
            // Start the microphone FGS only after the PRIME window is stably
            // visible again.
            binding.root.postDelayed(
                { launchPersistentVoiceOverlay() },
                650L
            )
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
        binding.btnVoiceSettings.setOnClickListener { showVoiceSettings() }
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
            voiceSpeech?.stop()
            speechRecognizer?.cancel()
            startVoiceInput(autoSend = true)
        }
        binding.btnVoiceSend.setOnClickListener { sendVoiceMessage() }
        binding.btnPlus.setOnClickListener { showQuickActions() }

        binding.etMessage.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { sendCurrentMessage(); true } else false
        }
        binding.etVoiceMessage.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { sendVoiceMessage(); true } else false
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
        binding.btnPairRemote.setOnClickListener { pairRemoteBridge() }
        binding.btnStopRemote.setOnClickListener { stopRemoteBridge() }
        binding.tvBridgeToken.text = bridgeAuthToken
        binding.btnCopyBridgeToken.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    getString(R.string.ui_bridge_token_label),
                    bridgeAuthToken
                )
            )
            Toast.makeText(
                this,
                R.string.ui_bridge_token_copied,
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.tvAppVersion.text = "P6 • PRIME " + appVersionName()
        updateComposerButtons()
        updateIPAddress()
        updateServerUI()
        updateRemoteBridgeUI()
        restoreRemoteBridge()
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

                val label = profile.email ?: profile.name ?: "حساب ChatGPT"
                appendChat("PRIME", "اکانت ChatGPT وصل شد: $label", persist = false)
                speak("اتصال انجام شد. پرایم آماده است.")
            } catch (e: Exception) {
                val message = authFriendlyError(e)
                appendChat("PRIME", message, persist = false)
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
                    "اتصال شبکه PRIME به OpenAI سالم است.\n$authResult\n$apiResult",
                    persist = false
                )
            } catch (e: Exception) {
                appendChat("PRIME", authFriendlyError(e), persist = false)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun disconnectChatGpt() {
        if (isBusy) return
        stopService(Intent(this, VoiceSessionForegroundService::class.java))
        voiceSpeech?.stop()
        setBusy(true, "در حال قطع اتصال…")
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
                else "ورود محلی پاک شد. برای قطع کامل دسترسی می‌توانی از تنظیمات ChatGPT هم PRIME را Disconnect کنی.",
                persist = false
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
        if (isBusy) return
        val chatId = chatStore.createChat()
        loadChat(chatId, showGreetingWhenEmpty = false)
        renderChatHistory()
        binding.drawerLayout.closeDrawer(Gravity.LEFT)
    }

    private fun loadChat(
        chatId: Long,
        showGreetingWhenEmpty: Boolean = true
    ) {
        if (isBusy || !chatStore.chatExists(chatId)) return

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
        binding.tvAgentStatus.text = "آماده"

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
                isAllCaps = false
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

            val deleteButton = MaterialButton(this).apply {
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
        if (isBusy) return
        AlertDialog.Builder(this)
            .setTitle("این گفت‌وگو حذف شود؟")
            .setMessage(chat.title)
            .setPositiveButton("حذف") { _, _ ->
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
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun confirmDeleteAllChats() {
        if (isBusy) return
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
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun showRenameChat(chat: PrimeChatSummary) {
        val input = android.widget.EditText(this).apply {
            setText(chat.title)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("تغییر نام گفت‌وگو")
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                chatStore.renameChat(
                    chat.id,
                    input.text?.toString().orEmpty()
                )
                renderChatHistory()
            }
            .setNegativeButton("لغو", null)
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

    private fun appVersionName(): String {
        return try {
            packageManager
                .getPackageInfo(packageName, 0)
                .versionName
                ?: "6.0.3"
        } catch (_: Exception) {
            "6.0.3"
        }
    }

    private fun showAboutPrime() {
        AlertDialog.Builder(this)
            .setTitle("PRIME P6")
            .setMessage(
                "Version ${appVersionName()}\n\n" +
                    "PRIME is a private Android action assistant. " +
                    "P6 is the PRIME product identity.\n\n" +
                    "Chats are saved locally on this phone. " +
                    "Phone control requires Accessibility and persistent Voice requires Display over other apps."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun sendCurrentMessage() {
        if (VoiceSessionForegroundService.isOverlayRunning) {
            binding.tvAgentStatus.text = "برای چت متنی، ابتدا پنجرهٔ Voice را ببند."
            return
        }
        val text = binding.etMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || isBusy) return
        binding.etMessage.setText("")
        handleInput(text, fromVoiceMode = false)
    }

    private fun sendVoiceMessage() {
        val text = binding.etVoiceMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || isBusy) return

        voiceSpeech?.stop()
        speechRecognizer?.cancel()
        binding.etVoiceMessage.setText("")
        handleInput(text, fromVoiceMode = true)
    }

    private fun handleInput(
        input: String,
        fromVoiceMode: Boolean = voiceModeActive
    ) {
        if (isBusy) return
        if (VoiceSessionForegroundService.isOverlayRunning) {
            binding.tvAgentStatus.text = "برای چت متنی، ابتدا پنجرهٔ Voice را ببند."
            return
        }
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
                        binding.tvVoiceStatus.text = "لغو شد"
                        speak("لغو شد.")
                    } else {
                        appendChat("PRIME", "لغو شد.")
                    }
                    return
                }
                else -> pendingConfirmationTask = null
            }
        }

        setBusy(true, "پرایم در حال انجام درخواست است…")
        if (fromVoiceMode) binding.tvVoiceStatus.text = "در حال آماده‌کردن پاسخ…"

        appScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    primeAgent.run(
                        userText = task,
                        confirmedForTask = confirmed,
                        uiProvider = { readUiState(task) },
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
                    appendChat("PRIME", outcome.text)
                    binding.tvVoiceStatus.text = "پرایم در حال صحبت است…"
                    speak(outcome.text)
                } else {
                    appendChat("PRIME", outcome.text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = userFriendlyError(e)
                if (fromVoiceMode) {
                    binding.tvVoiceStatus.text = "اتصال قطع شد · دوباره تلاش کن"
                    speak(message)
                } else {
                    appendChat("PRIME", message, persist = false)
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

    private fun isPositiveConfirmation(value: String) = PersianInput.isPositiveConfirmation(value)
    private fun isNegativeConfirmation(value: String) = PersianInput.isNegativeConfirmation(value)

    private val phoneController by lazy { PhoneController(this) }

    private suspend fun readUiState(task: String = ""): String = phoneController.readUiState(task)

    private suspend fun executePrimeAction(command: String, params: JSONObject): PrimeActionResult =
        phoneController.execute(command, params)

    private fun setupTextToSpeech() {
        voiceSpeech = PersianSpeech(applicationContext, object : PersianSpeech.Listener {
            override fun onReady() {
                pendingSpeech?.let { text -> pendingSpeech = null; voiceSpeech?.speak(text) }
            }
            override fun onDone() {
                binding.tvAgentStatus.text = "صدای فارسی آماده است"
                if (voiceModeActive && !isBusy) startVoiceInput(autoSend = true)
            }
            override fun onError(message: String) {
                pendingSpeech = null
                binding.tvAgentStatus.text = message
            }
        })
    }

    private fun speak(text: String) {
        speechRecognizer?.cancel()
        pendingSpeech = text
        voiceSpeech?.prepare()
    }

    private fun prepareVoicePack(after: () -> Unit) {
        if (voiceDownload?.isActive == true) return
        if (PersianVoicePack.isInstalled(this)) { after(); return }
        val dialog = AlertDialog.Builder(this)
            .setTitle("نصب صدای فارسی PRIME")
            .setMessage("بستهٔ صدا یک‌بار دانلود می‌شود (۶۴ مگابایت). پس از نصب، پخش فارسی روی خود گوشی انجام می‌شود.")
            .setNegativeButton("لغو") { _, _ -> voiceDownload?.cancel() }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { voiceDownload?.cancel() }
        dialog.show()
        voiceDownload = appScope.launch {
            try {
                PersianVoicePack.ensureInstalled(applicationContext) { progress ->
                    runOnUiThread { dialog.setMessage(progress) }
                }
                dialog.dismiss()
                after()
            } catch (e: CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                AlertDialog.Builder(this@MainActivity).setTitle("دانلود صدا کامل نشد")
                    .setMessage(e.message ?: "اتصال اینترنت را بررسی کن و دوباره تلاش کن.")
                    .setPositiveButton("تلاش دوباره") { _, _ -> prepareVoicePack(after) }
                    .setNegativeButton("بستن", null).show()
            }
        }
    }

    private fun showVoiceSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        panel.addView(TextView(this).apply {
            text = if (PersianVoicePack.isInstalled(this@MainActivity))
                "صدای فارسی نصب است · پخش آفلاین\nتشخیص گفتار ممکن است به اینترنت نیاز داشته باشد."
            else "صدای فارسی آمادهٔ دانلود است · ۶۴ مگابایت"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        })
        val language = MaterialButton(this).apply {
            text = "زبان ورودی: " + if (VoicePreferences.language(this@MainActivity) == "fa-IR") "فارسی" else "English"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("زبان تشخیص گفتار")
                    .setSingleChoiceItems(arrayOf("فارسی", "English"),
                        if (VoicePreferences.language(this@MainActivity) == "fa-IR") 0 else 1) { dialog, which ->
                        VoicePreferences.setLanguage(this@MainActivity, if (which == 0) "fa-IR" else "en-US")
                        text = "زبان ورودی: " + if (which == 0) "فارسی" else "English"
                        dialog.dismiss()
                    }.show()
            }
        }
        panel.addView(language)
        panel.addView(TextView(this).apply { text = "سرعت گفتار" })
        panel.addView(SeekBar(this).apply {
            max = 55
            progress = ((VoicePreferences.speed(this@MainActivity) - 0.75f) * 100).toInt()
            contentDescription = "سرعت صدای فارسی"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    if (user) VoicePreferences.setSpeed(this@MainActivity, 0.75f + value / 100f)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        AlertDialog.Builder(this).setTitle("صدای فارسی PRIME").setView(panel)
            .setPositiveButton("آزمایش صدا") { _, _ ->
                if (VoiceSessionForegroundService.isOverlayRunning) {
                    binding.tvAgentStatus.text = "برای آزمایش صدا ابتدا Voice را ببند."
                } else prepareVoicePack { speak("سلام، من پرایم هستم. صدای فارسی آماده است. چطور می‌توانم کمکت کنم؟") }
            }.setNegativeButton("بستن", null).show()
    }

    private fun enterVoiceMode() {
        if (!authManager.isSignedIn()) {
            appendChat("PRIME", "اول ChatGPT را از منوی کناری وصل کن.", persist = false)
            binding.drawerLayout.openDrawer(Gravity.RIGHT)
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

        prepareVoicePack { launchPersistentVoiceOverlay() }
    }

    private fun launchPersistentVoiceOverlay() {
        pendingSpeech = null
        voiceSpeech?.stop()
        if (!PersianVoicePack.isInstalled(this)) {
            prepareVoicePack { launchPersistentVoiceOverlay() }
            return
        }
        pendingStartPersistentVoice = false

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            appendChat(
                "PRIME",
                "برای Voice Mode دسترسی میکروفن لازم است.",
                persist = false
            )
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            appendChat(
                "PRIME",
                "برای Voice Mode اجازه Display over other apps لازم است.",
                persist = false
            )
            return
        }

        val intent = Intent(
            this,
            VoiceSessionForegroundService::class.java
        ).apply {
            action = VoiceSessionForegroundService.ACTION_START
        }

        try {
            startForegroundService(intent)
            binding.tvAgentStatus.text = "شروع گفت‌وگوی صوتی…"
        } catch (t: Throwable) {
            showVoiceStartFailure(
                t::class.java.simpleName + ": " +
                    (t.message ?: "Voice service could not start")
            )
            return
        }

        appScope.launch {
            delay(1_200)

            if (VoiceSessionForegroundService.isOverlayRunning) {
                binding.voiceOverlay.visibility = View.GONE
                binding.tvAgentStatus.text = "گفت‌وگوی صوتی فعال است"
                return@launch
            }

            val error = VoiceSessionForegroundService.lastStartError
                ?: getSharedPreferences(
                    "prime_voice_diagnostics",
                    Context.MODE_PRIVATE
                ).getString("last_error", null)

            showVoiceStartFailure(
                error ?: "PRIME Voice service stopped before the overlay opened."
            )
        }
    }

    private fun showVoiceStartFailure(details: String) {
        binding.tvAgentStatus.text = "Voice could not start"

        AlertDialog.Builder(this)
            .setTitle("PRIME Voice اجرا نشد")
            .setMessage(
                "PRIME بسته نمی‌شود؛ Voice Service روی این گوشی شروع نشده است.\n\n" +
                    "جزئیات: " + details.take(500) + "\n\n" +
                    "دسترسی Microphone و Display over other apps را روشن نگه دار. " +
                    "اگر دوباره تکرار شد همین پیام را برای من بفرست."
            )
            .setPositiveButton("باشه", null)
            .setNeutralButton("تنظیمات صدا") { _, _ -> showVoiceSettings() }
            .show()
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
        voiceSpeech?.stop()
        binding.voiceOverlay.visibility = View.GONE
        binding.tvAgentStatus.text = "آماده"
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
            appendChat("PRIME", "تشخیص صدا روی این گوشی در دسترس نیست.", persist = false)
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
                VoicePreferences.language(this@MainActivity)
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { speechRecognizer?.startListening(intent) }
        catch (e: Exception) { binding.tvAgentStatus.text = "تشخیص گفتار شروع نشد؛ دوباره تلاش کن." }
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
                    binding.tvAgentStatus.text = "در حال گوش دادن…"
                    if (voiceModeActive) binding.tvVoiceStatus.text = "در حال گوش دادن…"
                }

                override fun onBeginningOfSpeech() {
                    binding.tvAgentStatus.text = "در حال گوش دادن…"
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    binding.tvAgentStatus.text = "در حال تشخیص گفتار…"
                    if (voiceModeActive) binding.tvVoiceStatus.text = "در حال تشخیص گفتار…"
                }

                override fun onError(error: Int) {
                    binding.tvAgentStatus.text = "آماده"
                    if (voiceModeActive) {
                        binding.tvVoiceStatus.text = "در حال گوش دادن…"
                        appScope.launch {
                            delay(700)
                            if (voiceModeActive && !isBusy) {
                                startVoiceInput(autoSend = true)
                            }
                        }
                    } else if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        appendChat("PRIME", "صدای واضحی دریافت نشد. دوباره امتحان کن.", persist = false)
                    }
                }

                override fun onResults(results: Bundle?) {
                    binding.tvAgentStatus.text = "آماده"
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
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
            setPadding(
                dp(14), dp(12), dp(14), dp(12)
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            if (isUser) {
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    R.drawable.user_message_bg
                )
            } else {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.chat_bg)
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
            if (authManager.isSignedIn()) "حساب ChatGPT" else "اتصال ChatGPT",
            "کنترل گوشی",
            "تنظیمات گوشی",
            "پل اتصال دستگاه"
        )

        AlertDialog.Builder(this)
            .setTitle("ابزارهای PRIME")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (authManager.isSignedIn()) {
                            binding.drawerLayout.openDrawer(Gravity.RIGHT)
                        } else {
                            connectChatGpt()
                        }
                    }
                    1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    2 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                    3 -> binding.drawerLayout.openDrawer(Gravity.RIGHT)
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
        binding.btnCreateNewChat.isEnabled = !busy
        binding.btnDeleteAllChats.isEnabled = !busy
        binding.tvAgentStatus.text = status ?: if (busy) "پرایم در حال انجام درخواست است…" else "آماده"
    }

    private fun pairRemoteBridge() {
        val code = binding.etRemotePairingCode.text?.toString()?.trim().orEmpty()
        if (!code.matches(Regex("\\d{6}"))) {
            binding.etRemotePairingCode.error = "کد اتصال باید ۶ رقم باشد"
            return
        }

        binding.btnPairRemote.isEnabled = false
        binding.tvRemoteStatus.setText(R.string.ui_remote_pairing)

        appScope.launch {
            try {
                val credential = withContext(Dispatchers.IO) {
                    RemoteBridgeClient.pair(
                        RemoteBridgeSecurity.DEFAULT_RELAY_URL,
                        code,
                        remoteBridgeSecurity.getOrCreateDeviceId()
                    )
                }
                remoteBridgeSecurity.saveCredential(credential)
                remoteBridgeSecurity.setEnabled(true)
                binding.etRemotePairingCode.setText("")
                startRemoteBridgeService()
                updateRemoteBridgeUI()
                appendLog("PRIME remote bridge paired and started")
            } catch (e: Exception) {
                remoteBridgeSecurity.setEnabled(false)
                binding.tvRemoteStatus.text =
                    "اتصال راه دور کامل نشد: " + (e.message ?: "خطای نامشخص")
                appendLog("Remote bridge pairing failed: " + (e.message ?: "unknown"))
            } finally {
                binding.btnPairRemote.isEnabled = true
            }
        }
    }

    private fun startRemoteBridgeService() {
        val credential = remoteBridgeSecurity.credential() ?: return
        val intent = Intent(this, RemoteBridgeForegroundService::class.java).apply {
            action = RemoteBridgeForegroundService.ACTION_START
            putExtra(
                RemoteBridgeForegroundService.EXTRA_RELAY_URL,
                RemoteBridgeSecurity.DEFAULT_RELAY_URL
            )
            putExtra(
                RemoteBridgeForegroundService.EXTRA_CREDENTIAL,
                credential
            )
        }
        startForegroundService(intent)
    }

    private fun stopRemoteBridge() {
        remoteBridgeSecurity.setEnabled(false)
        startService(
            Intent(this, RemoteBridgeForegroundService::class.java).apply {
                action = RemoteBridgeForegroundService.ACTION_STOP
            }
        )
        updateRemoteBridgeUI()
        appendLog("PRIME remote bridge stopped")
    }

    private fun restoreRemoteBridge() {
        if (remoteBridgeSecurity.isEnabled() && remoteBridgeSecurity.credential() != null) {
            startRemoteBridgeService()
        }
    }

    private fun updateRemoteBridgeUI() {
        binding.tvRemoteStatus.setText(
            if (remoteBridgeSecurity.isEnabled()) {
                R.string.ui_remote_on
            } else {
                R.string.ui_remote_off
            }
        )
    }

    private fun startServer() {
        if (!MobileAccessibilityService.isRunning) {
            appendLog("ERROR: Accessibility Service is not enabled")
            return
        }

        val port = binding.etPort.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            binding.etPort.error = "پورت باید بین ۱۰۲۴ و ۶۵۵۳۵ باشد"
            return
        }
        try {
            wsServer = WebSocketCommandServer(
                port = port,
                authToken = bridgeAuthToken,
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
            binding.tvAccessibilityStatus.text = "فعال"
        } else {
            indicator.setColor(
                ContextCompat.getColor(this, R.color.status_disconnected)
            )
            binding.tvAccessibilityStatus.text = "غیرفعال"
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
        voiceSpeech?.stop()
        voiceSpeech?.close()
        voiceSpeech = null
        appScope.cancel()
        chatStore.close()
        super.onDestroy()
    }
}
