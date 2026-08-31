package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.min

/** Static, non-interactive battery-flow scale. Direction is also stated in adjacent text. */
class PowerFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_surface_raised)
    }
    private val flowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_accent)
    }
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ev_outline)
        strokeWidth = resources.displayMetrics.density * CENTRE_WIDTH_DP
    }
    private val bar = RectF()
    private var powerKw: Float? = null

    fun showPower(value: Float?) {
        val finite = value?.takeIf(Float::isFinite)
        if (powerKw == finite) return
        powerKw = finite
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right <= left) return
        val centreX = (left + right) / 2f
        val centreY = height / 2f
        val radius = height / 2f
        bar.set(left, 0f, right, height.toFloat())
        canvas.drawRoundRect(bar, radius, radius, trackPaint)

        powerKw?.takeIf { abs(it) > IDLE_POWER_KW }?.let { power ->
            val fraction = min(abs(power) / DISPLAY_LIMIT_KW, 1f)
            val extent = (right - left) * fraction / 2f
            if (power > 0f) {
                bar.set(centreX, 0f, centreX + extent, height.toFloat())
            } else {
                bar.set(centreX - extent, 0f, centreX, height.toFloat())
            }
            canvas.drawRoundRect(bar, radius, radius, flowPaint)
        }
        canvas.drawLine(centreX, 0f, centreX, height.toFloat(), centrePaint)
    }

    companion object {
        /** Visual clamp only; the adjacent signed number remains the authoritative value. */
        private const val DISPLAY_LIMIT_KW = 200f
        private const val IDLE_POWER_KW = 0.1f
        private const val CENTRE_WIDTH_DP = 2f
    }
}

internal enum class PowerFlowDirection { OUTPUT, REGENERATION, IDLE, UNAVAILABLE }

internal fun powerFlowDirection(powerKw: Float?): PowerFlowDirection = when {
    powerKw == null || !powerKw.isFinite() -> PowerFlowDirection.UNAVAILABLE
    powerKw > 0.1f -> PowerFlowDirection.OUTPUT
    powerKw < -0.1f -> PowerFlowDirection.REGENERATION
    else -> PowerFlowDirection.IDLE
}
