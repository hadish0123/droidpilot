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
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
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
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.CoroutineStart
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
    private lateinit var memoryStore: PrimeMemoryStore
    private lateinit var providerStore: PrimeProviderStore
    private lateinit var observabilityStore: PrimeObservabilityStore
    private lateinit var costStore: PrimeCostStore
    private lateinit var mcpServerStore: PrimeMcpServerStore
    private lateinit var pluginManager: PrimePluginManager
    private lateinit var pluginManifestLoader: PrimePluginManifestLoader
    private lateinit var taskStore: PrimeTaskStore
    private lateinit var taskScheduler: PrimeTaskScheduler
    private lateinit var mcpClient: PrimeMcpClient
    private lateinit var mcpToolSource: PrimeMcpToolSource
    private val mcpDiscoveryCache =
        mutableMapOf<String, PrimeMcpDiscovery>()
    private val attachmentSession = PrimeAttachmentSession()
    private val imageSession = PrimeImageSession()
    private val imageLoader by lazy {
        PrimeImageLoader(
            applicationContext
        )
    }
    private val documentPipeline by lazy {
        PrimeDocumentPipeline(applicationContext)
    }
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) ingestAttachment(uri)
    }
    private val imagePicker =
        registerForActivityResult(
            ActivityResultContracts
                .OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) {
                ingestImages(
                    uris.take(4)
                )
            }
        }
    private val pluginManifestPicker =
        registerForActivityResult(
            ActivityResultContracts
                .OpenDocument()
        ) { uri ->
            if (uri != null) {
                ingestPluginManifest(
                    uri
                )
            }
        }
    private val chatBackupExportPicker =
        registerForActivityResult(
            ActivityResultContracts
                .CreateDocument(
                    "application/json"
                )
        ) { uri ->
            if (uri != null) {
                exportChatBackup(uri)
            }
        }
    private val chatBackupImportPicker =
        registerForActivityResult(
            ActivityResultContracts
                .OpenDocument()
        ) { uri ->
            if (uri != null) {
                importChatBackup(uri)
            }
        }
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
    private var activeGenerationJob: Job? = null

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
        memoryStore = PrimeMemoryStore(applicationContext)
        providerStore = PrimeProviderStore(applicationContext)
        observabilityStore = PrimeObservabilityStore(applicationContext)
        costStore = PrimeCostStore(applicationContext)
        mcpServerStore = PrimeMcpServerStore(applicationContext)
        pluginManager = PrimePluginManager(
            context = applicationContext,
            mcpStore = mcpServerStore
        )
        pluginManifestLoader =
            PrimePluginManifestLoader(
                applicationContext
            )
        taskStore = PrimeTaskStore(
            applicationContext
        )
        taskScheduler =
            PrimeTaskScheduler(
                applicationContext
            )
        mcpClient = PrimeMcpClient(
            clientVersion = appVersionName()
        )
        mcpToolSource = PrimeMcpToolSource(
            mcpServerStore,
            mcpClient
        )
        primeAgent = buildPrimeAgent()
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
        warmUpMcpTools()

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
        binding.btnStop.setOnClickListener { stopGeneration() }
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
        binding.btnCopyRemoteMcp.setOnClickListener {
            val mcpUrl = remoteBridgeSecurity.mcpUrl() ?: return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.ui_remote_mcp_url), mcpUrl)
            )
            Toast.makeText(
                this,
                R.string.ui_remote_mcp_copied,
                Toast.LENGTH_SHORT
            ).show()
        }
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
        val savedChat =
            saved.takeIf { it > 0L }
                ?.let(chatStore::chat)
        val target = when {
            savedChat != null &&
                !savedChat.isArchived ->
                savedChat.id
            chats.isNotEmpty() ->
                chats.first().id
            else ->
                chatStore.createChat()
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
        attachmentSession.clear()
        imageSession.clear()
        binding.chatMessages.removeAllViews()
        binding.etMessage.setText("")
        binding.tvAgentStatus.text = "آماده"

        val messages = chatStore.messages(chatId)
        primeAgent.restoreConversation(
            messages.map { it.role to it.content }
        )

        renderedMessageCount = messages.size
        messages.forEach { stored ->
            val bubble = addChatBubble(
                isUser = stored.role == "user",
                message = stored.content
            )
            bubble.setOnLongClickListener {
                showMessageActions(stored)
                true
            }
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
        rehydrateChatAttachments(chatId)
    }

    private fun rehydrateChatAttachments(
        chatId: Long
    ) {
        val stored = chatStore.attachments(chatId)
        if (stored.isEmpty()) return

        appScope.launch {
            var restored = 0
            var unavailable = 0

            stored.forEach { attachment ->
                if (currentChatId != chatId) return@launch

                try {
                    when (attachment.kind) {
                        "document" -> {
                            val uri = Uri.parse(
                                attachment.storageUri
                            )
                            val parsed = withContext(
                                Dispatchers.IO
                            ) {
                                documentPipeline.parse(uri)
                            }
                            if (currentChatId != chatId) return@launch
                            attachmentSession.add(
                                parsed,
                                persistedAttachmentId =
                                    attachment.id,
                                storageUri =
                                    attachment.storageUri
                            )
                            restored += 1
                        }

                        "image" -> {
                            val uri = Uri.parse(
                                attachment.storageUri
                            )
                            val pair = withContext(
                                Dispatchers.IO
                            ) {
                                imageLoader.load(uri)
                            }
                            if (currentChatId != chatId) return@launch
                            imageSession.add(
                                pair.first,
                                pair.second,
                                persistedAttachmentId =
                                    attachment.id,
                                storageUri =
                                    attachment.storageUri
                            )
                            restored += 1
                        }
                    }
                } catch (_: Exception) {
                    unavailable += 1
                }
            }

            if (
                currentChatId == chatId &&
                unavailable > 0
            ) {
                binding.tvAgentStatus.text =
                    restored.toString() +
                        " پیوست بازیابی شد · " +
                        unavailable +
                        " پیوست در دسترس نیست"
            }
        }
    }

    private fun persistReadAccess(
        uri: Uri
    ): Boolean =
        runCatching {
            contentResolver
                .takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            true
        }.getOrDefault(false)

    private fun queryUriSize(
        uri: Uri
    ): Long? =
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (
                    cursor.moveToFirst() &&
                    !cursor.isNull(0)
                ) {
                    cursor.getLong(0)
                } else {
                    null
                }
            }
        }.getOrNull()

    private fun showMessageActions(
        message: PrimeStoredMessage
    ) {
        if (isBusy) return
        val options = if (message.role == "user") {
            arrayOf(
                "ویرایش و ادامه در شاخهٔ جدید",
                "شاخهٔ جدید از اینجا",
                "کپی متن"
            )
        } else {
            arrayOf(
                "تولید دوباره در شاخهٔ جدید",
                "شاخهٔ جدید از اینجا",
                "کپی متن"
            )
        }

        AlertDialog.Builder(this)
            .setTitle(if (message.role == "user") "پیام شما" else "پاسخ PRIME")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (message.role == "user") {
                        editAndBranchMessage(message)
                    } else {
                        regenerateInBranch(message)
                    }
                    1 -> branchThroughMessage(message)
                    2 -> copyMessageText(message.content)
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun editAndBranchMessage(
        message: PrimeStoredMessage
    ) {
        val input = android.widget.EditText(this).apply {
            setText(message.content)
            setSelection(text.length)
            minLines = 2
            maxLines = 8
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("ویرایش و ادامه در شاخهٔ جدید")
            .setMessage("گفت‌وگوی اصلی بدون تغییر حفظ می‌شود.")
            .setView(input)
            .setPositiveButton("ساخت شاخه", null)
            .setNegativeButton("لغو", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val edited = input.text?.toString()?.trim().orEmpty()
                    if (edited.isBlank()) return@setOnClickListener
                    val branchId = chatStore.forkBeforeMessage(
                        message.id,
                        " · edit"
                    )
                    dialog.dismiss()
                    loadChat(branchId, showGreetingWhenEmpty = false)
                    handleInput(edited, fromVoiceMode = false)
                }
        }
        dialog.show()
    }

    private fun regenerateInBranch(
        assistantMessage: PrimeStoredMessage
    ) {
        val messages = chatStore.messages(assistantMessage.chatId)
        val user = messages
            .takeWhile { it.id < assistantMessage.id }
            .lastOrNull { it.role == "user" }
            ?: return

        val branchId = chatStore.forkBeforeMessage(
            user.id,
            " · regenerate"
        )
        loadChat(branchId, showGreetingWhenEmpty = false)
        handleInput(user.content, fromVoiceMode = false)
    }

    private fun branchThroughMessage(
        message: PrimeStoredMessage
    ) {
        val branchId = chatStore.forkThroughMessage(
            message.id
        )
        loadChat(branchId, showGreetingWhenEmpty = false)
        renderChatHistory()
        Toast.makeText(
            this,
            "شاخهٔ جدید ساخته شد",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun copyMessageText(text: String) {
        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("PRIME message", text)
        )
        binding.tvAgentStatus.text = "متن کپی شد"
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
                text = buildString {
                    if (chat.id == currentChatId) {
                        append("• ")
                    }
                    if (chat.isPinned) {
                        append("📌 ")
                    }
                    append(chat.title)
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
                    showChatActions(chat)
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

    private fun showChatActions(
        chat: PrimeChatSummary
    ) {
        val options = arrayOf(
            "تغییر نام",
            if (chat.isPinned) "برداشتن سنجاق" else "سنجاق کردن",
            "آرشیو",
            "اشتراک / Export",
            "حذف"
        )

        AlertDialog.Builder(this)
            .setTitle(chat.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameChat(chat)
                    1 -> {
                        chatStore.setPinned(
                            chat.id,
                            !chat.isPinned
                        )
                        renderChatHistory()
                    }
                    2 -> archiveChat(chat)
                    3 -> shareChat(chat.id)
                    4 -> confirmDeleteChat(chat)
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun archiveChat(
        chat: PrimeChatSummary
    ) {
        if (isBusy) return
        chatStore.setPinned(
            chat.id,
            false
        )
        chatStore.setArchived(
            chat.id,
            true
        )

        if (chat.id == currentChatId) {
            val remaining =
                chatStore.listChats()
            val next = remaining
                .firstOrNull()
                ?.id
                ?: chatStore.createChat()
            loadChat(
                next,
                showGreetingWhenEmpty = true
            )
        } else {
            renderChatHistory()
        }
    }

    private fun showArchivedChats() {
        val chats =
            chatStore.archivedChats()

        if (chats.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("آرشیو گفتگوها")
                .setMessage(
                    "گفت‌وگوی آرشیوشده‌ای وجود ندارد."
                )
                .setPositiveButton(
                    "بستن",
                    null
                )
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("آرشیو گفتگوها")
            .setItems(
                chats.map {
                    it.title
                }.toTypedArray()
            ) { _, which ->
                val chat = chats[which]
                AlertDialog.Builder(this)
                    .setTitle(chat.title)
                    .setItems(
                        arrayOf(
                            "بازگردانی و باز کردن",
                            "اشتراک / Export",
                            "حذف"
                        )
                    ) { _, action ->
                        when (action) {
                            0 -> {
                                chatStore.setArchived(
                                    chat.id,
                                    false
                                )
                                loadChat(
                                    chat.id,
                                    showGreetingWhenEmpty = true
                                )
                            }
                            1 -> shareChat(chat.id)
                            2 -> confirmDeleteChat(chat)
                        }
                    }
                    .setNegativeButton(
                        "بستن",
                        null
                    )
                    .show()
            }
            .setPositiveButton(
                "بستن",
                null
            )
            .show()
    }

    private fun showChatSearch() {
        val input =
            android.widget.EditText(this)
                .apply {
                    hint =
                        "جست‌وجو در متن همهٔ گفتگوها"
                    maxLines = 1
                }

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    "جست‌وجوی گفتگوها"
                )
                .setView(input)
                .setPositiveButton(
                    "جست‌وجو",
                    null
                )
                .setNegativeButton(
                    "لغو",
                    null
                )
                .create()

        dialog.setOnShowListener {
            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {
                val query =
                    input.text
                        ?.toString()
                        .orEmpty()
                        .trim()
                if (query.isBlank()) {
                    return@setOnClickListener
                }

                val results =
                    chatStore.searchMessages(
                        query,
                        60
                    )
                dialog.dismiss()
                showChatSearchResults(
                    query,
                    results
                )
            }
        }
        dialog.show()
    }

    private fun showChatSearchResults(
        query: String,
        results: List<PrimeStoredMessage>
    ) {
        if (results.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(
                    "نتیجه‌ای پیدا نشد"
                )
                .setMessage(
                    "برای «" +
                        query.take(80) +
                        "» نتیجه‌ای در گفتگوهای ذخیره‌شده نیست."
                )
                .setPositiveButton(
                    "بستن",
                    null
                )
                .show()
            return
        }

        val chatMap =
            chatStore
                .listChats(
                    includeArchived = true
                )
                .associateBy {
                    it.id
                }

        val labels =
            results.map { message ->
                val title =
                    chatMap[
                        message.chatId
                    ]?.title
                        ?: "Conversation"
                title +
                    " · " +
                    PrimeChatProductivity
                        .snippet(
                            message.content,
                            110
                        )
            }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(
                "نتایج: " +
                    query.take(50)
            )
            .setItems(
                labels
            ) { _, which ->
                val message =
                    results[which]
                val chat =
                    chatMap[
                        message.chatId
                    ]
                if (
                    chat != null &&
                    chat.isArchived
                ) {
                    chatStore.setArchived(
                        chat.id,
                        false
                    )
                }
                loadChat(
                    message.chatId,
                    showGreetingWhenEmpty = true
                )
                binding.drawerLayout
                    .closeDrawers()
            }
            .setPositiveButton(
                "بستن",
                null
            )
            .show()
    }

    private fun showChatBackupActions() {
        AlertDialog.Builder(this)
            .setTitle(
                "پشتیبان گفتگوها"
            )
            .setMessage(
                "Backup فقط عنوان، pin/archive و پیام‌های user/assistant را شامل می‌شود. Token، API key، Memory، Tool log و URI پیوست‌ها صادر نمی‌شوند."
            )
            .setItems(
                arrayOf(
                    "Export JSON backup",
                    "Import JSON backup"
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        val name =
                            "PRIME-chat-backup-" +
                                SimpleDateFormat(
                                    "yyyyMMdd-HHmm",
                                    Locale.US
                                ).format(
                                    Date()
                                ) +
                                ".json"
                        chatBackupExportPicker
                            .launch(name)
                    }

                    1 ->
                        chatBackupImportPicker
                            .launch(
                                arrayOf(
                                    "application/json",
                                    "text/json",
                                    "text/plain"
                                )
                            )
                }
            }
            .setPositiveButton(
                "بستن",
                null
            )
            .show()
    }

    private fun exportChatBackup(
        uri: Uri
    ) {
        if (isBusy) return
        setBusy(
            true,
            "در حال ساخت backup…"
        )

        appScope.launch {
            try {
                val result =
                    withContext(
                        Dispatchers.IO
                    ) {
                        val backup =
                            PrimeChatBackupCodec
                                .capture(
                                    chatStore
                                )
                        val payload =
                            PrimeChatBackupCodec
                                .encode(
                                    backup
                                )
                        require(
                            payload.length <=
                                PrimeChatBackupCodec
                                    .MAX_JSON_CHARS
                        ) {
                            "Backup از سقف حجم PRIME بزرگ‌تر است."
                        }

                        val output =
                            contentResolver
                                .openOutputStream(
                                    uri,
                                    "w"
                                )
                                ?: throw IllegalStateException(
                                    "فایل خروجی قابل نوشتن نیست."
                                )
                        output.bufferedWriter(
                            Charsets.UTF_8
                        ).use {
                            writer ->
                            writer.write(
                                payload
                            )
                        }

                        backup.chats.size to
                            backup.chats
                                .sumOf {
                                    it.messages.size
                                }
                    }

                appendChat(
                    "PRIME",
                    "Backup ساخته شد: " +
                        result.first +
                        " گفتگو و " +
                        result.second +
                        " پیام. اطلاعات ورود، کلیدها و پیوست‌ها در فایل نیستند.",
                    persist = false
                )
            } catch (e: Exception) {
                appendChat(
                    "PRIME",
                    "Export backup انجام نشد: " +
                        (
                            e.message
                                ?: "خطای نامشخص"
                            ).take(300),
                    persist = false
                )
            } finally {
                setBusy(false)
            }
        }
    }

    private fun importChatBackup(
        uri: Uri
    ) {
        if (isBusy) return
        setBusy(
            true,
            "در حال بررسی backup…"
        )

        appScope.launch {
            try {
                val restored =
                    withContext(
                        Dispatchers.IO
                    ) {
                        val raw =
                            readBoundedBackup(
                                uri
                            )
                        val backup =
                            PrimeChatBackupCodec
                                .decode(raw)
                        PrimeChatBackupCodec
                            .restore(
                                chatStore,
                                backup
                            )
                    }

                renderChatHistory()
                appendChat(
                    "PRIME",
                    restored.size.toString() +
                        " گفتگو از backup وارد شد. Backup خارجی فقط به‌عنوان تاریخچهٔ user/assistant پذیرفته می‌شود و نقش system/developer قابل import نیست.",
                    persist = false
                )
            } catch (e: Exception) {
                appendChat(
                    "PRIME",
                    "Import backup انجام نشد: " +
                        (
                            e.message
                                ?: "فایل backup معتبر نیست."
                            ).take(300),
                    persist = false
                )
            } finally {
                setBusy(false)
            }
        }
    }

    private fun readBoundedBackup(
        uri: Uri
    ): String {
        val input =
            contentResolver
                .openInputStream(uri)
                ?: throw IllegalStateException(
                    "فایل backup قابل خواندن نیست."
                )

        return input.bufferedReader(
            Charsets.UTF_8
        ).use { reader ->
            val result =
                StringBuilder()
            val buffer =
                CharArray(8_192)

            while (true) {
                val count =
                    reader.read(buffer)
                if (count < 0) break
                if (
                    result.length + count >
                    PrimeChatBackupCodec
                        .MAX_JSON_CHARS
                ) {
                    throw IllegalArgumentException(
                        "Backup از سقف 8 MiB PRIME بزرگ‌تر است."
                    )
                }
                result.append(
                    buffer,
                    0,
                    count
                )
            }
            result.toString()
        }
    }

    private fun shareChat(
        chatId: Long
    ) {
        val chat =
            chatStore.chat(chatId)
                ?: return
        val text =
            PrimeChatProductivity
                .exportText(
                    chat,
                    chatStore.messages(
                        chatId
                    )
                )

        val intent =
            Intent(
                Intent.ACTION_SEND
            ).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "PRIME P6 · " +
                        chat.title
                )
                putExtra(
                    Intent.EXTRA_TEXT,
                    text
                )
            }

        startActivity(
            Intent.createChooser(
                intent,
                "اشتراک گفت‌وگو"
            )
        )
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
        fromVoiceMode: Boolean = voiceModeActive,
        persistUserMessage: Boolean = true
    ) {
        if (isBusy) return
        if (VoiceSessionForegroundService.isOverlayRunning) {
            binding.tvAgentStatus.text = "برای چت متنی، ابتدا پنجرهٔ Voice را ببند."
            return
        }
        if (fromVoiceMode) {
            binding.tvVoiceStatus.text = "You: " + input.take(120)
        } else if (persistUserMessage) {
            appendChat("شما", input)
        }

        if (handleMemoryCommand(input, fromVoiceMode)) {
            pendingConfirmationTask = null
            return
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
        if (fromVoiceMode) {
            binding.tvVoiceStatus.text = "در حال آماده‌کردن پاسخ…"
        }

        val streamBuffer = StringBuffer()
        var streamView: TextView? = null
        var generationStopped = false

        val job = appScope.launch(start = CoroutineStart.LAZY) {
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
                        },
                        onTextDelta = if (fromVoiceMode) {
                            null
                        } else {
                            { delta ->
                                streamBuffer.append(delta)
                                val snapshot = streamBuffer.toString()
                                runOnUiThread {
                                    if (streamView == null) {
                                        streamView = addChatBubble(
                                            isUser = false,
                                            message = snapshot
                                        )
                                    } else {
                                        updateChatBubble(
                                            streamView!!,
                                            isUser = false,
                                            message = snapshot
                                        )
                                    }
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
                    val streamed = streamBuffer.isNotEmpty()
                    if (streamed) {
                        val target = streamView ?: addChatBubble(
                            isUser = false,
                            message = outcome.text
                        ).also { streamView = it }
                        updateChatBubble(
                            target,
                            isUser = false,
                            message = outcome.text
                        )
                        persistChatMessage(
                            isUser = false,
                            message = outcome.text
                        )
                    } else {
                        appendChat("PRIME", outcome.text)
                    }
                }
            } catch (_: CancellationException) {
                generationStopped = true
                if (!isFinishing && !isDestroyed) {
                    if (fromVoiceMode) {
                        binding.tvVoiceStatus.text = getString(R.string.ui_generation_stopped)
                    } else {
                        val partial = streamBuffer.toString().trim()
                        if (partial.isNotBlank()) {
                            val target = streamView ?: addChatBubble(
                                isUser = false,
                                message = partial
                            ).also { streamView = it }
                            updateChatBubble(
                                target,
                                isUser = false,
                                message = partial + "\n\n" +
                                    getString(R.string.ui_generation_stopped)
                            )
                        }
                        binding.tvAgentStatus.text =
                            getString(R.string.ui_generation_stopped)
                    }
                }
            } catch (e: Exception) {
                val message = userFriendlyError(e)
                if (fromVoiceMode) {
                    binding.tvVoiceStatus.text = "اتصال قطع شد · دوباره تلاش کن"
                    speak(message)
                } else if (streamBuffer.isEmpty()) {
                    appendChat("PRIME", message, persist = false)
                } else {
                    val target = streamView ?: addChatBubble(
                        isUser = false,
                        message = streamBuffer.toString()
                    ).also { streamView = it }
                    updateChatBubble(
                        target,
                        isUser = false,
                        message = streamBuffer.toString().trim() +
                            "\n\n" + message
                    )
                }
            } finally {
                activeGenerationJob = null
                setBusy(
                    false,
                    if (generationStopped) {
                        getString(R.string.ui_generation_stopped)
                    } else {
                        null
                    }
                )
            }
        }

        activeGenerationJob = job
        job.start()
    }

    private fun stopGeneration() {
        val job = activeGenerationJob ?: return
        if (!job.isActive) return

        binding.tvAgentStatus.text = getString(R.string.ui_stopping_generation)
        job.cancel(CancellationException("Stopped by user"))
    }

    private fun handleMemoryCommand(
        input: String,
        fromVoiceMode: Boolean
    ): Boolean {
        val command = PrimeMemoryCommandParser.parse(input) ?: return false

        if (fromVoiceMode) {
            appendChat("شما", input)
        }

        val response = when (command) {
            is PrimeMemoryCommand.Remember -> {
                val item = memoryStore.remember(
                    kind = command.kind,
                    content = command.content,
                    sourceChatId = currentChatId.takeIf { it > 0L }
                )
                val label = when (item.kind) {
                    PrimeMemoryKind.FACT -> "واقعیت"
                    PrimeMemoryKind.PREFERENCE -> "ترجیح"
                    PrimeMemoryKind.PROJECT -> "پروژه"
                }
                "به حافظهٔ رمز‌شدهٔ PRIME اضافه شد ($label): " +
                    item.content.take(180)
            }

            is PrimeMemoryCommand.Forget -> {
                val removed = memoryStore.forgetMatching(command.query)
                if (removed > 0) {
                    "$removed مورد مرتبط از حافظهٔ PRIME حذف شد."
                } else {
                    "مورد مرتبطی در حافظهٔ PRIME پیدا نشد."
                }
            }

            PrimeMemoryCommand.ListMemories -> {
                val memories = memoryStore.list(limit = 8)
                if (memories.isEmpty()) {
                    "حافظهٔ بلندمدت PRIME خالی است."
                } else {
                    buildString {
                        appendLine("چیزهایی که به درخواست تو در حافظه نگه داشته‌ام:")
                        memories.forEach { memory ->
                            append("• ")
                            appendLine(memory.content.take(180))
                        }
                    }.trim()
                }
            }
        }

        appendChat("PRIME", response)
        if (fromVoiceMode) {
            binding.tvVoiceStatus.text = response.take(160)
            speak(response)
        }
        return true
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
        if (isBusy) {
            binding.btnMic.visibility = View.GONE
            binding.btnSend.visibility = View.GONE
            binding.btnVoice.visibility = View.GONE
            binding.btnStop.visibility = View.VISIBLE
            return
        }

        val hasText = !binding.etMessage.text.isNullOrBlank()
        binding.btnMic.visibility = View.VISIBLE
        binding.btnStop.visibility = View.GONE
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
        if (persist) {
            persistChatMessage(isUser, message)
        }
        addChatBubble(isUser, message)
    }

    private fun persistChatMessage(
        isUser: Boolean,
        message: String
    ) {
        if (
            message.isBlank() ||
            currentChatId <= 0L ||
            !chatStore.chatExists(currentChatId)
        ) {
            return
        }

        chatStore.appendMessage(
            currentChatId,
            if (isUser) "user" else "assistant",
            message
        )
        renderedMessageCount += 1
        if (isUser) {
            renderChatHistory()
        }
    }

    private fun addChatBubble(
        isUser: Boolean,
        message: String
    ): TextView {
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
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    R.color.text_primary
                )
            )
            textSize = 15f
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (isUser) {
                    R.drawable.user_message_bg
                } else {
                    R.drawable.chat_bg
                }
            )
        }

        updateChatBubble(messageView, isUser, message)
        row.addView(messageView)
        binding.chatMessages.addView(row)
        scrollChatToBottom()
        return messageView
    }

    private fun updateChatBubble(
        view: TextView,
        isUser: Boolean,
        message: String
    ) {
        val clean = message.trim()
        view.text = if (isUser) clean else "PRIME\n$clean"
        Linkify.addLinks(
            view,
            Linkify.WEB_URLS
        )
        view.linksClickable = true
        view.movementMethod =
            LinkMovementMethod.getInstance()
        view.textDirection = if (
            clean.any { it.code in 0x0600..0x06FF }
        ) {
            View.TEXT_DIRECTION_RTL
        } else {
            View.TEXT_DIRECTION_FIRST_STRONG
        }
        scrollChatToBottom()
    }

    private fun scrollChatToBottom() {
        binding.chatScrollView.post {
            binding.chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showQuickActions() {
        val options = arrayOf(
            if (authManager.isSignedIn()) "حساب ChatGPT" else "اتصال ChatGPT",
            "کنترل گوشی",
            "تنظیمات گوشی",
            "پل اتصال دستگاه",
            "حافظهٔ PRIME",
            "جست‌وجوی گفتگوها",
            "آرشیو گفتگوها",
            "افزودن فایل",
            "فایل‌های این چت",
            "افزودن تصویر",
            "تصاویر این چت",
            "Tasks & Automations",
            "AI Providers",
            "Developer / Usage",
            "Plugins",
            "MCP Servers",
            "پشتیبان / بازیابی گفتگوها"
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
                    4 -> showMemoryManager()
                    5 -> showChatSearch()
                    6 -> showArchivedChats()
                    7 -> pickDocument()
                    8 -> showAttachmentManager()
                    9 -> pickImages()
                    10 -> showImageManager()
                    11 -> showTasks()
                    12 -> showProviders()
                    13 -> showDeveloperUsage()
                    14 -> showPlugins()
                    15 -> showMcpServers()
                    16 -> showChatBackupActions()
                }
            }
            .show()
    }

    private fun buildPrimeAgent(): PrimeAgent {
        val activeId =
            providerStore.activeId()
        val profile =
            if (
                activeId ==
                PrimeProviderStore
                    .CHATGPT_ID
            ) {
                null
            } else {
                providerStore.get(
                    activeId
                )
            }

        if (
            activeId !=
                PrimeProviderStore
                    .CHATGPT_ID &&
            profile == null
        ) {
            providerStore.setActive(
                PrimeProviderStore
                    .CHATGPT_ID
            )
        }

        val baseProvider =
            if (profile == null) {
                OpenAIResponsesProvider(
                    object :
                        PrimeCredentials {
                        override fun isSignedIn() =
                            authManager
                                .isSignedIn()

                        override suspend fun accessToken() =
                            authManager
                                .accessToken()
                    }
                )
            } else {
                PrimeProviderFactory
                    .create(profile)
            }

        val observedProvider =
            PrimeObservedProvider(
                baseProvider,
                observabilityStore
            )

        return PrimeAgent(
            provider =
                observedProvider,
            memorySource =
                memoryStore,
            fileContextSource =
                attachmentSession,
            externalToolSource =
                mcpToolSource,
            imageContextSource =
                imageSession
        )
    }

    private fun rebuildPrimeAgent() {
        primeAgent = buildPrimeAgent()

        if (
            ::chatStore.isInitialized &&
            currentChatId > 0L &&
            chatStore.chatExists(
                currentChatId
            )
        ) {
            val messages =
                chatStore.messages(
                    currentChatId
                )
            primeAgent
                .restoreConversation(
                    messages.map {
                        it.role to
                            it.content
                    }
                )
        }

        warmUpPrime()
        updateAuthUI()
    }

    private fun showDeveloperUsage() {
        val summary =
            observabilityStore
                .summary()
        val events =
            observabilityStore
                .all()
        val recent =
            events
                .asReversed()
                .take(20)
        val cost =
            costStore.estimate(
                events
            )

        val details =
            buildString {
                appendLine(
                    "AI calls: " +
                        summary.calls
                )
                appendLine(
                    "Failures: " +
                        summary.failures
                )
                appendLine(
                    "Estimated input tokens: " +
                        summary
                            .estimatedInputTokens
                )
                appendLine(
                    "Estimated output tokens: " +
                        summary
                            .estimatedOutputTokens
                )
                appendLine(
                    "Average latency: " +
                        summary
                            .averageLatencyMs +
                        " ms"
                )

                if (
                    cost.matchedEvents > 0
                ) {
                    appendLine(
                        "Estimated configured cost: $" +
                            String.format(
                                Locale.US,
                                "%.6f",
                                cost.usd
                            )
                    )
                }
                if (
                    cost.unmatchedEvents > 0
                ) {
                    appendLine(
                        "Cost without configured rate: " +
                            cost.unmatchedEvents +
                            " calls"
                    )
                }

                if (
                    summary.byProvider
                        .isNotEmpty()
                ) {
                    appendLine()
                    appendLine(
                        "By provider:"
                    )
                    summary.byProvider
                        .toList()
                        .sortedByDescending {
                            it.second
                        }
                        .forEach {
                            (provider, count) ->
                            append("• ")
                            append(provider)
                            append(": ")
                            appendLine(count)
                        }
                }

                if (
                    recent.isNotEmpty()
                ) {
                    appendLine()
                    appendLine(
                        "Recent:"
                    )
                    recent.forEach {
                        event ->
                        append("• ")
                        append(
                            event.operation
                                .name
                                .lowercase()
                        )
                        append(" · ")
                        append(
                            event.providerId
                        )
                        event.model?.let {
                            append(" · ")
                            append(
                                it.take(60)
                            )
                        }
                        append(" · ")
                        append(
                            event.durationMs
                        )
                        append("ms · ")
                        append(
                            event.estimatedInputTokens
                        )
                        append("/")
                        append(
                            event.estimatedOutputTokens
                        )
                        append(" tok")
                        if (
                            event.mediaItems >
                            0
                        ) {
                            append(" · ")
                            append(
                                event.mediaItems
                            )
                            append(
                                " media"
                            )
                        }
                        if (!event.success) {
                            append(" · ERROR")
                            event.errorType
                                ?.let {
                                    append("(")
                                    append(it)
                                    append(")")
                                }
                        }
                        appendLine()
                    }
                }

                appendLine()
                append(
                    "Token and cost values are local estimates. Cost is calculated only when you configure a provider/model rate; it is not a provider invoice."
                )
            }.trim()

        AlertDialog.Builder(this)
            .setTitle(
                "Developer / Usage"
            )
            .setMessage(details)
            .setPositiveButton(
                "بستن",
                null
            )
            .setNeutralButton(
                "Cost rates"
            ) { _, _ ->
                showCostRates()
            }
            .setNegativeButton(
                "پاک کردن آمار"
            ) { _, _ ->
                observabilityStore
                    .clear()
                Toast.makeText(
                    this,
                    "آمار محلی پاک شد",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun showCostRates() {
        val rates = costStore.list()
        val labels =
            buildList {
                add("＋ افزودن / ویرایش نرخ")
                rates.forEach { rate ->
                    add(
                        rate.providerId +
                            " · " +
                            rate.modelPattern +
                            " · $" +
                            rate.inputUsdPerMillion +
                            "/$" +
                            rate.outputUsdPerMillion +
                            " per 1M"
                    )
                }
            }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Cost rates")
            .setMessage(
                if (rates.isEmpty()) {
                    "هیچ نرخ قیمتی تنظیم نشده. PRIME قیمت vendorها را hardcode نمی‌کند؛ نرخ‌های فعلی provider/model را خودت وارد کن."
                } else {
                    "Input / Output USD per 1M tokens. این نرخ‌ها فقط برای تخمین محلی استفاده می‌شوند."
                }
            )
            .setItems(labels) { _, which ->
                if (which == 0) {
                    showCostRateEditor()
                } else {
                    val rate =
                        rates[which - 1]
                    AlertDialog.Builder(this)
                        .setTitle(
                            rate.providerId +
                                " · " +
                                rate.modelPattern
                        )
                        .setMessage(
                            "Input: $" +
                                rate.inputUsdPerMillion +
                                " / 1M\nOutput: $" +
                                rate.outputUsdPerMillion +
                                " / 1M"
                        )
                        .setPositiveButton(
                            "ویرایش"
                        ) { _, _ ->
                            showCostRateEditor(
                                rate
                            )
                        }
                        .setNegativeButton(
                            "حذف"
                        ) { _, _ ->
                            costStore.remove(
                                rate.id
                            )
                        }
                        .show()
                }
            }
            .setNeutralButton(
                "پاک کردن همه"
            ) { _, _ ->
                costStore.clear()
            }
            .setPositiveButton(
                "بستن",
                null
            )
            .show()
    }

    private fun showCostRateEditor(
        existing: PrimeCostRate? = null
    ) {
        val target =
            activeCostTarget()
        val providerInput =
            android.widget.EditText(
                this
            ).apply {
                hint = "provider id"
                setText(
                    existing?.providerId
                        ?: target.first
                )
                maxLines = 1
            }
        val modelInput =
            android.widget.EditText(
                this
            ).apply {
                hint = "model or *"
                setText(
                    existing?.modelPattern
                        ?: target.second
                )
                maxLines = 1
            }
        val inputPrice =
            android.widget.EditText(
                this
            ).apply {
                hint =
                    "input USD / 1M tokens"
                setText(
                    existing
                        ?.inputUsdPerMillion
                        ?.toString()
                        ?: ""
                )
                inputType =
                    android.text.InputType
                        .TYPE_CLASS_NUMBER or
                        android.text.InputType
                            .TYPE_NUMBER_FLAG_DECIMAL
            }
        val outputPrice =
            android.widget.EditText(
                this
            ).apply {
                hint =
                    "output USD / 1M tokens"
                setText(
                    existing
                        ?.outputUsdPerMillion
                        ?.toString()
                        ?: ""
                )
                inputType =
                    android.text.InputType
                        .TYPE_CLASS_NUMBER or
                        android.text.InputType
                            .TYPE_NUMBER_FLAG_DECIMAL
            }

        val box = LinearLayout(this).apply {
            orientation =
                LinearLayout.VERTICAL
            val pad = dp(18)
            setPadding(
                pad,
                dp(6),
                pad,
                0
            )
            addView(providerInput)
            addView(modelInput)
            addView(inputPrice)
            addView(outputPrice)
        }

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    if (existing == null) {
                        "Add cost rate"
                    } else {
                        "Edit cost rate"
                    }
                )
                .setMessage(
                    "قیمت‌ها از provider گرفته نمی‌شوند؛ مقدار فعلی رسمی را خودت وارد کن."
                )
                .setView(box)
                .setPositiveButton(
                    "ذخیره",
                    null
                )
                .setNegativeButton(
                    "لغو",
                    null
                )
                .create()

        dialog.setOnShowListener {
            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {
                val input =
                    inputPrice.text
                        ?.toString()
                        ?.trim()
                        ?.toDoubleOrNull()
                val output =
                    outputPrice.text
                        ?.toString()
                        ?.trim()
                        ?.toDoubleOrNull()

                if (
                    input == null ||
                    output == null ||
                    input < 0.0 ||
                    output < 0.0
                ) {
                    inputPrice.error =
                        "یک عدد نامنفی وارد کن"
                    outputPrice.error =
                        "یک عدد نامنفی وارد کن"
                    return@setOnClickListener
                }

                runCatching {
                    existing?.let {
                        costStore.remove(
                            it.id
                        )
                    }
                    costStore.save(
                        providerId =
                            providerInput.text
                                ?.toString()
                                .orEmpty(),
                        modelPattern =
                            modelInput.text
                                ?.toString()
                                .orEmpty(),
                        inputUsdPerMillion =
                            input,
                        outputUsdPerMillion =
                            output
                    )
                }.onSuccess {
                    dialog.dismiss()
                    showCostRates()
                }.onFailure {
                    providerInput.error =
                        it.message
                            ?: "نرخ معتبر نیست"
                }
            }
        }

        dialog.show()
    }

    private fun activeCostTarget():
        Pair<String, String> {
        val activeId =
            providerStore.activeId()
        if (
            activeId ==
                PrimeProviderStore
                    .CHATGPT_ID
        ) {
            return "openai-responses" to
                "*"
        }

        val profile =
            providerStore.get(activeId)
                ?: return "unknown" to "*"
        val provider = when (
            profile.kind
        ) {
            PrimeProviderKind.OPENROUTER ->
                "openrouter"
            PrimeProviderKind.GEMINI ->
                "gemini"
            PrimeProviderKind.ANTHROPIC ->
                "anthropic"
            PrimeProviderKind.OPENAI_COMPATIBLE ->
                "openai-compatible"
        }

        return provider to
            profile.model
    }

    private fun showProviders() {
        val profiles =
            providerStore.list()
        val activeId =
            providerStore.activeId()

        val labels =
            buildList {
                add(
                    "ChatGPT Plan" +
                        if (
                            activeId ==
                            PrimeProviderStore
                                .CHATGPT_ID
                        ) {
                            " · فعال"
                        } else {
                            ""
                        }
                )
                profiles.forEach {
                    profile ->
                    add(
                        profile.name +
                            " · " +
                            profile.kind
                                .name
                                .lowercase() +
                            if (
                                activeId ==
                                profile.id
                            ) {
                                " · فعال"
                            } else {
                                ""
                            }
                    )
                }
            }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("AI Providers")
            .setItems(labels) {
                _,
                which ->
                if (which == 0) {
                    providerStore
                        .setActive(
                            PrimeProviderStore
                                .CHATGPT_ID
                        )
                    rebuildPrimeAgent()
                    Toast.makeText(
                        this,
                        "ChatGPT Plan فعال شد",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    showProviderDetails(
                        profiles[
                            which - 1
                        ]
                    )
                }
            }
            .setPositiveButton(
                "افزودن Provider"
            ) { _, _ ->
                chooseProviderKind()
            }
            .setNegativeButton(
                "بستن",
                null
            )
            .show()
    }

    private fun chooseProviderKind() {
        val kinds =
            arrayOf(
                PrimeProviderKind
                    .OPENROUTER,
                PrimeProviderKind
                    .GEMINI,
                PrimeProviderKind
                    .ANTHROPIC,
                PrimeProviderKind
                    .OPENAI_COMPATIBLE
            )
        val labels =
            arrayOf(
                "OpenRouter",
                "Google Gemini",
                "Anthropic Claude",
                "OpenAI-compatible / Local"
            )

        AlertDialog.Builder(this)
            .setTitle(
                "نوع Provider"
            )
            .setItems(
                labels
            ) { _, which ->
                showAddProviderDialog(
                    kinds[which]
                )
            }
            .setNegativeButton(
                "لغو",
                null
            )
            .show()
    }

    private fun showAddProviderDialog(
        kind: PrimeProviderKind
    ) {
        val panel =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(24),
                    dp(8),
                    dp(24),
                    dp(4)
                )
            }

        fun textInput(
            hintText: String
        ) = android.widget
            .EditText(this)
            .apply {
                hint = hintText
                maxLines = 2
            }

        val name =
            textInput(
                "نام Profile"
            ).apply {
                setText(
                    when (kind) {
                        PrimeProviderKind
                            .OPENROUTER ->
                            "OpenRouter"
                        PrimeProviderKind
                            .GEMINI ->
                            "Gemini"
                        PrimeProviderKind
                            .ANTHROPIC ->
                            "Anthropic"
                        PrimeProviderKind
                            .OPENAI_COMPATIBLE ->
                            "Local / Custom"
                    }
                )
            }
        val base =
            textInput(
                "Base URL"
            ).apply {
                setText(
                    PrimeProviderDefaults
                        .baseUrl(kind)
                )
            }
        val model =
            textInput(
                "Model ID"
            ).apply {
                setText(
                    PrimeProviderDefaults
                        .modelHint(kind)
                )
            }
        val key =
            textInput(
                if (
                    kind ==
                    PrimeProviderKind
                        .OPENAI_COMPATIBLE
                ) {
                    "API key (اختیاری)"
                } else {
                    "API key"
                }
            ).apply {
                inputType =
                    android.text
                        .InputType
                        .TYPE_CLASS_TEXT or
                        android.text
                            .InputType
                            .TYPE_TEXT_VARIATION_PASSWORD
                maxLines = 1
            }

        panel.addView(name)
        panel.addView(base)
        panel.addView(model)
        panel.addView(key)

        AlertDialog.Builder(this)
            .setTitle(
                "افزودن Provider"
            )
            .setView(panel)
            .setPositiveButton(
                "ذخیره و فعال"
            ) { _, _ ->
                try {
                    val profile =
                        providerStore.save(
                            name = name.text
                                ?.toString()
                                .orEmpty(),
                            kind = kind,
                            baseUrl = base.text
                                ?.toString()
                                .orEmpty(),
                            model = model.text
                                ?.toString()
                                .orEmpty(),
                            apiKey = key.text
                                ?.toString()
                        )
                    providerStore
                        .setActive(
                            profile.id
                        )
                    rebuildPrimeAgent()
                    Toast.makeText(
                        this,
                        profile.name +
                            " فعال شد",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    appendChat(
                        "PRIME",
                        "Provider ذخیره نشد: " +
                            (
                                e.message
                                    ?: "تنظیم نامعتبر"
                            ).take(500),
                        persist = false
                    )
                }
            }
            .setNegativeButton(
                "لغو",
                null
            )
            .show()
    }

    private fun showProviderDetails(
        profile: PrimeProviderProfile
    ) {
        val active =
            providerStore.activeId() ==
                profile.id

        val details =
            buildString {
                appendLine(
                    profile.kind
                        .name
                )
                appendLine()
                appendLine(
                    profile.baseUrl
                )
                append(
                    "Model: "
                )
                appendLine(
                    profile.model
                )
                append(
                    "API key: "
                )
                append(
                    if (
                        profile.apiKey
                            .isNullOrBlank()
                    ) {
                        "none"
                    } else {
                        "stored encrypted"
                    }
                )
            }

        AlertDialog.Builder(this)
            .setTitle(
                profile.name +
                    if (active) {
                        " · فعال"
                    } else {
                        ""
                    }
            )
            .setMessage(details)
            .setPositiveButton(
                "فعال کن"
            ) { _, _ ->
                providerStore
                    .setActive(
                        profile.id
                    )
                rebuildPrimeAgent()
            }
            .setNeutralButton(
                "حذف"
            ) { _, _ ->
                val wasActive =
                    providerStore
                        .activeId() ==
                        profile.id
                providerStore.remove(
                    profile.id
                )
                if (wasActive) {
                    rebuildPrimeAgent()
                }
            }
            .setNegativeButton(
                "بستن",
                null
            )
            .show()
    }

    private fun showTasks() {
        val tasks = taskStore.list()

        if (tasks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(
                    "Tasks & Automations"
                )
                .setMessage(
                    "Reminder یا AI Brief زمان‌بندی‌شده بساز. " +
                        "Taskهای دوره‌ای حداقل هر ۱۵ دقیقه اجرا می‌شوند."
                )
                .setPositiveButton(
                    "ساخت Task"
                ) { _, _ ->
                    chooseTaskType()
                }
                .setNegativeButton(
                    "بستن",
                    null
                )
                .show()
            return
        }

        val labels = tasks
            .map { task ->
                buildString {
                    append(task.title)
                    append(" · ")
                    append(
                        when (
                            task.type
                        ) {
                            PrimeTaskType
                                .REMINDER ->
                                "Reminder"
                            PrimeTaskType
                                .AI_BRIEF ->
                                "AI Brief"
                        }
                    )
                    append(" · ")
                    append(
                        if (
                            task.enabled
                        ) {
                            "فعال"
                        } else {
                            "خاموش"
                        }
                    )
                }
            }
            .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(
                "Tasks & Automations"
            )
            .setItems(
                labels
            ) { _, which ->
                showTaskDetails(
                    tasks[which]
                )
            }
            .setPositiveButton(
                "ساخت"
            ) { _, _ ->
                chooseTaskType()
            }
            .setNegativeButton(
                "بستن",
                null
            )
            .show()
    }

    private fun chooseTaskType() {
        AlertDialog.Builder(this)
            .setTitle(
                "نوع Task"
            )
            .setItems(
                arrayOf(
                    "Reminder محلی",
                    "AI Brief پس‌زمینه"
                )
            ) { _, which ->
                showCreateTaskDialog(
                    if (which == 0) {
                        PrimeTaskType
                            .REMINDER
                    } else {
                        PrimeTaskType
                            .AI_BRIEF
                    }
                )
            }
            .setNegativeButton(
                "لغو",
                null
            )
            .show()
    }

    private fun showCreateTaskDialog(
        type: PrimeTaskType
    ) {
        val panel =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(24),
                    dp(8),
                    dp(24),
                    dp(4)
                )
            }

        fun input(
            hintText: String,
            numeric: Boolean = false
        ) = android.widget
            .EditText(this)
            .apply {
                hint = hintText
                if (numeric) {
                    inputType =
                        android.text
                            .InputType
                            .TYPE_CLASS_NUMBER
                    maxLines = 1
                }
            }

        val title = input(
            "عنوان Task"
        )
        val prompt = input(
            if (
                type ==
                PrimeTaskType
                    .AI_BRIEF
            ) {
                "درخواست AI، مثلاً: هر بار خلاصه‌ای از اهداف پروژه بده"
            } else {
                "متن Reminder"
            }
        ).apply {
            minLines = 2
            maxLines = 5
        }
        val delay = input(
            "چند دقیقه دیگر شروع شود؟ (0 = اکنون)",
            numeric = true
        ).apply {
            setText("0")
        }
        val interval = input(
            "تکرار هر چند دقیقه؟ خالی = یک‌بار، حداقل 15",
            numeric = true
        )

        panel.addView(title)
        panel.addView(prompt)
        panel.addView(delay)
        panel.addView(interval)

        AlertDialog.Builder(this)
            .setTitle(
                if (
                    type ==
                    PrimeTaskType
                        .AI_BRIEF
                ) {
                    "AI Brief جدید"
                } else {
                    "Reminder جدید"
                }
            )
            .setView(panel)
            .setPositiveButton(
                "زمان‌بندی"
            ) { _, _ ->
                try {
                    val delayMinutes =
                        delay.text
                            ?.toString()
                            ?.trim()
                            ?.toLongOrNull()
                            ?: 0L
                    val intervalValue =
                        interval.text
                            ?.toString()
                            ?.trim()
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?.toLongOrNull()

                    val task =
                        taskStore.create(
                            title = title.text
                                ?.toString()
                                .orEmpty(),
                            prompt = prompt.text
                                ?.toString()
                                .orEmpty(),
                            type = type,
                            scheduleKind =
                                if (
                                    intervalValue ==
                                    null
                                ) {
                                    PrimeTaskScheduleKind
                                        .ONE_TIME
                                } else {
                                    PrimeTaskScheduleKind
                                        .PERIODIC
                                },
                            initialDelayMinutes =
                                delayMinutes,
                            intervalMinutes =
                                intervalValue
                        )

                    taskScheduler
                        .schedule(task)
                    requestTaskNotificationPermission()
                    Toast.makeText(
                        this,
                        "Task زمان‌بندی شد",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    appendChat(
                        "PRIME",
                        "Task ساخته نشد: " +
                            (
                                e.message
                                    ?: "تنظیم زمان نامعتبر"
                            ).take(400),
                        persist = false
                    )
                }
            }
            .setNegativeButton(
                "لغو",
                null
            )
            .show()
    }

    private fun showTaskDetails(
        task: PrimeTaskRecord
    ) {
        val details =
            buildString {
                appendLine(
                    when (task.type) {
                        PrimeTaskType.REMINDER ->
                            "Reminder"
                        PrimeTaskType.AI_BRIEF ->
                            "AI Brief"
                    }
                )
                appendLine()
                appendLine(
                    task.prompt.take(
                        700
                    )
                )
                appendLine()
                append("Schedule: ")
                when (
                    task.scheduleKind
                ) {
                    PrimeTaskScheduleKind
                        .ONE_TIME -> {
                        append(
                            "one-time"
                        )
                    }
                    PrimeTaskScheduleKind
                        .PERIODIC -> {
                        append(
                            "every " +
                                task.intervalMinutes +
                                " min"
                        )
                    }
                }
                appendLine()
                append(
                    "Initial delay: "
                )
                append(
                    task.initialDelayMinutes
                )
                appendLine(" min")
                task.lastRunAt?.let {
                    append(
                        "Last run: "
                    )
                    appendLine(
                        Date(it).toString()
                    )
                }
                task.lastStatus?.let {
                    append(
                        "Status: "
                    )
                    appendLine(it)
                }
            }.trim()

        AlertDialog.Builder(this)
            .setTitle(task.title)
            .setMessage(details)
            .setPositiveButton(
                "Reschedule"
            ) { _, _ ->
                val current =
                    taskStore.get(
                        task.id
                    )
                if (
                    current != null &&
                    current.enabled
                ) {
                    taskScheduler
                        .schedule(
                            current
                        )
                    Toast.makeText(
                        this,
                        "Task دوباره زمان‌بندی شد",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNeutralButton(
                if (
                    task.enabled
                ) {
                    "غیرفعال"
                } else {
                    "فعال"
                }
            ) { _, _ ->
                val updated =
                    taskStore.setEnabled(
                        task.id,
                        !task.enabled
                    )
                if (
                    updated != null
                ) {
                    if (
                        updated.enabled
                    ) {
                        taskScheduler
                            .schedule(
                                updated
                            )
                        requestTaskNotificationPermission()
                    } else {
                        taskScheduler
                            .cancel(
                                updated.id
                            )
                    }
                }
            }
            .setNegativeButton(
                "حذف"
            ) { _, _ ->
                taskScheduler
                    .cancel(task.id)
                taskStore.remove(
                    task.id
                )
            }
            .show()
    }

    private fun requestTaskNotificationPermission() {
        if (
            android.os.Build
                .VERSION.SDK_INT >= 33 &&
            ContextCompat
                .checkSelfPermission(
                    this,
                    Manifest.permission
                        .POST_NOTIFICATIONS
                ) !=
                PackageManager
                    .PERMISSION_GRANTED
        ) {
            ActivityCompat
                .requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission
                            .POST_NOTIFICATIONS
                    ),
                    6002
                )
        }
    }

    private fun showPlugins() {
        val plugins =
            pluginManager.list()

        if (plugins.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Plugins")
                .setMessage(
                    "Pluginهای PRIME از manifest نسخه prime.plugin.v1 نصب می‌شوند و runtime آن‌ها MCP است."
                )
                .setPositiveButton(
                    "نصب Plugin"
                ) { _, _ ->
                    pickPluginManifest()
                }
                .setNegativeButton(
                    "بستن",
                    null
                )
                .show()
            return
        }

        val labels =
            plugins.map { record ->
                record.manifest.name +
                    " · v" +
                    record.manifest.version +
                    " · " +
                    (
                        if (
                            record.enabled
                        ) {
                            "فعال"
                        } else {
                            "خاموش"
                        }
                    )
            }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Plugins")
            .setItems(labels) {
                _,
                which ->
                showPluginDetails(
                    plugins[which]
                )
            }
            .setPositiveButton(
                "نصب"
            ) { _, _ ->
                pickPluginManifest()
            }
            .setNegativeButton(
                "بستن",
                null
            )
            .show()
    }

    private fun pickPluginManifest() {
        if (isBusy) return
        pluginManifestPicker.launch(
            arrayOf(
                "application/json",
                "text/plain",
                "application/octet-stream"
            )
        )
    }

    private fun ingestPluginManifest(
        uri: Uri
    ) {
        if (isBusy) return
        setBusy(
            true,
            "در حال بررسی Plugin…"
        )

        appScope.launch {
            try {
                val manifest =
                    withContext(
                        Dispatchers.IO
                    ) {
                        PrimePluginManifestParser
                            .parse(
                                pluginManifestLoader
                                    .load(uri)
                            )
                    }
                setBusy(false)
                showPluginInstallDialog(
                    manifest
                )
            } catch (e: Exception) {
                appendChat(
                    "PRIME",
                    "Plugin نصب نشد: " +
                        (
                            e.message
                                ?: "manifest نامعتبر"
                            ).take(500),
                    persist = false
                )
                setBusy(false)
            }
        }
    }

    private fun showPluginInstallDialog(
        manifest: PrimePluginManifest
    ) {
        val panel =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(24),
                    dp(8),
                    dp(24),
                    dp(4)
                )
            }

        val summary =
            TextView(this).apply {
                text = buildString {
                    appendLine(
                        manifest.name +
                            " · v" +
                            manifest.version
                    )
                    manifest.description
                        ?.let {
                            appendLine(it)
                        }
                    appendLine()
                    appendLine(
                        "Runtime: MCP"
                    )
                    appendLine(
                        manifest.mcpUrl
                    )
                    append(
                        "Capabilities: "
                    )
                    append(
                        manifest
                            .capabilities
                            .joinToString(
                                ", "
                            ) {
                                it.name
                                    .lowercase()
                            }
                    )
                }
                setTextColor(
                    ContextCompat
                        .getColor(
                            this@MainActivity,
                            R.color
                                .text_primary
                        )
                )
                setPadding(
                    0,
                    0,
                    0,
                    dp(12)
                )
            }

        val token =
            android.widget.EditText(
                this
            ).apply {
                hint =
                    "Bearer token (اختیاری؛ secret داخل manifest قرار نده)"
                inputType =
                    android.text.InputType
                        .TYPE_CLASS_TEXT or
                        android.text.InputType
                            .TYPE_TEXT_VARIATION_PASSWORD
                maxLines = 1
            }

        panel.addView(summary)
        panel.addView(token)

        AlertDialog.Builder(this)
            .setTitle(
                "نصب Plugin"
            )
            .setView(panel)
            .setPositiveButton(
                "نصب و تست"
            ) { _, _ ->
                try {
                    val record =
                        pluginManager
                            .install(
                                manifest =
                                    manifest,
                                bearerToken =
                                    token.text
                                        ?.toString()
                            )
                    val server =
                        pluginManager
                            .serverFor(
                                record
                            )
                    if (
                        server != null &&
                        server.enabled
                    ) {
                        discoverMcpServer(
                            server
                        )
                    }
                } catch (
                    e: Exception
                ) {
                    appendChat(
                        "PRIME",
                        "Plugin نصب نشد: " +
                            (
                                e.message
                                    ?: "خطای نصب"
                                ).take(
                                    500
                                ),
                        persist = false
                    )
                }
            }
            .setNegativeButton(
                "لغو",
                null
            )
            .show()
    }

    private fun showPluginDetails(
        record: PrimePluginRecord
    ) {
        val manifest =
            record.manifest
        val details =
            buildString {
                appendLine(
                    manifest.id
                )
                appendLine(
                    "Version: " +
                        manifest.version
                )
                manifest.description
                    ?.let {
                        appendLine()
                        appendLine(it)
                    }
                appendLine()
                appendLine(
                    manifest.mcpUrl
                )
                append(
                    "Capabilities: "
                )
                append(
                    manifest
                        .capabilities
                        .joinToString(
                            ", "
                        ) {
                            it.name
                                .lowercase()
                        }
                )
            }.trim()

        AlertDialog.Builder(this)
            .setTitle(
                manifest.name
            )
            .setMessage(details)
            .setPositiveButton(
                "Test / Refresh"
            ) { _, _ ->
                pluginManager
                    .serverFor(record)
                    ?.let {
                        discoverMcpServer(
                            it
                        )
                    }
            }
            .setNeutralButton(
                if (
                    record.enabled
                ) {
                    "غیرفعال"
                } else {
                    "فعال"
                }
            ) { _, _ ->
                val updated =
                    pluginManager
                        .setEnabled(
                            manifest.id,
                            !record.enabled
                        )
                if (
                    updated != null
                ) {
                    if (
                        updated.enabled
                    ) {
                        pluginManager
                            .serverFor(
                                updated
                            )
                            ?.let {
                                discoverMcpServer(
                                    it
                                )
                            }
                    } else {
                        mcpDiscoveryCache
                            .remove(
                                record.mcpServerId
                            )
                        mcpToolSource
                            .removeServer(
                                record.mcpServerId
                            )
                    }
                }
            }
            .setNegativeButton(
                "حذف"
            ) { _, _ ->
                confirmUninstallPlugin(
                    record
                )
            }
            .show()
    }

    private fun confirmUninstallPlugin(
        record: PrimePluginRecord
    ) {
        AlertDialog.Builder(this)
            .setTitle(
                "حذف Plugin؟"
            )
            .setMessage(
                record.manifest.name +
                    "\n" +
                    record.manifest.id
            )
            .setPositiveButton(
                "حذف"
            ) { _, _ ->
                val removed =
                    pluginManager
                        .uninstall(
                            record
                                .manifest
                                .id
                        )
                if (
                    removed != null
                ) {
                    mcpDiscoveryCache
                        .remove(
                            removed
                                .mcpServerId
                        )
                    mcpToolSource
                        .removeServer(
                            removed
                                .mcpServerId
                        )
                }
            }
            .setNegativeButton(
                "لغو",
                null
            )
            .show()
    }

    private fun showMcpServers() {
        val servers = mcpServerStore.list()

        if (servers.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("MCP Servers")
                .setMessage(
                    "هنوز MCP Server اضافه نشده. " +
                        "برای server راه‌دور HTTPS و برای localhost " +
                        "HTTP/HTTPS قابل استفاده است."
                )
                .setPositiveButton("افزودن Server") { _, _ ->
                    showAddMcpServerDialog()
                }
                .setNegativeButton("بستن", null)
                .show()
            return
        }

        val labels = servers.map { server ->
            val discovery =
                mcpDiscoveryCache[server.id]
            val status = when {
                !server.enabled -> "خاموش"
                discovery != null ->
                    discovery.protocolVersion +
                        " · " +
                        discovery.tools.size +
                        " tools"
                else -> "ذخیره‌شده"
            }
            server.name + " · " + status
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("MCP Servers")
            .setItems(labels) { _, which ->
                showMcpServerDetails(
                    servers[which]
                )
            }
            .setPositiveButton("افزودن") { _, _ ->
                showAddMcpServerDialog()
            }
            .setNeutralButton("تست همه") { _, _ ->
                discoverAllMcpServers()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun showAddMcpServerDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(24),
                dp(8),
                dp(24),
                dp(4)
            )
        }

        val nameInput =
            android.widget.EditText(this).apply {
                hint = "نام Server"
                maxLines = 1
            }
        val urlInput =
            android.widget.EditText(this).apply {
                hint = "https://example.com/mcp"
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
                maxLines = 2
            }
        val tokenInput =
            android.widget.EditText(this).apply {
                hint = "Bearer token (اختیاری)"
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                maxLines = 1
            }

        panel.addView(nameInput)
        panel.addView(urlInput)
        panel.addView(tokenInput)

        AlertDialog.Builder(this)
            .setTitle("افزودن MCP Server")
            .setView(panel)
            .setPositiveButton("ذخیره و تست") { _, _ ->
                try {
                    val saved = mcpServerStore.save(
                        name = nameInput.text
                            ?.toString()
                            .orEmpty(),
                        url = urlInput.text
                            ?.toString()
                            .orEmpty(),
                        bearerToken = tokenInput.text
                            ?.toString()
                    )
                    discoverMcpServer(saved)
                } catch (e: Exception) {
                    appendChat(
                        "PRIME",
                        "MCP Server ذخیره نشد: " +
                            (e.message ?: "تنظیم نامعتبر"),
                        persist = false
                    )
                }
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun showMcpServerDetails(
        server: PrimeMcpServerConfig
    ) {
        val discovery =
            mcpDiscoveryCache[server.id]

        val details = buildString {
            appendLine(server.url)
            appendLine()
            append("وضعیت: ")
            appendLine(
                if (server.enabled) "فعال" else "غیرفعال"
            )

            if (discovery != null) {
                append("Protocol: ")
                appendLine(discovery.protocolVersion)
                append("Era: ")
                appendLine(discovery.era.name)
                discovery.remoteServerName?.let {
                    append("Server: ")
                    append(it)
                    discovery.remoteServerVersion?.let {
                        version ->
                        append(" ")
                        append(version)
                    }
                    appendLine()
                }
                appendLine()
                append("Tools: ")
                appendLine(discovery.tools.size.toString())
                discovery.tools.take(12).forEach {
                    append("• ")
                    appendLine(it.name)
                }
                append("Resources: ")
                appendLine(
                    discovery.resources.size.toString()
                )
                discovery.resources.take(8).forEach {
                    append("• ")
                    appendLine(
                        it.name ?: it.uri
                    )
                }
                append("Prompts: ")
                appendLine(
                    discovery.prompts.size.toString()
                )
                discovery.prompts.take(8).forEach {
                    append("• ")
                    appendLine(it.name)
                }
            } else {
                appendLine(
                    "هنوز discovery اجرا نشده است."
                )
            }
        }.trim()

        AlertDialog.Builder(this)
            .setTitle(server.name)
            .setMessage(details)
            .setPositiveButton("Test / Refresh") { _, _ ->
                discoverMcpServer(server)
            }
            .setNeutralButton(
                if (server.enabled) "غیرفعال" else "فعال"
            ) { _, _ ->
                mcpServerStore.setEnabled(
                    server.id,
                    !server.enabled
                )
                if (server.enabled) {
                    mcpDiscoveryCache.remove(server.id)
                    mcpToolSource.removeServer(
                        server.id
                    )
                }
            }
            .setNegativeButton("حذف") { _, _ ->
                confirmRemoveMcpServer(server)
            }
            .show()
    }

    private fun confirmRemoveMcpServer(
        server: PrimeMcpServerConfig
    ) {
        AlertDialog.Builder(this)
            .setTitle("حذف MCP Server؟")
            .setMessage(
                server.name + "\n" + server.url
            )
            .setPositiveButton("حذف") { _, _ ->
                mcpServerStore.remove(server.id)
                mcpDiscoveryCache.remove(server.id)
                mcpToolSource.removeServer(
                    server.id
                )
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun warmUpMcpTools() {
        val enabled = mcpServerStore
            .list()
            .filter { it.enabled }
        if (enabled.isEmpty()) return

        appScope.launch {
            enabled.forEach { server ->
                try {
                    val discovery =
                        withContext(
                            Dispatchers.IO
                        ) {
                            mcpClient.discover(
                                server
                            )
                        }
                    mcpDiscoveryCache[
                        server.id
                    ] = discovery
                    mcpToolSource
                        .updateDiscovery(
                            discovery
                        )
                } catch (_: Exception) {
                    mcpDiscoveryCache.remove(
                        server.id
                    )
                    mcpToolSource.removeServer(
                        server.id
                    )
                }
            }
        }
    }

    private fun discoverAllMcpServers() {
        val enabled = mcpServerStore
            .list()
            .filter { it.enabled }

        if (enabled.isEmpty()) {
            appendChat(
                "PRIME",
                "MCP Server فعالی وجود ندارد.",
                persist = false
            )
            return
        }

        setBusy(
            true,
            "در حال بررسی MCP Serverها…"
        )
        appScope.launch {
            var ok = 0
            val failures = mutableListOf<String>()

            try {
                enabled.forEach { server ->
                    try {
                        val discovery =
                            withContext(
                                Dispatchers.IO
                            ) {
                                mcpClient.discover(
                                    server
                                )
                            }
                        mcpDiscoveryCache[
                            server.id
                        ] = discovery
                        mcpToolSource.updateDiscovery(
                            discovery
                        )
                        ok += 1
                    } catch (e: Exception) {
                        failures +=
                            server.name + ": " +
                                (
                                    e.message
                                        ?: "connection failed"
                                    ).take(180)
                    }
                }

                val summary = buildString {
                    append(ok)
                    append("/")
                    append(enabled.size)
                    append(
                        " MCP Server متصل شد."
                    )
                    if (failures.isNotEmpty()) {
                        appendLine()
                        failures.forEach {
                            appendLine("• $it")
                        }
                    }
                }.trim()

                appendChat(
                    "PRIME",
                    summary,
                    persist = false
                )
            } finally {
                setBusy(false)
            }
        }
    }

    private fun discoverMcpServer(
        server: PrimeMcpServerConfig
    ) {
        if (!server.enabled) {
            appendChat(
                "PRIME",
                "این MCP Server غیرفعال است.",
                persist = false
            )
            return
        }

        setBusy(
            true,
            "در حال MCP discovery…"
        )
        appScope.launch {
            try {
                val discovery =
                    withContext(Dispatchers.IO) {
                        mcpClient.discover(
                            server
                        )
                    }

                mcpDiscoveryCache[
                    server.id
                ] = discovery
                mcpToolSource.updateDiscovery(
                    discovery
                )

                appendChat(
                    "PRIME",
                    buildString {
                        append("MCP متصل شد: ")
                        appendLine(server.name)
                        append("Protocol: ")
                        appendLine(
                            discovery.protocolVersion
                        )
                        append("Tools: ")
                        append(
                            discovery.tools.size
                        )
                        append(" · Resources: ")
                        append(
                            discovery.resources.size
                        )
                        append(" · Prompts: ")
                        append(
                            discovery.prompts.size
                        )
                    },
                    persist = false
                )
            } catch (e: Exception) {
                mcpDiscoveryCache.remove(
                    server.id
                )
                appendChat(
                    "PRIME",
                    "MCP connection failed: " +
                        (
                            e.message
                                ?: "unknown error"
                            ).take(500),
                    persist = false
                )
            } finally {
                setBusy(false)
            }
        }
    }

    private fun pickImages() {
        if (isBusy) return
        imagePicker.launch(
            arrayOf(
                "image/png",
                "image/jpeg",
                "image/webp"
            )
        )
    }

    private fun ingestImages(
        uris: List<Uri>
    ) {
        if (isBusy) return
        setBusy(
            true,
            "در حال آماده‌سازی تصویر…"
        )

        appScope.launch {
            var added = 0
            val failures =
                mutableListOf<String>()

            try {
                uris.forEach { uri ->
                    try {
                        val pair =
                            withContext(
                                Dispatchers.IO
                            ) {
                                imageLoader.load(
                                    uri
                                )
                            }
                        val persistent =
                            persistReadAccess(uri)
                        val storedId =
                            if (persistent) {
                                chatStore.addAttachment(
                                    chatId = currentChatId,
                                    kind = "image",
                                    displayName =
                                        pair.first.name,
                                    mimeType =
                                        pair.first.mimeType,
                                    storageUri =
                                        uri.toString(),
                                    sizeBytes =
                                        pair.second.toLong()
                                )
                            } else {
                                null
                            }
                        imageSession.add(
                            pair.first,
                            pair.second,
                            persistedAttachmentId =
                                storedId,
                            storageUri =
                                if (persistent) {
                                    uri.toString()
                                } else {
                                    null
                                }
                        )
                        added += 1
                    } catch (e: Exception) {
                        failures +=
                            (
                                e.message
                                    ?: "تصویر قابل پردازش نیست."
                            ).take(180)
                    }
                }

                val summary =
                    buildString {
                        append(added)
                        append(
                            " تصویر آمادهٔ تحلیل است."
                        )
                        if (
                            failures.isNotEmpty()
                        ) {
                            appendLine()
                            failures.forEach {
                                appendLine(
                                    "• " + it
                                )
                            }
                        }
                        if (added > 0) {
                            appendLine()
                            append(
                                "حالا بگو «این تصویر را توضیح بده» یا دربارهٔ آن سؤال بپرس."
                            )
                        }
                    }.trim()

                appendChat(
                    "PRIME",
                    summary,
                    persist = false
                )
            } finally {
                setBusy(false)
            }
        }
    }

    private fun showImageManager() {
        val images =
            imageSession.list()
        if (images.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(
                    "تصاویر این چت"
                )
                .setMessage(
                    "هنوز تصویری اضافه نشده."
                )
                .setPositiveButton(
                    "افزودن تصویر"
                ) { _, _ ->
                    pickImages()
                }
                .setNegativeButton(
                    "بستن",
                    null
                )
                .show()
            return
        }

        val labels =
            images.map {
                image ->
                image.input.name +
                    " · " +
                    (
                        image.byteSize /
                            1024
                    ) +
                    " KiB"
            }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(
                "تصاویر این چت"
            )
            .setItems(
                labels
            ) { _, which ->
                val image =
                    images[which]
                AlertDialog.Builder(this)
                    .setTitle(
                        image.input.name
                    )
                    .setMessage(
                        "نوع: " +
                            image.input.mimeType +
                            "\nحجم: " +
                            image.byteSize +
                            " bytes"
                    )
                    .setPositiveButton(
                        "بستن",
                        null
                    )
                    .setNegativeButton(
                        "حذف"
                    ) { _, _ ->
                        imageSession.remove(
                            image.id
                        )
                        image.persistedAttachmentId
                            ?.let(
                                chatStore::deleteAttachment
                            )
                    }
                    .show()
            }
            .setNeutralButton(
                "پاک کردن همه"
            ) { _, _ ->
                imageSession.clear()
                chatStore.deleteAttachments(
                    currentChatId,
                    "image"
                )
            }
            .setPositiveButton(
                "بستن",
                null
            )
            .show()
    }

    private fun pickDocument() {
        if (isBusy) return
        filePicker.launch(
            arrayOf(
                "application/pdf",
                "text/*",
                "application/json",
                "text/csv",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            )
        )
    }

    private fun ingestAttachment(uri: Uri) {
        if (isBusy) return
        setBusy(true, "در حال خواندن فایل…")

        appScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    documentPipeline.parse(uri)
                }
                val persistent =
                    persistReadAccess(uri)
                val storedId =
                    if (persistent) {
                        chatStore.addAttachment(
                            chatId = currentChatId,
                            kind = "document",
                            displayName = parsed.name,
                            mimeType = parsed.mimeType,
                            storageUri = uri.toString(),
                            sizeBytes = queryUriSize(uri)
                        )
                    } else {
                        null
                    }
                val sessionDocument =
                    attachmentSession.add(
                        parsed,
                        persistedAttachmentId =
                            storedId,
                        storageUri =
                            if (persistent) {
                                uri.toString()
                            } else {
                                null
                            }
                    )
                appendChat(
                    "PRIME",
                    "فایل «" + sessionDocument.name + "» آماده است؛ " +
                        sessionDocument.chunkCount + " بخش متنی استخراج شد. " +
                        "حالا دربارهٔ فایل سؤال بپرس یا درخواست خلاصه بده.",
                    persist = false
                )
            } catch (e: Exception) {
                appendChat(
                    "PRIME",
                    e.message ?: "فایل قابل پردازش نیست.",
                    persist = false
                )
            } finally {
                setBusy(false)
            }
        }
    }

    private fun showAttachmentManager() {
        val documents = attachmentSession.listDocuments()
        if (documents.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("فایل‌های این چت")
                .setMessage(
                    "هنوز فایلی اضافه نشده. از دکمه + گزینهٔ «افزودن فایل» را بزن."
                )
                .setPositiveButton("باشه", null)
                .show()
            return
        }

        val labels = documents.map { sessionDocument ->
            sessionDocument.name + " · " +
                sessionDocument.chunkCount + " بخش"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("فایل‌های این چت")
            .setItems(labels) { _, which ->
                val sessionDocument = documents[which]
                AlertDialog.Builder(this)
                    .setTitle(sessionDocument.name)
                    .setMessage(
                        "نوع: " + sessionDocument.kind + "\n" +
                            "متن استخراج‌شده: " +
                            sessionDocument.textChars + " نویسه\n" +
                            "بخش‌ها: " +
                            sessionDocument.chunkCount
                    )
                    .setPositiveButton("بستن", null)
                    .setNegativeButton("حذف از چت") { _, _ ->
                        attachmentSession.remove(
                            sessionDocument.id
                        )
                        sessionDocument
                            .persistedAttachmentId
                            ?.let(
                                chatStore::deleteAttachment
                            )
                    }
                    .show()
            }
            .setNegativeButton("پاک کردن همه") { _, _ ->
                attachmentSession.clear()
                chatStore.deleteAttachments(
                    currentChatId,
                    "document"
                )
            }
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun showMemoryManager() {
        val memories = memoryStore.list(limit = 20)
        val message = if (memories.isEmpty()) {
            "حافظهٔ بلندمدت PRIME خالی است.\n\n" +
                "مثال: «یادت باشه پاسخ‌های کوتاه رو ترجیح میدم»"
        } else {
            buildString {
                appendLine("حافظه‌ها فقط با درخواست صریح تو ذخیره می‌شوند و با Android Keystore رمز می‌شوند.")
                appendLine()
                memories.forEachIndexed { index, memory ->
                    append(index + 1)
                    append(". ")
                    appendLine(memory.content.take(220))
                }
            }.trim()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("حافظهٔ PRIME")
            .setMessage(message)
            .setPositiveButton("بستن", null)

        if (memories.isNotEmpty()) {
            dialog.setNegativeButton("پاک کردن همه") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("همهٔ حافظه‌ها پاک شوند؟")
                    .setMessage(
                        "حافظهٔ بلندمدت PRIME پاک می‌شود؛ تاریخچهٔ چت‌ها حذف نمی‌شود."
                    )
                    .setPositiveButton("پاک کردن") { _, _ ->
                        memoryStore.clear()
                        Toast.makeText(
                            this,
                            "حافظهٔ PRIME پاک شد",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("لغو", null)
                    .show()
            }
        }

        dialog.show()
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
        updateComposerButtons()
        binding.tvAgentStatus.text = status ?: if (busy) "پرایم در حال انجام درخواست است…" else "آماده"
    }

    private fun pairRemoteBridge() {
        binding.btnPairRemote.isEnabled = false
        binding.tvRemoteStatus.setText(R.string.ui_remote_pairing)

        appScope.launch {
            try {
                val pairing = withContext(Dispatchers.IO) {
                    RemoteBridgeClient.pair(
                        RemoteBridgeSecurity.DEFAULT_RELAY_URL,
                        remoteBridgeSecurity.getOrCreateDeviceId(),
                        bridgeAuthToken
                    )
                }
                remoteBridgeSecurity.saveCredential(pairing.credential)
                remoteBridgeSecurity.saveMcpUrl(pairing.mcpUrl)
                remoteBridgeSecurity.setEnabled(true)
                startRemoteBridgeService()
                updateRemoteBridgeUI()
                appendLog("PRIME remote bridge registered and started")
            } catch (e: Exception) {
                remoteBridgeSecurity.setEnabled(false)
                binding.tvRemoteStatus.text =
                    "اتصال راه دور کامل نشد: " + (e.message ?: "خطای نامشخص")
                appendLog("Remote bridge registration failed: " + (e.message ?: "unknown"))
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
        val mcpUrl = remoteBridgeSecurity.mcpUrl()
        binding.tvRemoteMcpUrl.text = mcpUrl ?: "--"
        binding.btnCopyRemoteMcp.isEnabled = !mcpUrl.isNullOrBlank()
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
        activeGenerationJob?.cancel()
        activeGenerationJob = null
        appScope.cancel()
        chatStore.close()
        super.onDestroy()
    }
}
