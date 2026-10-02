package com.mobilemcp.pro.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobilemcp.pro.MainActivity
import com.mobilemcp.pro.OpenAIAuthManager
import com.mobilemcp.pro.PrimeActionResult
import com.mobilemcp.pro.PrimeChatStore
import com.mobilemcp.pro.PrimeAgent
import com.mobilemcp.pro.R
import com.mobilemcp.pro.voice.PersianSpeech
import com.mobilemcp.pro.voice.SpeechText
import com.mobilemcp.pro.voice.VoicePreferences
import com.mobilemcp.pro.PersianInput
import com.mobilemcp.pro.model.CommandRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
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

        @Volatile
        var lastStartError: String? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var authManager: OpenAIAuthManager
    private lateinit var primeAgent: PrimeAgent
    private lateinit var chatStore: PrimeChatStore
    private var runtimeInitialized = false
    private var activeChatId: Long = -1L
    private lateinit var windowManager: WindowManager

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayFocusable = false

    private var tvStatus: TextView? = null
    private var tvContext: TextView? = null
    private var editMessage: EditText? = null
    private var btnMic: ImageButton? = null
    private var btnSend: ImageButton? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceSpeech: PersianSpeech? = null
    private var ttsReady = false
    private var ttsSpeaking = false
    private val speechQueue = ArrayDeque<String>()
    private val replyBuffer = StringBuilder()
    private var tvReply: TextView? = null
    private var agentJob: Job? = null
    private var listening = false
    private var minimized = false
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var sessionActive = false
    private var isBusy = false
    private var pendingConfirmationTask: String? = null

    override fun onCreate() {
        super.onCreate()

        // Keep onCreate intentionally lightweight. Samsung/Android 14 may
        // construct the service before microphone foreground eligibility has
        // fully settled. Risky runtime objects are created only after PRIME is
        // successfully promoted to a microphone foreground service.
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    private fun initializeRuntime() {
        if (runtimeInitialized) return

        authManager = OpenAIAuthManager(applicationContext)
        primeAgent = PrimeAgent(authManager)
        chatStore = PrimeChatStore(applicationContext)
        activeChatId = resolveActiveChatId()
        restoreActiveChatContext()
        setupTextToSpeech()

        runtimeInitialized = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopPersistentVoice()
            return START_NOT_STICKY
        }

        if (sessionActive) return START_NOT_STICKY
        lastStartError = null

        try {
            if (
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException(
                    "PRIME Voice needs microphone permission before starting"
                )
            }

            if (!Settings.canDrawOverlays(this)) {
                throw SecurityException(
                    "PRIME Voice needs Display over other apps permission"
                )
            }

            val foregroundType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                foregroundType
            )

            initializeRuntime()

            if (!authManager.isSignedIn()) {
                throw IllegalStateException(
                    "Connect ChatGPT before starting PRIME Voice"
                )
            }

            sessionActive = true
            showOverlay()
            isOverlayRunning = true

            scope.launch(Dispatchers.IO) {
                try {
                    primeAgent.warmUp()
                } catch (_: Throwable) {
                    // Best effort only. The first request can warm up instead.
                }
            }

            // Audible startup check: this proves the device TTS path works
            // before the first user request. If TTS is still initializing,
            // the phrase stays queued and is spoken as soon as it is ready.
            enqueueSpeech("پرایم آماده است", flush = true)

            scope.launch {
                delay(2_500)
                if (
                    sessionActive &&
                    !ttsReady &&
                    !isBusy &&
                    !overlayFocusable
                ) {
                    updateStatus(
                        "آماده‌سازی صدای فارسی…"
                    )
                }
            }

            return START_NOT_STICKY
        } catch (t: Throwable) {
            handleStartFailure(t)
            return START_NOT_STICKY
        }
    }

    private fun handleStartFailure(error: Throwable) {
        sessionActive = false
        isOverlayRunning = false

        val compact = buildString {
            append(error::class.java.simpleName)
            val message = error.message?.trim().orEmpty()
            if (message.isNotBlank()) {
                append(": ")
                append(message.take(240))
            }
        }

        lastStartError = compact

        try {
            getSharedPreferences(
                "prime_voice_diagnostics",
                Context.MODE_PRIVATE
            ).edit()
                .putString("last_error", compact)
                .putLong("last_error_at", System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }

        stopSelf()
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
        tvReply = view.findViewById(R.id.tvOverlayReply)
        view.findViewById<TextView>(R.id.btnOverlayMinimize).setOnClickListener {
            minimized = !minimized
            view.findViewById<View>(R.id.voiceOverlayBody).visibility = if (minimized) View.GONE else View.VISIBLE
            overlayParams?.let { params ->
                params.height = if (minimized) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT
                params.gravity = if (minimized) Gravity.BOTTOM else Gravity.TOP
                windowManager.updateViewLayout(view, params)
            }
        }
        editMessage = view.findViewById(R.id.etOverlayVoiceMessage)
        btnMic = view.findViewById(R.id.btnOverlayMic)
        btnSend = view.findViewById(R.id.btnOverlaySend)

        view.findViewById<ImageButton>(R.id.btnOverlayClose)
            .setOnClickListener {
                stopPersistentVoice()
            }

        btnMic?.setOnClickListener {
            voiceSpeech?.stop()
            ttsSpeaking = false
            speechQueue.clear()
            abandonSpeechAudioFocus()
            listening = false
            speechRecognizer?.cancel()
            setOverlayFocusable(false)
            startListening()
        }

        btnSend?.setOnClickListener {
            sendTypedCommand()
        }

        editMessage?.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { sendTypedCommand(); true } else false
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

        try {
            windowManager.addView(view, params)
        } catch (t: Throwable) {
            overlayView = null
            overlayParams = null
            throw IllegalStateException(
                "Could not create PRIME Voice overlay",
                t
            )
        }

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
        listening = false
        speechRecognizer?.cancel()
        updateStatus("Type your next command…")
    }

    private fun sendTypedCommand() {
        val text = editMessage?.text?.toString()?.trim().orEmpty()
        if (text.isBlank() || isBusy) return

        editMessage?.setText("")
        setOverlayFocusable(false)
        scope.launch {
            delay(60)
            handleInput(text)
        }
    }

    private fun setupTextToSpeech() {
        voiceSpeech = PersianSpeech(applicationContext, object : PersianSpeech.Listener {
            override fun onReady() {
                ttsReady = true
                updateStatus("صدای فارسی آماده است")
                pumpSpeechQueue()
            }
            override fun onStart() { updateStatus("پرایم در حال صحبت است…") }
            override fun onDone() {
                ttsSpeaking = false
                pumpSpeechQueue()
            }
            override fun onError(message: String) {
                onSpeechError(message)
            }
        }).also { it.prepare() }
    }

    private fun requestSpeechAudioFocus() {
        try {
            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        scope.launch {
                            voiceSpeech?.stop()
                            ttsSpeaking = false
                            speechQueue.clear()
                            listening = false
                            speechRecognizer?.cancel()
                            updateStatus("صدا متوقف شد · برای ادامه میکروفن را بزن")
                        }
                    }
                }
                .build()

            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } catch (_: Throwable) {
            // TTS can still speak even if a vendor audio stack rejects focus.
        }
    }

    private fun abandonSpeechAudioFocus() {
        val request = audioFocusRequest ?: return
        try {
            audioManager.abandonAudioFocusRequest(request)
        } catch (_: Throwable) {
        }
        audioFocusRequest = null
    }

    private fun enqueueSpeech(
        text: String,
        flush: Boolean = false
    ) {
        val clean = text.trim()
        if (clean.isBlank()) {
            if (sessionActive && !isBusy) {
                startListening()
            }
            return
        }

        listening = false
        speechRecognizer?.cancel()

        if (flush) {
            try {
                voiceSpeech?.stop()
            } catch (_: Throwable) {
            }
            ttsSpeaking = false
            speechQueue.clear()
        }

        speechQueue.addAll(SpeechText.chunks(clean))

        if (!ttsReady) {
            updateStatus("Preparing PRIME voice…")
            return
        }

        pumpSpeechQueue()
    }

    private fun pumpSpeechQueue() {
        if (!ttsReady || ttsSpeaking) return

        if (speechQueue.isEmpty()) {
            abandonSpeechAudioFocus()
            if (sessionActive && !isBusy && !overlayFocusable) {
                scope.launch {
                    delay(220)
                    startListening()
                }
            }
            return
        }

        val tts = voiceSpeech
        if (tts == null) {
            ttsReady = false
            updateStatus("Voice output unavailable")
            if (sessionActive && !isBusy) startListening()
            return
        }

        val next = speechQueue.removeFirst()
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            updateStatus("صدای رسانهٔ گوشی بسته است")
        }
        requestSpeechAudioFocus()
        ttsSpeaking = true
        tts.speak(next)
    }

    private fun onSpeechError(reason: String) {
        ttsSpeaking = false
        lastStartError = reason
        speechQueue.clear()
        abandonSpeechAudioFocus()

        if (speechQueue.isNotEmpty()) {
            pumpSpeechQueue()
        } else {
            updateStatus(
                "پخش صدا ناموفق بود · $reason"
            )
            if (sessionActive && !isBusy && !overlayFocusable) {
                scope.launch {
                    delay(500)
                    startListening()
                }
            }
        }
    }

    private fun speak(text: String) {
        enqueueSpeech(text, flush = false)
    }

    private fun resetStreamSpeech() { replyBuffer.setLength(0) }

    private fun acceptStreamSpeechDelta(delta: String) {
        replyBuffer.append(delta)
        tvReply?.text = replyBuffer.toString()
    }

    private fun finishStreamSpeech(fullReply: String) {
        tvReply?.text = fullReply
        replyBuffer.setLength(0)
        enqueueSpeech(fullReply)
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

        speechRecognizer = try {
            SpeechRecognizer
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
                            listening = false
                            if (!sessionActive || isBusy || overlayFocusable || ttsSpeaking || speechQueue.isNotEmpty()) return

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
                            listening = false
                            if (!sessionActive || isBusy || overlayFocusable || ttsSpeaking) return
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
        } catch (t: Throwable) {
            lastStartError =
                "SpeechRecognizer: " +
                    (t.message ?: t::class.java.simpleName)
            updateStatus("Voice input failed • tap mic to retry")
            null
        }

        return speechRecognizer != null
    }

    private fun startListening() {
        if (
            !sessionActive ||
            isBusy ||
            overlayFocusable ||
            ttsSpeaking ||
            speechQueue.isNotEmpty() ||
            listening ||
            voiceSpeech?.isSpeaking == true
        ) {
            return
        }

        if (!ensureSpeechRecognizer()) return

        try {
            listening = false
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
                VoicePreferences.language(this@VoiceSessionForegroundService)
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            listening = true
            speechRecognizer?.startListening(intent)
        } catch (t: Throwable) {
            listening = false
            lastStartError =
                "Speech start: " +
                    (t.message ?: t::class.java.simpleName)
            updateStatus("Voice paused • tap the mic to retry")
        }
    }

    private fun handleInput(input: String) {
        if (input.isBlank() || isBusy || !sessionActive) return

        ensureActiveChat()
        restoreActiveChatContext()
        chatStore.appendMessage(activeChatId, "user", input)
        tvReply?.text = "شما: $input"

        setOverlayFocusable(false)
        listening = false
        speechRecognizer?.cancel()

        // A new user turn owns the audio channel. Only completed Responses
        // output is spoken; partial/error responses stay visible as text.
        try {
            voiceSpeech?.stop()
        } catch (_: Throwable) {
        }
        ttsSpeaking = false
        speechQueue.clear()
        abandonSpeechAudioFocus()
        resetStreamSpeech()

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
        resetStreamSpeech()
        setControlsEnabled(false)
        updateStatus("Thinking…")

        agentJob = scope.launch {
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
                        },
                        onTextDelta = { delta ->
                            scope.launch {
                                acceptStreamSpeechDelta(delta)
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
                finishStreamSpeech(outcome.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isBusy = false
                setControlsEnabled(true)

                val message = userFriendlyError(e)
                resetStreamSpeech()
                voiceSpeech?.stop()
                ttsSpeaking = false
                speechQueue.clear()
                tvReply?.text = message
                updateStatus("Connection issue")
                enqueueSpeech(message, flush = false)
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

    private fun isPositiveConfirmation(value: String) = PersianInput.isPositiveConfirmation(value)
    private fun isNegativeConfirmation(value: String) = PersianInput.isNegativeConfirmation(value)

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
            pendingConfirmationTask = null
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
        agentJob?.cancel()

        try {
            listening = false
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        voiceSpeech?.stop()
        speechQueue.clear()
        resetStreamSpeech()
        abandonSpeechAudioFocus()
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

        voiceSpeech?.stop()
        voiceSpeech?.close()
        voiceSpeech = null
        speechQueue.clear()
        resetStreamSpeech()
        abandonSpeechAudioFocus()

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
