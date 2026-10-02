package com.mobilemcp.pro.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobilemcp.pro.MainActivity
import com.mobilemcp.pro.OpenAIAuthManager
import com.mobilemcp.pro.PrimeActionResult
import com.mobilemcp.pro.PrimeChatStore
import com.mobilemcp.pro.PrimeAgent
import com.mobilemcp.pro.R
import com.mobilemcp.pro.model.CommandRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Persistent PRIME Voice.
 *
 * This is a full-screen TYPE_APPLICATION_OVERLAY owned by a microphone
 * foreground service. The overlay stays visible while PRIME launches Telegram,
 * Chrome, Settings, or another app underneath it. The user can keep speaking or
 * type the next instruction without returning to MainActivity.
 *
 * The window is normally NOT_FOCUSABLE so the underlying app remains the
 * automation target. It becomes focusable only while the user types into the
 * overlay composer, then returns to pass-through automation mode on send.
 */
class VoiceSessionForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "prime_voice_session"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.mobilemcp.pro.action.START_PERSISTENT_VOICE"
        const val ACTION_STOP = "com.mobilemcp.pro.action.STOP_PERSISTENT_VOICE"

        @Volatile
        var isOverlayRunning: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var authManager: OpenAIAuthManager
    private lateinit var primeAgent: PrimeAgent
    private lateinit var chatStore: PrimeChatStore
    private var activeChatId: Long = -1L
    private lateinit var windowManager: WindowManager

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayFocusable = false

    private var tvStatus: TextView? = null
    private var tvContext: TextView? = null
    private var editMessage: EditText? = null
    private var btnMic: MaterialButton? = null
    private var btnSend: MaterialButton? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private var sessionActive = false
    private var isBusy = false
    private var pendingConfirmationTask: String? = null

    override fun onCreate() {
        super.onCreate()

        authManager = OpenAIAuthManager(applicationContext)
        primeAgent = PrimeAgent(authManager)
        chatStore = PrimeChatStore(applicationContext)
        activeChatId = resolveActiveChatId()
        restoreActiveChatContext()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        setupTextToSpeech()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopPersistentVoice()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (!Settings.canDrawOverlays(this)) {
                    updateStatus("Overlay permission is required")
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (!authManager.isSignedIn()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                sessionActive = true
                showOverlay()
                isOverlayRunning = true

                scope.launch(Dispatchers.IO) {
                    try {
                        primeAgent.warmUp()
                    } catch (_: Exception) {
                        // Best effort only.
                    }
                }

                scope.launch {
                    delay(250)
                    startListening()
                }
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.voice_notification_channel_description)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.voice_notification_title))
            .setContentText("Persistent PRIME Voice is active over your apps")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showOverlay() {
        if (overlayView != null) {
            overlayView?.visibility = View.VISIBLE
            return
        }

        val view = LayoutInflater.from(this)
            .inflate(R.layout.overlay_voice_session, null, false)

        // Keep PRIME's own overlay out of the Accessibility tree used to drive
        // the underlying application.
        view.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        tvStatus = view.findViewById(R.id.tvOverlayVoiceStatus)
        tvContext = view.findViewById(R.id.tvOverlayContext)
        editMessage = view.findViewById(R.id.etOverlayVoiceMessage)
        btnMic = view.findViewById(R.id.btnOverlayMic)
        btnSend = view.findViewById(R.id.btnOverlaySend)

        view.findViewById<MaterialButton>(R.id.btnOverlayClose)
            .setOnClickListener {
                stopPersistentVoice()
            }

        btnMic?.setOnClickListener {
            textToSpeech?.stop()
            speechRecognizer?.cancel()
            setOverlayFocusable(false)
            startListening()
        }

        btnSend?.setOnClickListener {
            sendTypedCommand()
        }

        editMessage?.setOnEditorActionListener { _, _, _ ->
            sendTypedCommand()
            true
        }

        editMessage?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                pauseListeningForTyping()
                setOverlayFocusable(true)
            }
            false
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        overlayView = view
        overlayParams = params
        overlayFocusable = false

        windowManager.addView(view, params)
        updateStatus("Listening…")
        updateContext("Persistent voice • P6 • apps stay underneath")
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val view = overlayView ?: return
        val params = overlayParams ?: return

        if (overlayFocusable == focusable) {
            if (focusable) {
                editMessage?.requestFocus()
                showKeyboard()
            }
            return
        }

        overlayFocusable = focusable

        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            return
        }

        if (focusable) {
            editMessage?.requestFocus()
            showKeyboard()
        } else {
            hideKeyboard()
            editMessage?.clearFocus()
        }
    }

    private fun showKeyboard() {
        val editor = editMessage ?: return
        editor.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val view = overlayView ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
            as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun pauseListeningForTyping() {
        speechRecognizer?.cancel()
        updateStatus("Type your next command…")
    }

    private fun sendTypedCommand() {
        val text = editMessage?.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || isBusy) return

        editMessage?.setText("")
        setOverlayFocusable(false)
        scope.launch {
            delay(220)
            handleInput(text)
        }
    }

    private fun setupTextToSpeech() {
        textToSpeech = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true

                textToSpeech?.setSpeechRate(1.0f)
                textToSpeech?.setPitch(1.0f)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    textToSpeech?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }

                textToSpeech?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            scope.launch {
                                updateStatus("PRIME is speaking…")
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            scope.launch {
                                updateStatus(
                                    "Voice output failed • tap mic or type again"
                                )
                                if (sessionActive && !isBusy) {
                                    delay(500)
                                    startListening()
                                }
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            scope.launch {
                                if (sessionActive && !isBusy) {
                                    delay(350)
                                    startListening()
                                }
                            }
                        }
                    }
                )

                val queued = pendingSpeech
                pendingSpeech = null
                if (!queued.isNullOrBlank()) {
                    scope.launch {
                        delay(80)
                        speak(queued)
                    }
                }
            } else {
                ttsReady = false
                updateStatus("Voice output is unavailable on this phone")
            }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) {
            scope.launch { startListening() }
            return
        }

        if (!ttsReady) {
            pendingSpeech = text
            updateStatus("Preparing PRIME voice…")
            return
        }

        val tts = textToSpeech
        if (tts == null) {
            pendingSpeech = text
            updateStatus("Preparing PRIME voice…")
            return
        }

        val language = if (text.any { it.code in 0x0600..0x06FF }) {
            Locale("fa", "IR")
        } else {
            Locale.getDefault()
        }

        val result = tts.setLanguage(language)
        if (
            result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            tts.setLanguage(Locale.getDefault())
        }

        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        ) {
            updateStatus("Media volume is muted")
        }

        val params = Bundle().apply {
            putInt(
                TextToSpeech.Engine.KEY_PARAM_STREAM,
                AudioManager.STREAM_MUSIC
            )
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val resultCode = tts.speak(
            text.take(2500),
            TextToSpeech.QUEUE_FLUSH,
            params,
            "prime-overlay-" + System.currentTimeMillis()
        )

        if (resultCode == TextToSpeech.ERROR) {
            updateStatus("Voice output failed • check Text-to-Speech settings")
            scope.launch {
                delay(650)
                startListening()
            }
        }
    }

    private fun ensureSpeechRecognizer(): Boolean {
        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus("Microphone permission is required")
            return false
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("Speech recognition is unavailable on this phone")
            return false
        }

        if (speechRecognizer != null) return true

        speechRecognizer = SpeechRecognizer
            .createSpeechRecognizer(this)
            .also { recognizer ->
                recognizer.setRecognitionListener(
                    object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            updateStatus("Listening…")
                        }

                        override fun onBeginningOfSpeech() {
                            updateStatus("Listening…")
                        }

                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit

                        override fun onEndOfSpeech() {
                            updateStatus("Understanding…")
                        }

                        override fun onError(error: Int) {
                            if (!sessionActive || isBusy || overlayFocusable) return

                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                                SpeechRecognizer.ERROR_CLIENT -> {
                                    scope.launch {
                                        delay(600)
                                        if (
                                            sessionActive &&
                                            !isBusy &&
                                            !overlayFocusable
                                        ) {
                                            startListening()
                                        }
                                    }
                                }

                                else -> {
                                    updateStatus("Voice paused • tap the mic to retry")
                                }
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val spoken = results
                                ?.getStringArrayList(
                                    SpeechRecognizer.RESULTS_RECOGNITION
                                )
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty()

                            if (spoken.isBlank()) {
                                scope.launch {
                                    delay(450)
                                    startListening()
                                }
                                return
                            }

                            updateStatus("You: " + spoken.take(120))
                            handleInput(spoken)
                        }

                        override fun onPartialResults(
                            partialResults: Bundle?
                        ) = Unit

                        override fun onEvent(
                            eventType: Int,
                            params: Bundle?
                        ) = Unit
                    }
                )
            }

        return true
    }

    private fun startListening() {
        if (
            !sessionActive ||
            isBusy ||
            overlayFocusable ||
            textToSpeech?.isSpeaking == true
        ) {
            return
        }

        if (!ensureSpeechRecognizer()) return

        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
            // Reset best effort.
        }

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
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

        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            updateStatus("Voice paused • tap the mic to retry")
        }
    }

    private fun handleInput(input: String) {
        if (input.isBlank() || isBusy) return

        ensureActiveChat()
        chatStore.appendMessage(activeChatId, "user", input)

        setOverlayFocusable(false)
        speechRecognizer?.cancel()
        textToSpeech?.stop()

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
                    updateStatus("Cancelled")
                    speak("لغو شد.")
                    return
                }

                else -> {
                    pendingConfirmationTask = null
                }
            }
        }

        isBusy = true
        setControlsEnabled(false)
        updateStatus("Thinking…")

        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    primeAgent.run(
                        userText = task,
                        confirmedForTask = confirmed,
                        uiProvider = { readUiState() },
                        actionRunner = { command, params ->
                            executePrimeAction(command, params)
                        },
                        onProgress = { progress ->
                            scope.launch {
                                updateStatus(progress)
                            }
                        }
                    )
                }

                if (outcome.needsConfirmation) {
                    pendingConfirmationTask = task
                }

                chatStore.appendMessage(
                    activeChatId,
                    "assistant",
                    outcome.text
                )

                isBusy = false
                setControlsEnabled(true)
                speak(outcome.text)
            } catch (e: Exception) {
                isBusy = false
                setControlsEnabled(true)

                val message = userFriendlyError(e)
                updateStatus("Connection issue")
                speak(message)
            }
        }
    }

    private fun userFriendlyError(e: Exception): String {
        val message = e.message.orEmpty()

        return when {
            message.contains("PRIME_API_DNS") ||
                message.contains("PRIME_NETWORK_DNS") ||
                message.contains(
                    "Unable to resolve host",
                    ignoreCase = true
                ) ->
                "اتصال پرایم به اوپن ای آی از سمت دی ان اس یا وی پی ان قطع شده. دوباره امتحان کن."

            message.contains("PRIME_API_NETWORK") ||
                message.contains(
                    "unexpected end of stream",
                    ignoreCase = true
                ) ->
                "ارتباط لحظه‌ای قطع شد. دوباره امتحان کن."

            message.contains("usage limit", ignoreCase = true) ->
                "سهمیه فعلی چت جی پی تی به حدش رسیده."

            message.contains("expired", ignoreCase = true) ->
                "اتصال چت جی پی تی نیاز به ورود دوباره دارد."

            else ->
                "این دستور کامل نشد. دوباره امتحان کن."
        }
    }

    private suspend fun readUiState(): String =
        withContext(Dispatchers.Default) {
            val service = MobileAccessibilityService.instance
                ?: return@withContext(
                    "ACCESSIBILITY_OFF: PRIME cannot read or operate " +
                        "the current app until Accessibility is enabled."
                    )

            val params = JsonObject().apply {
                addProperty("maxDepth", 12)
            }

            val response = service.handleCommand(
                CommandRequest(
                    "prime_overlay_ui",
                    "get_ui_tree",
                    params
                )
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
        setOverlayFocusable(false)

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

            else -> {
                val needsPassThrough = command in setOf(
                    "tap",
                    "long_press",
                    "swipe",
                    "scroll"
                )

                if (needsPassThrough) {
                    temporarilyHideOverlay {
                        executeAccessibilityCommand(command, params)
                    }
                } else {
                    executeAccessibilityCommand(command, params)
                }
            }
        }
    }

    private suspend fun temporarilyHideOverlay(
        block: suspend () -> PrimeActionResult
    ): PrimeActionResult {
        withContext(Dispatchers.Main) {
            overlayView?.visibility = View.INVISIBLE
        }

        delay(140)

        return try {
            block()
        } finally {
            delay(140)
            withContext(Dispatchers.Main) {
                if (sessionActive) {
                    overlayView?.visibility = View.VISIBLE
                }
            }
        }
    }

    private suspend fun executeAccessibilityCommand(
        command: String,
        params: JSONObject
    ): PrimeActionResult = withContext(Dispatchers.Default) {
        val allowed = setOf(
            "click_element",
            "tap",
            "long_press",
            "set_text",
            "type_text",
            "scroll",
            "swipe",
            "press_key",
            "wait_for_element",
            "get_focused"
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
                "Accessibility Service is OFF."
            )

        val gsonParams = try {
            JsonParser.parseString(params.toString()).asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }

        val response = service.handleCommand(
            CommandRequest(
                "prime_overlay_action",
                command,
                gsonParams
            )
        )

        if (response.success) {
            PrimeActionResult(
                true,
                response.data?.toString()?.take(2500) ?: "OK"
            )
        } else {
            PrimeActionResult(
                false,
                response.error ?: "Action failed"
            )
        }
    }

    private suspend fun openApp(
        params: JSONObject
    ): PrimeActionResult = withContext(Dispatchers.Main) {
        val explicitPackage = params
            .optString("package")
            .takeIf { it.isNotBlank() }

        val requestedName = params
            .optString("name")
            .takeIf { it.isNotBlank() }

        val packageName = explicitPackage
            ?: requestedName?.let { resolveAppPackage(it) }

        if (packageName.isNullOrBlank()) {
            return@withContext PrimeActionResult(
                false,
                "App not found"
            )
        }

        val launchIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?: return@withContext PrimeActionResult(
                false,
                "App is not launchable: $packageName"
            )

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        startActivity(launchIntent)
        updateContext(
            "Persistent voice • " +
                (requestedName ?: packageName)
        )

        PrimeActionResult(
            true,
            "Opened " + (requestedName ?: packageName)
        )
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
            if (
                packageManager.getLaunchIntentForPackage(known) != null
            ) {
                return known
            }
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        val activities = packageManager
            .queryIntentActivities(launcherIntent, 0)

        val exact = activities.firstOrNull {
            it.loadLabel(packageManager)
                .toString()
                .trim()
                .equals(name, ignoreCase = true)
        }

        if (exact != null) {
            return exact.activityInfo.packageName
        }

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
        if (raw.isBlank()) {
            return@withContext PrimeActionResult(
                false,
                "URL is missing"
            )
        }

        val uri = Uri.parse(raw)
        if (uri.scheme !in listOf("http", "https")) {
            return@withContext PrimeActionResult(
                false,
                "Only http/https URLs are allowed"
            )
        }

        return@withContext try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
            updateContext("Persistent voice • browser")
            PrimeActionResult(true, "Opened $raw")
        } catch (e: Exception) {
            PrimeActionResult(
                false,
                e.message ?: "Could not open URL"
            )
        }
    }

    private fun isPositiveConfirmation(value: String): Boolean {
        val normalized = value
            .trim()
            .lowercase(Locale.getDefault())

        return normalized in setOf(
            "بله",
            "آره",
            "اره",
            "اوکی",
            "باشه",
            "تایید",
            "تأیید",
            "انجام بده",
            "ارسال کن",
            "yes",
            "ok",
            "okay",
            "confirm",
            "do it"
        )
    }

    private fun isNegativeConfirmation(value: String): Boolean {
        val normalized = value
            .trim()
            .lowercase(Locale.getDefault())

        return normalized in setOf(
            "نه",
            "خیر",
            "لغو",
            "بیخیال",
            "cancel",
            "no",
            "stop"
        )
    }

    private fun resolveActiveChatId(): Long {
        val prefs = getSharedPreferences(
            PrimeChatStore.PREFS,
            Context.MODE_PRIVATE
        )

        val saved = prefs.getLong(
            PrimeChatStore.ACTIVE_CHAT_ID,
            -1L
        )

        if (saved > 0L && chatStore.chatExists(saved)) {
            return saved
        }

        val existing = chatStore.listChats().firstOrNull()?.id
        val resolved = existing ?: chatStore.createChat()

        prefs.edit()
            .putLong(PrimeChatStore.ACTIVE_CHAT_ID, resolved)
            .apply()

        return resolved
    }

    private fun ensureActiveChat() {
        val prefs = getSharedPreferences(
            PrimeChatStore.PREFS,
            Context.MODE_PRIVATE
        )
        val saved = prefs.getLong(
            PrimeChatStore.ACTIVE_CHAT_ID,
            activeChatId
        )

        if (
            saved > 0L &&
            saved != activeChatId &&
            chatStore.chatExists(saved)
        ) {
            activeChatId = saved
            restoreActiveChatContext()
            return
        }

        if (activeChatId <= 0L || !chatStore.chatExists(activeChatId)) {
            activeChatId = resolveActiveChatId()
            restoreActiveChatContext()
        }
    }

    private fun restoreActiveChatContext() {
        if (activeChatId <= 0L || !chatStore.chatExists(activeChatId)) {
            return
        }

        val lines = chatStore.messages(activeChatId)
            .map { it.role to it.content }

        primeAgent.restoreConversation(lines)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        btnMic?.isEnabled = enabled
        btnSend?.isEnabled = enabled
        editMessage?.isEnabled = enabled
    }

    private fun updateStatus(value: String) {
        tvStatus?.text = value
    }

    private fun updateContext(value: String) {
        tvContext?.text = value
    }

    private fun stopPersistentVoice() {
        sessionActive = false
        isOverlayRunning = false
        isBusy = false
        pendingConfirmationTask = null

        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        textToSpeech?.stop()
        hideKeyboard()

        val view = overlayView
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }

        overlayView = null
        overlayParams = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sessionActive = false
        isOverlayRunning = false

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        val view = overlayView
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }

        overlayView = null
        overlayParams = null
        scope.cancel()

        super.onDestroy()
    }
}
