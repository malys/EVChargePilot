package com.evsuite.chargepilot

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.saic.SaicTts
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
     * @param interrupt true only for the driver's own test from the settings screen; a coach line
     *   queues behind whatever the car is already announcing.
     */
    fun say(
        context: Context,
        text: String,
        interrupt: Boolean = false,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        if (text.isBlank()) {
            onResult?.invoke(false)
            return
        }
        val app = context.applicationContext
        runCatching {
            worker.execute {
                val spoken = runCatching { speakBlocking(app, text, interrupt) }
                    .onFailure { AppLogger.w(TAG, "speech failed: ${it.message}") }
                    .getOrDefault(false)
                runCatching { onResult?.invoke(spoken) }
            }
        }.onFailure { onResult?.invoke(false) }
    }

    private fun speakBlocking(app: Context, text: String, interrupt: Boolean): Boolean {
        connect(app)
        var waited = 0L
        while (!SaicTts.isAvailable && waited < BIND_WAIT_MS) {
            Thread.sleep(BIND_POLL_MS)
            waited += BIND_POLL_MS
        }
        if (SaicTts.isAvailable && SaicTts.speak(text, interrupt, app.packageName)) {
            AppLogger.d(TAG, "spoken by the vehicle voice service")
            return true
        }
        return speakWithPlatformEngine(app, text)
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
