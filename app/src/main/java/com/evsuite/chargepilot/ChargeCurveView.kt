package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Charge power against charge level, 0–100 % across, DC above and AC below, each panel on its
 * own kW scale. The version's theoretical curves are dashed lines, DC in the accent and AC flat
 * at the on-board charger; what this car drew is columns per band, AC in green and DC in the
 * accent. A band nobody watched has no column.
 */
class ChargeCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class Curves(
        val dcTheoryKw: List<Double>,
        val acTheoryKw: Double,
        val acMeasuredKw: List<Double?>,
        val dcMeasuredKw: List<Double?>,
    )

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.ev_accent)
    private val ok = ContextCompat.getColor(context, R.color.ev_ok)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_outline)
        strokeWidth = density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_text_secondary)
        textSize = LABEL_SP * resources.displayMetrics.scaledDensity
    }
    private val theoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val columnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var curves: Curves? = null

    fun show(curves: Curves) {
        if (this.curves == curves) return
        this.curves = curves
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = curves ?: return
        val labelWidth = labelPaint.measureText("000 kW") + 4f * density
        val left = paddingLeft + labelWidth
        val right = (width - paddingRight).toFloat()
        val top = paddingTop + labelPaint.textSize / 2f
        val bottom = height - paddingBottom - labelPaint.textSize - 4f * density
        if (right <= left || bottom <= top) return
        // DC above AC, each on its own kW scale: on one axis the on-board charger's 7 to 11 kW
        // lie flat under a curve that peaks past 100, and the two read as one comparison.
        val gap = labelPaint.textSize * 1.5f
        val split = top + (bottom - top - gap) * DC_SHARE
        plot(canvas, left, right, top, split, data.dcTheoryKw, null, data.dcMeasuredKw, accent, DC_STEP_KW, "DC")
        plot(canvas, left, right, split + gap, bottom, emptyList(), data.acTheoryKw, data.acMeasuredKw, ok, AC_STEP_KW, "AC")

        fun x(percent: Double) = left + (right - left) * (percent / 100.0).toFloat()
        val baseline = bottom + labelPaint.textSize + 2f * density
        // The end labels align inward, so neither runs past the axis.
        listOf(0 to Paint.Align.LEFT, 50 to Paint.Align.CENTER, 100 to Paint.Align.RIGHT).forEach { (at, align) ->
            labelPaint.textAlign = align
            canvas.drawText("$at %", x(at.toDouble()), baseline, labelPaint)
        }
    }

    /** One panel: measured columns, the theoretical curve (or flat line) over them, its own scale. */
    private fun plot(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        theoryKw: List<Double>,
        flatKw: Double?,
        measuredKw: List<Double?>,
        color: Int,
        step: Double,
        name: String,
    ) {
        val peak = listOfNotNull(theoryKw.maxOrNull(), flatKw, measuredKw.filterNotNull().maxOrNull())
            .maxOrNull() ?: return
        val scale = max(step, ceil(peak / step) * step)
        fun x(percent: Double) = left + (right - left) * (percent / 100.0).toFloat()
        fun y(kw: Double) = bottom - (bottom - top) * (kw / scale).toFloat()

        // Measured columns first, so the theoretical line reads over them.
        val slot = (right - left) / ChargeCurve.BANDS
        val half = slot * COLUMN_SHARE / 2f
        for (band in 0 until ChargeCurve.BANDS) {
            val centre = left + slot * (band + 0.5f)
            measuredKw.getOrNull(band)?.let { column(canvas, centre - half, centre + half, y(it), bottom, color) }
        }

        theoryPaint.color = color
        if (theoryKw.isNotEmpty()) {
            path.reset()
            theoryKw.forEachIndexed { index, kw ->
                val px = x(index * ChargeCurve.BAND_PERCENT.toDouble())
                if (index == 0) path.moveTo(px, y(kw)) else path.lineTo(px, y(kw))
            }
            canvas.drawPath(path, theoryPaint)
        }
        flatKw?.let { canvas.drawLine(left, y(it), right, y(it), theoryPaint) }

        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            String.format(Locale.getDefault(), "%.0f kW", scale),
            left - 4f * density,
            top + labelPaint.textSize / 2f,
            labelPaint,
        )
        canvas.drawText("0", left - 4f * density, bottom, labelPaint)
        // In the scale's gutter, under its top figure: inside the panel a line can run through it.
        canvas.drawText(name, left - 4f * density, top + labelPaint.textSize * 2f, labelPaint)
    }

    private fun column(canvas: Canvas, from: Float, to: Float, top: Float, bottom: Float, color: Int) {
        columnPaint.color = color
        columnPaint.alpha = COLUMN_ALPHA
        canvas.drawRect(from, top, to, bottom, columnPaint)
    }

    private companion object {
        const val DC_STEP_KW = 25.0
        const val AC_STEP_KW = 2.0
        /** The DC panel's share of the height: its curve has the shape worth reading. */
        const val DC_SHARE = 0.6f
        const val COLUMN_SHARE = 0.8f
        const val COLUMN_ALPHA = 0xB0
        const val LABEL_SP = 12f
    }
}
