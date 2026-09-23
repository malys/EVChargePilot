package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withClip
import com.evsuite.chargepilot.TripPlotView.Companion.contiguousRuns
import com.evsuite.chargepilot.TripPlotView.Companion.smoothPath
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Net consumption over distance against the driver's reference, after Tesla's energy graph.
 *
 * The line takes the warning colour above the reference and the accent below it, and the
 * reference is a labelled dashed rule, so the colour is never the only thing saying which side
 * a stretch is on. Missing stretches break the line. A view wider than [DETAILED_MIN_DP] —
 * the large graph, or any graph opened full screen — also writes its scales; a card's
 * thumbnail, about as tall on this panel, stays a bare line.
 */
class ConsumptionTraceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var values: List<Float?> = emptyList()
    private var referenceKwhPer100Km = 0f
    private var spanKm: Double? = null
    private val density = resources.displayMetrics.density

    private fun line(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(colorRes)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    private val abovePaint = line(R.color.ev_warn)
    private val belowPaint = line(R.color.ev_accent)
    private val referencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ev_outline)
        strokeWidth = density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ev_text_secondary)
        textSize = resources.getDimension(R.dimen.text_label)
    }

    /**
     * @param trace kWh/100 km per equal stretch, oldest first; null where nothing was driven.
     * @param spanKm the distance the trace covers, for the scale; null leaves it unwritten.
     */
    fun setTrace(trace: List<Float?>, referenceKwhPer100Km: Double, spanKm: Double?) {
        values = trace
        this.referenceKwhPer100Km = referenceKwhPer100Km.toFloat()
        this.spanKm = spanKm
        invalidate()
    }

    private val detailed: Boolean get() = width >= DETAILED_MIN_DP * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val known = values.filterNotNull()
        if (known.isEmpty()) return
        val inset = 8f * density
        val gutter = if (detailed) GUTTER_DP * density else 0f
        val left = inset + gutter
        val right = width - inset - gutter
        val top = inset
        val bottom = height - inset - if (detailed) labelPaint.textSize * 1.6f else 0f
        if (right <= left || bottom <= top) return

        val low = min(0f, min(known.min(), referenceKwhPer100Km))
        val high = max(known.max(), referenceKwhPer100Km) * 1.1f
        val span = (high - low).coerceAtLeast(1f)
        fun y(value: Float) = bottom - (value - low) / span * (bottom - top)
        val step = if (values.size > 1) (right - left) / (values.size - 1) else 0f
        val runs = contiguousRuns(
            values.mapIndexed { index, value -> value?.let { left + index * step to y(it) } }
        )

        val referenceY = y(referenceKwhPer100Km)
        canvas.drawLine(left, referenceY, right, referenceY, referencePaint)
        runs.forEach { run ->
            val path = smoothPath(run)
            canvas.withClip(left - inset, 0f, right + inset, referenceY) {
                drawPath(path, abovePaint)
            }
            canvas.withClip(left - inset, referenceY, right + inset, height.toFloat()) {
                drawPath(path, belowPaint)
            }
            if (run.size == 1) {
                val (x, value) = run[0]
                canvas.drawCircle(x, value, 3f * density, if (value < referenceY) abovePaint else belowPaint)
            }
        }
        if (detailed) drawScales(canvas, left, top, right, bottom, low, span, referenceY)
    }

    private fun drawScales(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        low: Float,
        span: Float,
        referenceY: Float,
    ) {
        val baseline = labelPaint.textSize / 3f
        labelPaint.textAlign = Paint.Align.RIGHT
        for (step in 0..4) {
            val y = bottom - (bottom - top) * step / 4f
            canvas.drawText(format("%.0f", low + span * step / 4f), left - 6f * density, y + baseline, labelPaint)
        }
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            format("%.1f", referenceKwhPer100Km),
            right + 6f * density,
            referenceY + baseline,
            labelPaint,
        )
        val km = spanKm ?: return
        labelPaint.textAlign = Paint.Align.CENTER
        for (step in 0..4) {
            // Kilometres ago, so the newest end of the line reads 0 whatever the window.
            val x = left + (right - left) * step / 4f
            canvas.drawText(
                format("%.0f", km * (4 - step) / 4.0),
                x,
                bottom + labelPaint.textSize * 1.4f,
                labelPaint,
            )
        }
    }

    private fun format(pattern: String, value: Number) =
        String.format(Locale.getDefault(), pattern, value)

    private companion object {
        /** Wide enough that scales cost less than they explain. */
        const val DETAILED_MIN_DP = 600f

        /** Wide enough for "100" and "14.2" at the label size. */
        const val GUTTER_DP = 48f
    }
}
