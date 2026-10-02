package com.mobilemcp.pro.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Owns native inference and PCM playback; no system Persian TTS or API key. */
class PersianSpeech(context: Context, private val listener: Listener) {
    interface Listener {
        fun onReady() {}
        fun onStart() {}
        fun onDone() {}
        fun onError(message: String) {}
    }
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nativeMutex = Mutex()
    private var engine: OfflineTts? = null
    private var preparation: Job? = null
    private var playback: Job? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var generation = 0
    private var closed = false
    val isSpeaking: Boolean get() = playback?.isActive == true

    fun prepare() {
        if (closed) return
        if (engine != null) { listener.onReady(); return }
        if (preparation?.isActive == true) return
        preparation = scope.launch {
            try {
                val dir = PersianVoicePack.ensureInstalled(appContext)
                withContext(Dispatchers.IO) {
                    nativeMutex.withLock {
                        ensureActive()
                        if (engine == null) engine = OfflineTts(config = OfflineTtsConfig(
                            model = OfflineTtsModelConfig(
                                vits = OfflineTtsVitsModelConfig(
                                    model = File(dir, PersianVoicePack.MODEL).path,
                                    tokens = File(dir, "tokens.txt").path,
                                    dataDir = File(dir, "espeak-ng-data").path
                                ), numThreads = 2, provider = "cpu"
                            ), maxNumSentences = 1
                        ))
                    }
                }
                if (!closed) listener.onReady()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!closed) listener.onError(e.message ?: "صدای فارسی آماده نشد.") }
            catch (e: LinkageError) { if (!closed) listener.onError("موتور صدا با معماری این گوشی سازگار نیست.") }
        }
    }

    fun speak(text: String) {
        stop()
        if (closed) return
        val voice = engine ?: run { listener.onError("صدای فارسی هنوز آماده نیست."); return }
        val turn = generation
        playback = scope.launch {
            try {
                listener.onStart()
                withContext(Dispatchers.IO) {
                    nativeMutex.withLock {
                        for (chunk in SpeechText.chunks(text)) {
                            ensureActive()
                            val sampleRate = voice.sampleRate()
                            val audio = AudioTrack.Builder()
                                .setAudioAttributes(AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                                .setBufferSizeInBytes(maxOf(16384, AudioTrack.getMinBufferSize(sampleRate,
                                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)))
                                .setTransferMode(AudioTrack.MODE_STREAM).build()
                            track = audio
                            try {
                                audio.play()
                                var frames = 0
                                val job = coroutineContext[Job]!!
                                var writeError = false
                                voice.generateWithCallback(chunk, speed = VoicePreferences.speed(appContext)) { samples ->
                                    if (!job.isActive || turn != generation) 0 else {
                                        var offset = 0
                                        while (offset < samples.size && job.isActive) {
                                            val written = audio.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                                            if (written <= 0) { writeError = true; break }
                                            frames += written
                                            offset += written
                                        }
                                        if (writeError || !job.isActive) 0 else 1
                                    }
                                }
                                ensureActive()
                                check(!writeError && frames > 0) { "پخش صدا انجام نشد؛ خروجی صوتی گوشی را بررسی کن." }
                                // WRITE_BLOCKING means queued, not heard. Wait for the
                                // final samples before allowing the microphone to restart.
                                withTimeout((frames * 1000L / sampleRate) + 4000L) {
                                    while (audio.playbackHeadPosition.toLong() < frames.toLong()) {
                                        delay(20)
                                    }
                                }
                            } finally {
                                if (track === audio) track = null
                                runCatching { audio.stop() }
                                audio.release()
                            }
                        }
                    }
                }
                if (!closed && turn == generation) listener.onDone()
            } catch (e: TimeoutCancellationException) {
                if (!closed && turn == generation) listener.onError("پخش صدا متوقف شد؛ خروجی صوتی گوشی را بررسی کن.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!closed && turn == generation) listener.onError(e.message ?: "پخش صدا ناموفق بود.") }
        }
    }

    fun stop() {
        generation++
        playback?.cancel()
        playback = null
        runCatching { track?.pause(); track?.flush() }
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
        preparation?.cancel()
        // The native pointer must not be released while generation is in JNI.
        scope.launch(Dispatchers.IO) {
            nativeMutex.withLock { engine?.release(); engine = null }
            scope.cancel()
        }
    }
}
