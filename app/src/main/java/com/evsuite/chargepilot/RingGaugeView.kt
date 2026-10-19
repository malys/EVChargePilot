package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * CP-094. A round gauge: a track, an arc filled to a fraction, and the figure in its centre.
 * Static and non-interactive like [BarGaugeView]; the words beside it carry the meaning, the
 * arc is the glance.
 */
class RingGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        color = ContextCompat.getColor(context, R.color.ev_surface_raised)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_text_primary)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val ring = RectF()
    private var fraction: Float? = null
    private var label = ""
    @ColorRes private var color = R.color.ev_accent

    /** [fraction] null draws the bare track: nothing known, nothing claimed. */
    fun show(fraction: Float?, label: String, @ColorRes color: Int = R.color.ev_accent) {
        val clamped = fraction?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
        if (this.fraction == clamped && this.label == label && this.color == color) return
        this.fraction = clamped
        this.label = label
        this.color = color
        contentDescription = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = trackPaint.strokeWidth / 2f
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom)
        if (size <= 0) return
        val left = paddingLeft + (width - paddingLeft - paddingRight - size) / 2f
        val top = paddingTop + (height - paddingTop - paddingBottom - size) / 2f
        ring.set(left + inset, top + inset, left + size - inset, top + size - inset)
        canvas.drawOval(ring, trackPaint)
        fraction?.takeIf { it > 0f }?.let {
            arcPaint.color = ContextCompat.getColor(context, color)
            canvas.drawArc(ring, START_DEGREES, it * FULL_DEGREES, false, arcPaint)
        }
        textPaint.textSize = size * TEXT_SHARE
        val baseline = ring.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, ring.centerX(), baseline, textPaint)
    }

    private companion object {
        const val STROKE_DP = 10f
        const val START_DEGREES = -90f
        const val FULL_DEGREES = 360f
        const val TEXT_SHARE = 0.24f
    }
}
