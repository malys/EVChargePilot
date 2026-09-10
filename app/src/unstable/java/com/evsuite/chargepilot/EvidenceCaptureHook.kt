package com.evsuite.chargepilot

import android.app.Activity
import android.content.Context
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.probe.RuntimeInterfaceProbe
import java.io.File

/** Unstable channel: the capture screen exists and is reachable from the dashboard. */
object EvidenceCaptureHook {
    const val IS_SUPPORTED = true

    fun open(activity: Activity) {
        activity.startActivity(
            android.content.Intent(activity, EvidenceCaptureActivity::class.java)
        )
    }

    /**
     * Arms the always-on probes when the application starts.
     *
     * During validation the guidance listener has to be running before the drive begins, not
     * after somebody remembers to open a screen. It costs one binder registration and a 1 Hz
     * tick, and it is read-only.
     */
    fun startProbes(context: Context) {
        // First, because question 2 — "was fine location granted without a prompt" — stops
        // being observable the moment any screen of this process asks for it.
        ValidationProbe.arm(context)
        NavGuidanceRecorder.start(context)
        SignalEvidenceRecorder.start(context)
        // Binding both hubs is asynchronous, and a survey run before they come up reads every
        // interface as absent — the one false negative this exercise cannot afford, because it
        // looks exactly like a head unit that does not publish them. Bound at startup, the
        // survey on the export path is reading services that have been up for the session.
        RuntimeInterfaceProbe.connect(context)
    }

    /**
     * Writes every always-on probe artifact into the folder the diagnostic export bundles.
     *
     * Called on the export path so one "Export to USB" always carries the current state of
     * every probe, including the states nobody would think to save by hand: an adapter that
     * never bound, or a listener that was registered all drive and heard nothing.
     */
    fun saveProbeArtifacts(context: Context) {
        // A late arm is better than none: the app may have started before the vehicle
        // services were up, and an export is a good moment to find out.
        ValidationProbe.arm(context)
        NavGuidanceRecorder.start(context)
        SignalEvidenceRecorder.start(context)
        RuntimeInterfaceProbe.connect(context)
        val firmware = FirmwareInfo.getGeneration().name
        val store = EvidenceCaptureFileStore(
            File(context.filesDir, NavGuidanceRecorder.EVIDENCE_DIRECTORY)
        )
        store.write(
            NavGuidanceRecorder.artifact().toBoundedJson(),
            NavGuidanceProbeArtifact.KIND,
            firmware,
        )
        store.write(SignalEvidenceRecorder.capture())
        store.write(
            TripHistoryArtifact.of(context).toJson(),
            TripHistoryArtifact.KIND,
            firmware,
        )
        // Written even when validation mode is off: "the toggle was never on" is the answer to
        // every empty block in it, and a bundle that simply lacks the file says nothing.
        store.write(
            ValidationProbe.artifact(context).toBoundedJson(),
            ValidationArtifact.KIND,
            firmware,
        )
        // Last, because it is the slowest: the survey sweeps service codes and hub names, and
        // each miss costs a binder round trip. Nothing above it waits on that.
        store.write(
            RuntimeInterfaceArtifact.of().toJson(),
            RuntimeInterfaceArtifact.KIND,
            firmware,
        )
    }
}
