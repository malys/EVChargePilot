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
 * Charge power against charge level, 0–100 % across. The version's theoretical curves are
 * dashed lines, DC in the accent and AC flat at the on-board charger; what this car drew is
 * columns per band, AC in green and DC in the accent. A band nobody watched has no column.
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
        val peak = listOfNotNull(
            data.dcTheoryKw.maxOrNull(),
            data.acTheoryKw,
            data.acMeasuredKw.filterNotNull().maxOrNull(),
            data.dcMeasuredKw.filterNotNull().maxOrNull(),
        ).maxOrNull() ?: return
        val scale = max(STEP_KW, ceil(peak / STEP_KW) * STEP_KW)
        fun x(percent: Double) = left + (right - left) * (percent / 100.0).toFloat()
        fun y(kw: Double) = bottom - (bottom - top) * (kw / scale).toFloat()

        // Measured columns first, so the theoretical lines read over them.
        val slot = (right - left) / ChargeCurve.BANDS
        val half = slot * COLUMN_SHARE / 2f
        for (band in 0 until ChargeCurve.BANDS) {
            val centre = left + slot * (band + 0.5f)
            val ac = data.acMeasuredKw.getOrNull(band)
            val dc = data.dcMeasuredKw.getOrNull(band)
            // Both in one band: side by side, AC left.
            val split = ac != null && dc != null
            ac?.let { column(canvas, centre - half, if (split) centre else centre + half, y(it), bottom, ok) }
            dc?.let { column(canvas, if (split) centre else centre - half, centre + half, y(it), bottom, accent) }
        }

        theoryPaint.color = accent
        path.reset()
        data.dcTheoryKw.forEachIndexed { index, kw ->
            val px = x(index * ChargeCurve.BAND_PERCENT.toDouble())
            if (index == 0) path.moveTo(px, y(kw)) else path.lineTo(px, y(kw))
        }
        canvas.drawPath(path, theoryPaint)
        theoryPaint.color = ok
        canvas.drawLine(left, y(data.acTheoryKw), right, y(data.acTheoryKw), theoryPaint)

        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)
        val baseline = bottom + labelPaint.textSize + 2f * density
        // The end labels align inward, so neither runs past the axis.
        listOf(0 to Paint.Align.LEFT, 50 to Paint.Align.CENTER, 100 to Paint.Align.RIGHT).forEach { (at, align) ->
            labelPaint.textAlign = align
            canvas.drawText("$at %", x(at.toDouble()), baseline, labelPaint)
        }
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            String.format(Locale.getDefault(), "%.0f kW", scale),
            left - 4f * density,
            top + labelPaint.textSize / 2f,
            labelPaint,
        )
        canvas.drawText("0", left - 4f * density, bottom, labelPaint)
    }

    private fun column(canvas: Canvas, from: Float, to: Float, top: Float, bottom: Float, color: Int) {
        columnPaint.color = color
        columnPaint.alpha = COLUMN_ALPHA
        canvas.drawRect(from, top, to, bottom, columnPaint)
    }

    private companion object {
        const val STEP_KW = 25.0
        const val COLUMN_SHARE = 0.8f
        const val COLUMN_ALPHA = 0xB0
        const val LABEL_SP = 12f
    }
}
