package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * After Tesla's energy arc: a 240° track, the value as a filled sweep from its start, a tick at
 * the reference, and the two ends of the scale written at the ends. Static, like
 * [BarGaugeView]: the figure inside says the value, the arc is the glance. What sits inside the
 * arc — the trace and the figures — is laid out by the host, not drawn here.
 */
class ArcGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val stroke = STROKE_DP * density
    private val trackPaint = arcPaint(R.color.ev_surface_raised)
    private val valuePaint = arcPaint(R.color.ev_accent)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_text_primary)
        strokeWidth = TICK_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_text_secondary)
        textSize = resources.getDimension(R.dimen.text_label)
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()
    private var fraction: Float? = null
    private var reference: Float? = null
    private var scaleMax = 0.0

    private fun arcPaint(@ColorRes color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = ContextCompat.getColor(context, color)
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * @param value the reading, or null for an empty track.
     * @param referenceValue where the tick goes, or null for none.
     * @param max the full-scale value; the start of the scale is zero.
     * @param color the sweep's colour: the caller says which side of the reference it is on.
     */
    fun show(value: Double?, referenceValue: Double?, max: Double, @ColorRes color: Int) {
        scaleMax = max
        fraction = value?.let { (it / max).toFloat().coerceIn(0f, 1f) }
        reference = referenceValue?.let { (it / max).toFloat().coerceIn(0f, 1f) }
        valuePaint.color = ContextCompat.getColor(context, color)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelRoom = labelPaint.textSize * 1.6f
        val size = min(width.toFloat(), (height - labelRoom) * 2f / (1f + SIN_30)) - stroke
        if (size <= 0f) return
        val radius = size / 2f
        val cx = width / 2f
        val cy = stroke / 2f + radius
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(oval, START_DEG, SWEEP_DEG, false, trackPaint)
        fraction?.takeIf { it > 0f }?.let {
            canvas.drawArc(oval, START_DEG, SWEEP_DEG * it, false, valuePaint)
        }
        reference?.let {
            val angle = Math.toRadians((START_DEG + SWEEP_DEG * it).toDouble())
            val inner = radius - stroke
            val outer = radius + stroke
            canvas.drawLine(
                cx + (inner * cos(angle)).toFloat(), cy + (inner * sin(angle)).toFloat(),
                cx + (outer * cos(angle)).toFloat(), cy + (outer * sin(angle)).toFloat(),
                tickPaint,
            )
        }
        if (scaleMax > 0.0) {
            val y = cy + radius * SIN_30 + labelRoom
            val dx = radius * COS_30
            canvas.drawText("0", cx - dx, y, labelPaint)
            canvas.drawText(String.format(Locale.getDefault(), "%.0f", scaleMax), cx + dx, y, labelPaint)
        }
    }

    private companion object {
        /** From lower-left round to lower-right: 0 at 150°, full scale at 30°. */
        const val START_DEG = 150f
        const val SWEEP_DEG = 240f
        const val STROKE_DP = 10f
        const val TICK_DP = 3f
        const val SIN_30 = 0.5f
        const val COS_30 = 0.8660254f
    }
}
