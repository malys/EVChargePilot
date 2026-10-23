package com.evsuite.chargepilot

import android.app.Activity
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.evsuite.hardware.telemetry.EnergyTripHistoryStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Real Android measurement with populated fixtures; the host script requires an emulator. */
class WindowBoundsTest : Instrumentation() {
    private lateinit var options: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val report = mutableListOf<String>()
        val output = File(targetContext.filesDir, "window-bounds").apply { mkdirs() }
        output.listFiles()?.forEach { it.delete() }
        var failures = 0
        val originalLanguages = if (Build.VERSION.SDK_INT >= 33)
            targetContext.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        else AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val originalNight = AppCompatDelegate.getDefaultNightMode()
        try {
            onMain {
                val box = android.widget.FrameLayout(targetContext).apply {
                    layoutParams = ViewGroup.LayoutParams(10, 10)
                }
                box.addView(View(targetContext), ViewGroup.LayoutParams(20, 20))
                box.measure(View.MeasureSpec.makeMeasureSpec(10, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(10, View.MeasureSpec.EXACTLY))
                box.layout(0, 0, 10, 10)
                check(clipped(box).isNotEmpty()) { "Detector missed a deliberately cropped child" }
                val scroll = ScrollView(targetContext).apply { layoutParams = ViewGroup.LayoutParams(10, 10) }
                scroll.addView(View(targetContext), ViewGroup.LayoutParams(10, 20))
                scroll.measure(View.MeasureSpec.makeMeasureSpec(10, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(10, View.MeasureSpec.EXACTLY))
                scroll.layout(0, 0, 10, 10)
                check(clipped(scroll).isEmpty()) { "Reachable scroll content is not clipping" }
                val text = TextView(targetContext).apply {
                    layoutParams = ViewGroup.LayoutParams(300, 20)
                    this.text = "First line\nSecond line"
                    setLineSpacing(0f, 1.25f)
                }
                text.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(20, View.MeasureSpec.EXACTLY))
                text.layout(0, 0, 300, 20)
                check(clipped(text).isNotEmpty()) { "Detector missed cropped text" }
                text.text = "One line"
                text.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                text.layout(0, 0, 300, text.measuredHeight)
                check(clipped(text).isEmpty()) { "Trailing line spacing is not cropped text" }
            }
            val trips = EnergyTripHistoryStore(File(targetContext.filesDir, "trips.json")).read()
            check(trips.isNotEmpty()) { "Seed reference trips before testing; empty screens hide regressions" }
            val startedAt = trips.first().summary.startedAtMs
            val activities = targetContext.packageManager.getPackageInfo(
                targetContext.packageName, PackageManager.GET_ACTIVITIES,
            ).activities.orEmpty().map { it.name }.sorted()
            check(activities.size >= 15) { "Activity coverage unexpectedly shrank: $activities" }
            onMain {
                AppCompatDelegate.setDefaultNightMode(
                    if (options.getString("night") == "yes") AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO,
                )
            }
            val languages = options.getString("languages")?.split(',') ?: LANGUAGE_TAGS.filter { it.isNotBlank() }
            for (language in languages) {
                onMain {
                    if (Build.VERSION.SDK_INT >= 33) {
                        targetContext.getSystemService(LocaleManager::class.java).applicationLocales =
                            LocaleList.forLanguageTags(language)
                    } else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
                }
                for (name in activities) {
                    val label = "$language-${name.substringAfterLast('.')}"
                    File(output, "current-window.txt").writeText(label)
                    var activity: Activity? = null
                    try {
                        activity = startActivitySync(Intent().setClassName(targetContext, name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("started_at", startedAt)
                            .putExtra("split.preview", true))
                        onMain {
                            activity.findViewById<TextView>(R.id.splitRange)?.text = "355 km"
                        }
                        waitForIdleSync()
                        // Wait for data and layout, not a fixed delay that can miss slow renders.
                        awaitPopulated(activity)
                        waitForIdleSync()
                        onMain { inspect(activity, language) }
                        report += "PASS $label"
                        if (name.endsWith(".TripHistoryActivity")) {
                            onMain { activity.findViewById<View>(R.id.viewLedger).performClick() }
                            waitForIdleSync()
                            awaitPopulated(activity)
                            onMain {
                                check(activity.findViewById<android.widget.ListView>(R.id.historyList).count > 0)
                                inspect(activity, language)
                            }
                            report += "PASS $language-trip_ledger"
                        }
                        if (name.endsWith(".TripPlotActivity")) {
                            onMain {
                                activity.findViewById<TripPlotView>(R.id.tripPlot).onScrub
                                    ?.invoke(trips.first().samples.orEmpty().last())
                            }
                            waitForIdleSync()
                            awaitPopulated(activity)
                            onMain { inspect(activity, language) }
                            report += "PASS $language-trip_cursor"
                        }
                        if (name.endsWith(".MainActivity")) {
                            var dialog: androidx.appcompat.app.AlertDialog? = null
                            onMain {
                                dialog = MaterialAlertDialogBuilder(activity)
                                    .setView(activity.layoutInflater.inflate(R.layout.dialog_about, null)).create()
                                dialog!!.show()
                            }
                            waitForIdleSync()
                            SystemClock.sleep(200)
                            onMain {
                                try {
                                    val decor = checkNotNull(dialog!!.window).decorView
                                    val errors = clipped(decor)
                                    check(errors.isEmpty()) { "About: ${errors.joinToString("\n")}" }
                                } catch (error: Throwable) {
                                    capture(File(output, "$language-dialog_about.png"))
                                    throw error
                                } finally { dialog!!.dismiss() }
                            }
                            report += "PASS $language-dialog_about"
                        }
                    } catch (error: Throwable) {
                        failures++
                        report += "FAIL $label: ${error.message}"
                        capture(File(output, "$label.png"))
                    } finally {
                        activity?.let { onMain { it.finish() } }
                        waitForIdleSync()
                    }
                }
            }
        } catch (error: Throwable) {
            failures++
            report += "FAIL setup: ${error.stackTraceToString()}"
        } finally {
            onMain {
                if (Build.VERSION.SDK_INT >= 33) {
                    targetContext.getSystemService(LocaleManager::class.java).applicationLocales =
                        LocaleList.forLanguageTags(originalLanguages)
                } else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(originalLanguages))
                AppCompatDelegate.setDefaultNightMode(originalNight)
            }
        }
        File(output, "report-${options.getString("night")}.txt").writeText(report.joinToString("\n"))
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", report.joinToString("\n") + "\nwindow_bounds_failures=$failures\n")
        })
    }

    /** API 28's UiAutomation.takeScreenshot crops wm overrides; screencap captures the panel. */
    private fun capture(file: File) {
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("screencap -p")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    private fun awaitPopulated(activity: Activity) {
        repeat(100) {
            var ready = false
            onMain {
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                ready = root.width > 0 && root.height > 0 && !root.isLayoutRequested && when (activity) {
                    is BatteryHealthActivity -> activity.findViewById<TextView>(R.id.healthValue).text.contains('%')
                    is ChargeHistoryActivity -> activity.findViewById<ViewGroup>(R.id.chargeRows).childCount > 0
                    is TripHistoryActivity -> activity.findViewById<android.widget.ListView>(R.id.historyList).count > 0 &&
                        activity.findViewById<TextView>(R.id.overviewWindowEnergy).text.isNotBlank()
                    is TripDetailsActivity -> activity.findViewById<TextView>(R.id.energyVerdict).text.isNotBlank()
                    else -> true
                }
            }
            if (ready) return
            SystemClock.sleep(50)
        }
        error("Reference data never appeared in ${activity.javaClass.simpleName}")
    }

    private fun inspect(activity: Activity, language: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val density = root.resources.displayMetrics.density
        check(root.resources.configuration.locales[0].language == language) {
            "Requested language $language was not applied"
        }
        check(kotlin.math.abs(root.resources.configuration.fontScale -
            options.getString("font_scale", "1.0").toFloat()) < 0.01f) { "Requested font scale was not applied" }
        check(root.width == (1778 * density).toInt() && root.height == (640 * density).toInt()) {
            "Wrong app box: ${root.width}x${root.height}px @ $density"
        }
        val errors = clipped(root)
        check(errors.isEmpty()) { errors.joinToString("\n") }
    }

    /** Assertions must fail the test thread, never crash the application's main thread. */
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    /** Scroll content may exceed its viewport; children must still fit their own content box. */
    private fun clipped(root: View): List<String> {
        val errors = mutableListOf<String>()
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE) return
            val id = if (view.id == View.NO_ID) view.javaClass.simpleName +
                if (view is TextView) "[${view.text.take(50)}]" else "" else
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault(view.id.toString())
            if (view is TextView && view !is EditText && view.text.isNotBlank()) {
                val layout = view.layout
                if (layout == null || view.width <= 0 || view.height <= 0) errors += "$id: text has no space"
                else {
                    // Layout.height includes trailing line spacing, not clipped glyphs.
                    val inkBottom = view.baseline + layout.getLineBaseline(layout.lineCount - 1) -
                        layout.getLineBaseline(0) + view.paint.fontMetricsInt.descent
                    if (inkBottom > view.height - view.compoundPaddingBottom + 1) {
                        errors += "$id: text height ${layout.height} exceeds ${view.height}px " +
                            "(baseline=${view.baseline}, descent=${view.paint.fontMetricsInt.descent}, " +
                            "padding=${view.paddingTop}/${view.paddingBottom}, " +
                            "lineSpacing=${view.lineSpacingExtra}/${view.lineSpacingMultiplier})"
                    }
                    for (line in 0 until layout.lineCount) {
                        if (layout.getEllipsisCount(line) == 0 && layout.getLineMax(line) > layout.width + 1) {
                            errors += "$id: line $line exceeds text width"
                        }
                    }
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (child.visibility != View.VISIBLE) continue
                if (view !is HorizontalScrollView && (child.left < 0 || child.right > view.width + 1)) {
                    errors += "$id: child $index outside width (${child.left}..${child.right}/${view.width})"
                }
                if (view !is ScrollView && view !is androidx.core.widget.NestedScrollView &&
                    view !is android.widget.AbsListView &&
                    (child.top < 0 || child.bottom > view.height + 1)) {
                    errors += "$id: child $index outside height (${child.top}..${child.bottom}/${view.height})"
                }
                visit(child)
            }
        }
        visit(root)
        return errors
    }
}
