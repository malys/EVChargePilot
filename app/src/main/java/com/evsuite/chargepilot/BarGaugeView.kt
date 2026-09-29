package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

/**
 * CP-084/CP-085. A rounded track with filled spans and an optional mark, all in fractions of the
 * track. Static and non-interactive: the figure beside it stays the value, the bar is the glance.
 */
class BarGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** One filled span, [from] to [to] in 0..1 of the track. */
    data class Span(val from: Float, val to: Float, @ColorRes val color: Int)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_surface_raised)
    }
    private val spanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_text_primary)
        strokeWidth = resources.displayMetrics.density * MARK_WIDTH_DP
    }
    private val bar = RectF()
    private var spans: List<Span> = emptyList()
    private var mark: Float? = null

    fun show(spans: List<Span>, mark: Float? = null) {
        if (this.spans == spans && this.mark == mark) return
        this.spans = spans
        this.mark = mark
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right <= left) return
        val radius = height / 2f
        val span = right - left
        bar.set(left, 0f, right, height.toFloat())
        canvas.drawRoundRect(bar, radius, radius, trackPaint)
        spans.forEach {
            val from = it.from.coerceIn(0f, 1f)
            val to = it.to.coerceIn(0f, 1f)
            if (to <= from) return@forEach
            spanPaint.color = ContextCompat.getColor(context, it.color)
            bar.set(left + span * from, 0f, left + span * to, height.toFloat())
            canvas.drawRoundRect(bar, radius, radius, spanPaint)
        }
        mark?.let {
            val x = left + span * it.coerceIn(0f, 1f)
            canvas.drawLine(x, 0f, x, height.toFloat(), markPaint)
        }
    }

    private companion object {
        const val MARK_WIDTH_DP = 3f
    }
}
