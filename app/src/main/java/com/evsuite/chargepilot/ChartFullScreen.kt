package com.evsuite.chargepilot

import android.app.Dialog
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat

/**
 * A double tap moves a graph into a full-screen window and a second one, or Back, returns it.
 *
 * The graph itself moves rather than a copy of it, so whatever it was last given is what the
 * large window shows, with no second place to keep in step. A placeholder with the same layout
 * parameters holds its slot so the screen behind does not reflow.
 */
object ChartFullScreen {
    fun install(chart: View) {
        var open: Dialog? = null
        fun toggle() {
            open?.let { it.dismiss(); return }
            val parent = chart.parent as? ViewGroup ?: return
            val index = parent.indexOfChild(chart)
            val params = chart.layoutParams
            val placeholder = View(chart.context)
            parent.removeViewAt(index)
            parent.addView(placeholder, index, params)
            val frame = FrameLayout(chart.context).apply {
                setBackgroundColor(context.getColor(R.color.ev_background))
                val padding = resources.getDimensionPixelSize(R.dimen.spacing_lg)
                setPadding(padding, padding, padding, padding)
                addView(chart, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            open = Dialog(chart.context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                .apply {
                    setContentView(frame)
                    setOnDismissListener {
                        frame.removeView(chart)
                        parent.removeView(placeholder)
                        parent.addView(chart, index, params)
                        open = null
                    }
                    show()
                }
        }

        val detector = GestureDetector(
            chart.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    toggle()
                    return true
                }
            },
        )
        // Clickable so the view keeps the gesture past its first touch; the listener only
        // watches, and whatever the view itself does with a tap still happens.
        chart.isClickable = true
        chart.setOnTouchListener { _, event -> detector.onTouchEvent(event); false }
        ViewCompat.addAccessibilityAction(
            chart,
            chart.context.getString(R.string.action_chart_full_screen),
        ) { _, _ -> toggle(); true }
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
