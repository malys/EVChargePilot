package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Columns on a baseline, oldest on the left, each as tall as its fraction of the tallest. Static
 * and non-interactive like [BarGaugeView]: the figures beside it stay the values, the columns are
 * the glance. A null column is a gap, never a zero.
 */
class ColumnChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class Column(
        val fraction: Float?,
        @ColorRes val color: Int,
        val alpha: Int = BarGaugeView.OPAQUE,
    )

    private val density = resources.displayMetrics.density
    private val columnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_outline)
        strokeWidth = density
    }
    private val rect = RectF()
    private val path = Path()
    private var columns: List<Column> = emptyList()

    fun show(columns: List<Column>) {
        if (this.columns == columns) return
        this.columns = columns
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top || columns.isEmpty()) return
        val slot = (right - left) / columns.size
        val barWidth = min(slot * BAR_SHARE, MAX_BAR_DP * density)
        val radius = min(barWidth / 2f, RADIUS_DP * density)
        columns.forEachIndexed { index, column ->
            val fraction = column.fraction?.coerceIn(0f, 1f) ?: return@forEachIndexed
            val centre = left + slot * (index + 0.5f)
            // A column too small to see still shows it exists: at least its own rounded cap.
            val barTop = min(bottom - (bottom - top) * fraction, bottom - radius * 2f)
            columnPaint.color = ContextCompat.getColor(context, column.color)
            columnPaint.alpha = column.alpha
            rect.set(centre - barWidth / 2f, barTop, centre + barWidth / 2f, bottom)
            // Rounded at the top only: the column stands on the baseline.
            path.reset()
            path.addRoundRect(
                rect,
                floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
                Path.Direction.CW,
            )
            canvas.drawPath(path, columnPaint)
        }
        canvas.drawLine(left, bottom, right, bottom, basePaint)
    }

    private companion object {
        const val BAR_SHARE = 0.6f
        const val MAX_BAR_DP = 40f
        const val RADIUS_DP = 6f
    }
}
