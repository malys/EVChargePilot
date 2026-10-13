package com.evsuite.chargepilot

import android.app.ActivityManager
import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.saic.SaicTts
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The coach's voice: the car's own first, an Android engine only as the fallback.
 *
 * **Why the coach was silent on the car.** It spoke through `android.speech.tts`, and the MG4 head
 * unit has no Android TTS engine: it announces tyre faults and navigation through the SAIC
 * `voicetts` vendor service. The engine never initialised, `speakerReady` never turned true, and
 * every line was dropped without a trace. EVTasker's `Speaker` found this first; this is the same
 * order — [SaicTts] through EVHardware, then a per-utterance platform engine for a bench emulator.
 *
 * Every call runs on one worker thread, in order: the bind is asynchronous and the fallback blocks
 * until it has spoken, and neither belongs on the main thread.
 */
object EcoVoice {

    private const val TAG = "EcoVoice"
    private const val UTTERANCE_ID = "chargepilot-eco"

    /** The vendor bind is asynchronous; a first line right after boot waits this long for it. */
    private const val BIND_WAIT_MS = 3_000L
    private const val BIND_POLL_MS = 100L
    private const val INIT_TIMEOUT_SECONDS = 5L
    private const val SPEAK_TIMEOUT_SECONDS = 30L

    /** CP-081/083: enough to cover a drive's coach lines and the test, bounded against a loop. */
    private const val MAX_EVENTS = 20

    /** Who asked for a line. */
    enum class Origin { TEST, COACH, TRIP_END }

    /** Which voice took it; [NONE] when neither did. */
    enum class Path { VEHICLE, PLATFORM, NONE }

    /**
     * One dispatch and what became of it, for the diagnostic report.
     *
     * The vendor prompt has no completion callback, so [Path.VEHICLE] means the car's voice
     * service accepted the line, never that it was heard. [importance] is this process's own at
     * dispatch: above `IMPORTANCE_FOREGROUND` (100) the app was not in front.
     */
    data class Event(
        val atMs: Long,
        val origin: Origin,
        val path: Path,
        val bound: Boolean,
        val waitedMs: Long,
        val importance: Int?,
    ) {
        fun line(): String = "voice_event=$atMs origin=${origin.name.lowercase()} " +
            "path=${path.name.lowercase()} bound=$bound waited_ms=$waitedMs importance=$importance"
    }

    /** In memory only, guarded by itself: the report reads it, the worker writes it. */
    private val events = ArrayDeque<Event>()

    /** The newest [MAX_EVENTS] outcomes, oldest first. */
    fun recentEvents(): List<Event> = synchronized(events) { events.toList() }

    private fun remember(event: Event) = synchronized(events) {
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(event)
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-voice").apply { isDaemon = true }
    }

    /** Idempotent. Called at start-up so the first line does not wait for the bind. */
    fun connect(context: Context) {
        runCatching { SaicTts.connect(context.applicationContext) }
            .onFailure { AppLogger.w(TAG, "vehicle voice bind failed: ${it.message}") }
    }

    /**
     * Speaks [text] and reports, on the worker thread, whether anything took it.
     *
     * @param origin who asked, recorded with the outcome.
     * @param interrupt true only for the driver's own test from the settings screen; a coach line
     *   queues behind whatever the car is already announcing.
     */
    fun say(
        context: Context,
        text: String,
        origin: Origin,
        interrupt: Boolean = false,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        if (text.isBlank()) {
            onResult?.invoke(false)
            return
        }
        val app = context.applicationContext
        val atMs = System.currentTimeMillis()
        val importance = runCatching {
            ActivityManager.RunningAppProcessInfo()
                .also { ActivityManager.getMyMemoryState(it) }.importance
        }.getOrNull()
        runCatching {
            worker.execute {
                val outcome = runCatching { speakBlocking(app, text, interrupt) }
                    .onFailure { AppLogger.w(TAG, "speech failed: ${it.message}") }
                    .getOrNull()
                remember(
                    Event(
                        atMs, origin, outcome?.first ?: Path.NONE,
                        SaicTts.isAvailable, outcome?.second ?: 0L, importance,
                    )
                )
                val spoken = outcome != null && outcome.first != Path.NONE
                runCatching { onResult?.invoke(spoken) }
            }
        }.onFailure { onResult?.invoke(false) }
    }

    /** The path that took the line, and how long the vendor bind was waited for. */
    private fun speakBlocking(app: Context, text: String, interrupt: Boolean): Pair<Path, Long> {
        connect(app)
        var waited = 0L
        while (!SaicTts.isAvailable && waited < BIND_WAIT_MS) {
            Thread.sleep(BIND_POLL_MS)
            waited += BIND_POLL_MS
        }
        if (SaicTts.isAvailable && SaicTts.speak(text, interrupt, app.packageName)) {
            AppLogger.d(TAG, "spoken by the vehicle voice service")
            return Path.VEHICLE to waited
        }
        return (if (speakWithPlatformEngine(app, text)) Path.PLATFORM else Path.NONE) to waited
    }

    /**
     * The fallback, created per utterance and released once it has spoken: a resident engine would
     * hold an audio client for the whole ignition cycle for a few lines per drive.
     */
    private fun speakWithPlatformEngine(app: Context, text: String): Boolean {
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val engine = TextToSpeech(app) { result ->
            status = result
            ready.countDown()
        }
        try {
            if (!ready.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) ||
                status != TextToSpeech.SUCCESS
            ) {
                AppLogger.w(TAG, "no voice: vehicle service absent and no platform engine")
                return false
            }
            // The head unit ducks the radio for an assistant rather than stopping it.
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // CP-077: the line was resolved against the app's language, so the voice follows it.
            // A French sentence read with English phonemes is not worth saying.
            val language = engine.setLanguage(app.resources.configuration.locales[0])
            if (language == TextToSpeech.LANG_MISSING_DATA ||
                language == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                AppLogger.w(TAG, "platform engine has no voice for the app language")
                return false
            }
            val done = CountDownLatch(1)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = done.countDown()
                @Deprecated("Kept for API < 33; the platform calls this overload.")
                override fun onError(utteranceId: String?) = done.countDown()
            })
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) !=
                TextToSpeech.SUCCESS
            ) return false
            done.await(SPEAK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return true
        } finally {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }
}
