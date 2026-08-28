package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.evsuite.hardware.telemetry.TripSample
import kotlin.math.abs
import kotlin.math.max

/** Lightweight nullable speed/power trace; missing runs break the line instead of becoming zero. */
class TripPlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var samples: List<TripSample> = emptyList()
    private val density = resources.displayMetrics.density
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ev_outline)
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ev_accent)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val powerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ev_text_secondary)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    fun setSamples(value: List<TripSample>) {
        samples = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.isEmpty()) return
        val inset = 16f * density
        val left = inset
        val top = inset
        val right = width - inset
        val bottom = height - inset
        if (right <= left || bottom <= top) return

        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        val zeroY = (top + bottom) / 2f
        canvas.drawLine(left, zeroY, right, zeroY, axisPaint)

        val firstAt = samples.first().atMs
        val span = (samples.last().atMs - firstAt).coerceAtLeast(1L)
        val maxSpeed = max(1f, samples.mapNotNull { it.speedKmh }.maxOrNull() ?: 1f)
        val maxPower = max(1f, samples.mapNotNull { it.batteryPowerKw }.maxOfOrNull(::abs) ?: 1f)

        drawSeries(canvas, speedPaint) { sample ->
            sample.speedKmh?.let { bottom - (it / maxSpeed) * (bottom - top) }
        }
        drawSeries(canvas, powerPaint) { sample ->
            sample.batteryPowerKw?.let { zeroY - (it / maxPower) * (bottom - top) / 2f }
        }

        fun x(sample: TripSample): Float =
            left + ((sample.atMs - firstAt).toFloat() / span.toFloat()) * (right - left)
        if (samples.size == 1) {
            samples.single().speedKmh?.let { speed ->
                canvas.drawCircle(x(samples.single()), bottom - speed / maxSpeed * (bottom - top), 4f * density, speedPaint)
            }
        }
    }

    private inline fun drawSeries(
        canvas: Canvas,
        paint: Paint,
        y: (TripSample) -> Float?,
    ) {
        if (samples.isEmpty()) return
        val inset = 16f * density
        val left = inset
        val right = width - inset
        val firstAt = samples.first().atMs
        val span = (samples.last().atMs - firstAt).coerceAtLeast(1L)
        val path = Path()
        var drawing = false
        samples.forEach { sample ->
            val valueY = y(sample)
            if (valueY == null) {
                drawing = false
            } else {
                val valueX = left +
                    ((sample.atMs - firstAt).toFloat() / span.toFloat()) * (right - left)
                if (drawing) path.lineTo(valueX, valueY) else path.moveTo(valueX, valueY)
                drawing = true
            }
        }
        canvas.drawPath(path, paint)
    }
}
