package com.evsuite.chargepilot

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.EcoAdvice
import com.evsuite.hardware.telemetry.EcoDrivingMonitor
import com.evsuite.hardware.telemetry.EcoLever
import com.evsuite.hardware.telemetry.EcoVerdict
import com.evsuite.hardware.telemetry.EnergySnapshot
import com.evsuite.hardware.telemetry.EnergyTripSession
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.UnavailableReason
import com.evsuite.hardware.telemetry.model.SocConsumptionFitResult
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
import kotlin.math.roundToInt

/**
 * Whether this drive is a careful one, said on the energy screen and — if asked — out loud.
 *
 * **The physics is not here.** [EcoDrivingMonitor] holds all of it, in EVHardware, where CP-075
 * replays the same class over a finished track. This file is the app's half: the fit loaded off
 * the main thread, the once-a-gate recomputation, the sentences, and the voice.
 *
 * **The boundary this crosses, and the conditions.** CP-058 wrote that this app states facts and
 * gives no instructions. A coach gives advice, so CP-074 moved that line deliberately and narrowly:
 * every line is a consequence with a number in it and never an imperative, the advice is off until
 * the driver turns it on, the voice is off until they turn it on again, and nothing interrupts —
 * no dialog, no chime, no overlay, and the tile carries its meaning in words rather than colour.
 *
 * **The voice speaks changes, not minutes.** A coach that talks on a timer is a coach the driver
 * switches off in a week. It speaks when the advice becomes different advice, at most once per
 * [SPEAK_COOLDOWN_MS], and never while the car is stopped — a stationary car needs no cruising
 * speed and a passenger reading the screen is not being talked over.
 */
class EcoCoach(context: Context) {

    private val app = context.applicationContext
    private val monitor = EcoDrivingMonitor()

    @Volatile
    private var model: SocConsumptionModel? = null

    private var lastComputedAtMs = 0L
    private var verdict = EcoVerdict(Provenanced.unavailable(UnavailableReason.MODEL_NOT_TRAINED))

    private var speaker: TextToSpeech? = null
    private var speakerReady = false
    private var spokenLine: String? = null
    private var lastSpokeAtMs = 0L

    /** Fits CP-052's model from the current bounded history. Call on a worker thread. */
    fun load(trips: List<StoredTrip>) {
        val fit = SocConsumptionFitter().fit(trips, FirmwareInfo.getGeneration())
        model = (fit as? SocConsumptionFitResult.Ready)?.model
    }

    /**
     * Feeds the frame and returns the verdict to draw.
     *
     * Every frame goes into the speed window — that is the measurement — and the verdict itself
     * is recomputed on [RECOMPUTE_MS], because a band that changes under the driver's eyes at
     * 1 Hz is a band nobody believes.
     */
    fun update(snapshot: EnergySnapshot): EcoVerdict {
        monitor.add(snapshot.timestampMs, snapshot.speedKmh)
        if (snapshot.timestampMs - lastComputedAtMs >= RECOMPUTE_MS) {
            lastComputedAtMs = snapshot.timestampMs
            verdict = monitor.verdict(
                model,
                EnergyTripSession.current(snapshot.timestampMs),
                snapshot.outsideTempCelsius,
            )
        }
        return verdict
    }

    /** The trip ended: the next drive is judged on its own, not on the last one's window. */
    fun reset() {
        monitor.reset()
        lastComputedAtMs = 0L
        verdict = EcoVerdict(Provenanced.unavailable(UnavailableReason.MODEL_NOT_TRAINED))
        spokenLine = null
    }

    /** The band, as a word. An em dash when the model refused, never a cheerful default. */
    fun band(verdict: EcoVerdict): String {
        val band = verdict.band.value ?: return DASH
        return app.getString(ecoBandRes(band))
    }

    /** How far off the expectation, signed, under the band word. */
    fun delta(verdict: EcoVerdict): String {
        val delta = verdict.deltaPercent ?: return DASH
        return app.getString(R.string.eco_delta, delta.roundToInt())
    }

    /**
     * The advice line, or null when the driver never asked for one.
     *
     * A driver who turned the switch on and reads nothing cannot tell an app with no advice from
     * an app that ignored the switch, so the enabled-but-silent case says which it is — the same
     * rule every other unavailable value on this screen follows. It stays a statement about the
     * measurement rather than a promise: the levers need either a fitted model or a few minutes
     * of movement, and neither is a thing the driver can press.
     */
    fun line(verdict: EcoVerdict): String? {
        if (!adviceEnabled(app)) return null
        val advice = verdict.advice ?: return app.getString(R.string.eco_advice_waiting)
        return sentence(advice) ?: app.getString(R.string.eco_advice_waiting)
    }

    /**
     * Says the line when it has become a different line.
     *
     * It speaks advice and nothing else: the waiting line [line] draws is there to be read, and
     * a voice announcing that it has nothing to announce is the coach drivers switch off.
     *
     * @param speedKmh the car's own speed, re-read here rather than trusted from the verdict:
     *   the gate is "the car is moving", and it is checked at the moment of speaking.
     */
    fun speak(verdict: EcoVerdict, speedKmh: Float?, nowMs: Long) {
        val line = sentence(verdict.advice ?: return)
        if (line == null || !voiceEnabled(app) || !adviceEnabled(app)) return
        if (speedKmh == null || speedKmh <= MOVING_KMH) return
        if (line == spokenLine || nowMs - lastSpokeAtMs < SPEAK_COOLDOWN_MS) return
        val engine = speaker ?: TextToSpeech(app) { status ->
            speakerReady = status == TextToSpeech.SUCCESS && speaker?.let(::hasVoice) == true
        }.also {
            // The head unit ducks the radio for an assistant rather than stopping it, and a
            // coach is not a media stream competing with the music the driver chose.
            it.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            speaker = it
        }
        if (!speakerReady) return
        spokenLine = line
        lastSpokeAtMs = nowMs
        engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * Gives the engine the language the line was written in, and says whether it has that voice.
     *
     * The locale comes from the resources the line itself was resolved against, so the two can
     * never disagree. Where the engine has no voice for it the coach stays silent: a French
     * sentence read with English phonemes is not a degraded reading, it is an unparseable one,
     * and it would arrive at 110 km/h with no way to ask for it again.
     */
    private fun hasVoice(engine: TextToSpeech): Boolean {
        val result = engine.setLanguage(app.resources.configuration.locales[0])
        return result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** Releases the engine with the screen. Nothing of this app keeps talking in the background. */
    fun close() {
        speaker?.shutdown()
        speaker = null
        speakerReady = false
    }

    /** Consequence with a number in it. No imperative is written here, in any language. */
    private fun sentence(advice: EcoAdvice): String? {
        return when (advice.lever) {
            EcoLever.CRUISE_SPEED -> app.getString(
                R.string.eco_advice_cruise,
                advice.toSpeedKmh?.roundToInt() ?: return null,
                advice.fromSpeedKmh?.roundToInt() ?: return null,
                advice.savingPercent?.roundToInt() ?: return null,
            )
            EcoLever.STEADINESS -> app.getString(
                R.string.eco_advice_steadiness,
                advice.harshSharePercent?.roundToInt() ?: return null,
            )
        }
    }

    companion object {
        /** A trip is recording or it is not; the tile has nothing to say about a parked car. */
        private const val MOVING_KMH = 1f

        /** The drift line's cadence, for the same reason: a figure that moves is a figure ignored. */
        private const val RECOMPUTE_MS = 30_000L

        /** Two minutes between two spoken lines, however often the advice changes. */
        private const val SPEAK_COOLDOWN_MS = 120_000L

        private const val UTTERANCE_ID = "chargepilot-eco"
        private const val DASH = "—"

        private const val PREFERENCES = "eco_coach"
        private const val PREF_ADVICE = "advice_enabled"
        private const val PREF_VOICE = "voice_enabled"

        fun adviceEnabled(context: Context): Boolean =
            prefs(context).getBoolean(PREF_ADVICE, EcoCoachDefaults.ADVICE_ENABLED)

        fun voiceEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_VOICE, false)

        fun storeAdviceEnabled(context: Context, enabled: Boolean) =
            prefs(context).edit().putBoolean(PREF_ADVICE, enabled).apply()

        fun storeVoiceEnabled(context: Context, enabled: Boolean) =
            prefs(context).edit().putBoolean(PREF_VOICE, enabled).apply()

        private fun prefs(context: Context) = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
}
