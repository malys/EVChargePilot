package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The planned trip drawn end to end: the road as a bar from 0 to its length, each leg filled,
 * each charging stop as a marker with its uncertainty band bracketed under it, the charge at departure
 * above the start and on arrival above the end. A leg the plan refused stays an empty track.
 * Static: every figure on it is also in the plan's own sentence beside it.
 */
class TripStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** One driven leg, in kilometres from the origin. */
    data class Leg(val startKm: Double, val endKm: Double, val stopBandKm: Double?)

    private val density = resources.displayMetrics.density
    private val barHeight = BAR_DP * density
    private val trackPaint = fill(R.color.ev_surface_raised)
    private val legPaint = fill(R.color.ev_accent)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_warn)
        strokeWidth = BRACKET_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
    }
    private val markerFill = fill(R.color.ev_surface)
    private val markerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_warn)
        style = Paint.Style.STROKE
        strokeWidth = MARKER_STROKE_DP * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_text_secondary)
        textSize = resources.getDimension(R.dimen.text_label)
    }
    private val figurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_text_primary)
        textSize = resources.getDimension(R.dimen.text_body)
    }
    private val rect = RectF()

    private var totalKm = 0.0
    private var legs: List<Leg> = emptyList()
    private var startPercent: Double? = null
    private var endPercent: Double? = null

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = ContextCompat.getColor(context, color)
    }

    fun show(totalKm: Double, legs: List<Leg>, startPercent: Double?, endPercent: Double?) {
        this.totalKm = totalKm
        this.legs = legs
        this.startPercent = startPercent
        this.endPercent = endPercent
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (figurePaint.textSize * 1.6f + barHeight + MARKER_DP * density +
            labelPaint.textSize * 1.8f + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(wanted, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totalKm <= 0.0) return
        val radius = MARKER_DP * density / 2f
        val left = paddingLeft + radius
        val right = width - paddingRight - radius
        if (right <= left) return
        fun x(km: Double) = left + ((km / totalKm).coerceIn(0.0, 1.0) * (right - left)).toFloat()

        // The charge at the two ends, the one figure each end of a trip is about.
        val figureY = paddingTop + figurePaint.textSize
        figurePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(percent(startPercent), left - radius, figureY, figurePaint)
        figurePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(percent(endPercent), right + radius, figureY, figurePaint)

        val top = figureY + figurePaint.textSize * 0.6f + radius - barHeight / 2f
        val bottom = top + barHeight
        val centreY = (top + bottom) / 2f
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, trackPaint)
        legs.forEach { leg ->
            rect.set(x(leg.startKm), top, x(leg.endKm), bottom)
            if (rect.width() > 0f) canvas.drawRoundRect(rect, barHeight / 2f, barHeight / 2f, legPaint)
        }

        val labelY = bottom + radius + labelPaint.textSize * 1.3f
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("0", left - radius, labelY, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(km(totalKm), right + radius, labelY, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        legs.forEach { leg ->
            val band = leg.stopBandKm ?: return@forEach
            // The band as a bracket under the bar, so it never reads as road already driven.
            val bandY = bottom + radius * 0.6f
            canvas.drawLine(x(leg.endKm - band), bandY, x(leg.endKm + band), bandY, bandPaint)
            canvas.drawLine(x(leg.endKm - band), bandY - BRACKET_DP * density, x(leg.endKm - band), bandY, bandPaint)
            canvas.drawLine(x(leg.endKm + band), bandY - BRACKET_DP * density, x(leg.endKm + band), bandY, bandPaint)
            val cx = x(leg.endKm)
            canvas.drawCircle(cx, centreY, radius, markerFill)
            canvas.drawCircle(cx, centreY, radius, markerStroke)
            val text = km(leg.endKm)
            val half = labelPaint.measureText(text) / 2f
            canvas.drawText(text, min(max(cx, left + half * 2.5f), right - half * 2.5f), labelY, labelPaint)
        }
    }

    private fun percent(value: Double?) =
        value?.let { String.format(Locale.getDefault(), "%.0f %%", it) } ?: "—"

    private fun km(value: Double) = String.format(Locale.getDefault(), "%.0f km", value)

    private companion object {
        const val BAR_DP = 14f
        const val MARKER_DP = 22f
        const val MARKER_STROKE_DP = 4f
        const val BRACKET_DP = 5f
        const val BRACKET_STROKE_DP = 2.5f
    }
}
