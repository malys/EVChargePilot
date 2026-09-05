package com.evsuite.chargepilot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicNav
import com.evsuite.hardware.saic.SaicNavGuidance
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Every open on-vehicle question, armed before departure and recorded without being operated.
 *
 * CP-055's whole point is that a car is expensive to test on: eleven tickets each wanted a
 * different answer, and answered one at a time each costs a drive and an evening. So the
 * probes are armed at application start, they record from the decisions themselves rather
 * than from a screen, and they all land in the diagnostic bundle the export already writes.
 *
 * **One toggle.** The only question a driver should have to ask on returning is "did I turn it
 * on", and there is exactly one thing to have turned on. It is off by default and persists, so
 * turning it on before a drive covers the whole drive including the process restarts in it.
 *
 * **Cost when off.** [record] returns before its lambda is called, so a disarmed unstable
 * build does not even build the strings; nothing binds, nothing polls, nothing is written.
 */
object ValidationProbe {
    const val IS_SUPPORTED = true

    private const val PREFERENCES = "chargepilot_validation"
    private const val KEY_ENABLED = "enabled"

    /** Enough lines for every route request of a long drive, and bounded against a loop. */
    private const val LINES_PER_QUESTION = 24

    /** The bind is asynchronous; probing the instant it is asked for only records "unbound". */
    private const val BIND_SETTLE_MS = 3_000L

    /** Guarded by itself: recorded from the main thread, a worker, and the probe thread. */
    private val rings = HashMap<ValidationQuestion, ArrayDeque<String>>()
    private val dropped = HashSet<ValidationQuestion>()

    /**
     * Binder calls never run on the main thread here. A head-unit service that answers slowly
     * is a finding; a head-unit service that hangs the UI thread of a driving car is not.
     */
    private val prober = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chargepilot-validation")
    }

    @Volatile
    private var enabled = false

    @Volatile
    private var armedAtMs: Long? = null

    @Volatile
    private var probedWhileGuiding = false

    /** The application context, kept so a probe can fire from a tick that has none. */
    @Volatile
    private var appContext: Context? = null

    val isEnabled: Boolean get() = enabled

    /**
     * Reads the toggle and records the two things only the start of a process can answer.
     *
     * Question 2 has to be read here and nowhere else: once any screen has asked for location,
     * "was it granted without a prompt" can no longer be observed.
     */
    fun arm(context: Context) {
        val app = context.applicationContext
        appContext = app
        enabled = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        if (!enabled || armedAtMs != null) return
        armedAtMs = System.currentTimeMillis()
        record(ValidationQuestion.LOCATION_GRANT) {
            "at process start, before this process asked for anything: " +
                "fine=${granted(app, Manifest.permission.ACCESS_FINE_LOCATION)}, " +
                "coarse=${granted(app, Manifest.permission.ACCESS_COARSE_LOCATION)}, " +
                "firmware=${FirmwareInfo.getGeneration().name}"
        }
        probeDestinationLater(app, "at process start, before any guidance was heard")
    }

    /** The one toggle. Turning it on arms everything; turning it off keeps what was recorded. */
    fun setEnabled(context: Context, value: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
        enabled = value
        if (value) arm(app)
    }

    /**
     * One line against one question, or nothing at all.
     *
     * [line] is a lambda so that a disarmed build pays nothing for the call sites scattered
     * through the routing code: no formatting, no allocation beyond the lambda itself.
     */
    fun record(question: ValidationQuestion, line: () -> String) {
        if (!enabled) return
        val since = armedAtMs?.let { (System.currentTimeMillis() - it) / 1_000L } ?: 0L
        val text = String.format(Locale.ROOT, "%6ds %s", since, line())
        synchronized(rings) {
            val ring = rings.getOrPut(question) { ArrayDeque() }
            if (ring.size >= LINES_PER_QUESTION) {
                ring.removeFirst()
                dropped.add(question)
            }
            ring.addLast(text)
        }
    }

    /**
     * The car has a destination of its own and is guiding to it — the moment CP-051 asks about.
     *
     * Called from the guidance recorder's tick, at most once per process, and the work leaves
     * that thread immediately.
     */
    fun onGuidanceHeard() {
        if (!enabled || probedWhileGuiding) return
        val app = appContext ?: return
        probedWhileGuiding = true
        probeDestinationLater(app, "guidance heard, so the car's own navigation has a destination")
    }

    /** The state of every question as the file the export bundles. */
    internal fun artifact(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): ValidationArtifact {
        // Synchronous: [EvidenceCaptureHook.saveProbeArtifacts] is already on the export's
        // background thread, and a probe answering after the file is written answers into the
        // next export instead of this one.
        if (enabled) probeDestination(context.applicationContext, "at export")
        val recorded = synchronized(rings) {
            ValidationQuestion.entries.associateWith { rings[it]?.toList().orEmpty() }
        }
        return ValidationArtifact.of(
            savedAtMs = nowMs,
            firmware = FirmwareInfo.getGeneration().name,
            validationModeOn = enabled,
            armedAtMs = armedAtMs,
            lines = recorded,
            incomplete = synchronized(rings) { dropped.toSet() },
        )
    }

    private fun probeDestinationLater(context: Context, occasion: String) {
        SaicNav.connect(context)
        prober.execute {
            Thread.sleep(BIND_SETTLE_MS)
            probeDestination(context, occasion)
        }
    }

    /** Reads both candidate transactions and records what came back, byte for byte. */
    private fun probeDestination(context: Context, occasion: String) {
        SaicNav.connect(context)
        val callbacks = SaicNavGuidance.latest().events
        record(ValidationQuestion.DESTINATION) {
            "$occasion: adapter bound=${SaicNav.isAvailable}, guidance callbacks so far=$callbacks"
        }
        SaicNav.DESTINATION_TRANSACTIONS.forEach { code ->
            record(ValidationQuestion.DESTINATION) { SaicNav.probeTransaction(code) }
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
