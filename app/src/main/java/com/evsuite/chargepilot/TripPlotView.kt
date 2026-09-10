package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
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
    private val accent = context.getColor(R.color.ev_accent)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ev_outline)
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    /** The grid is scenery, not data: kept faint so a curve never competes with a rule. */
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ev_outline)
        alpha = 60
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    /** Area under the speed curve: an alpha wash of the same accent, no second hue. */
    private val speedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = 16f * density
        speedFillPaint.shader = LinearGradient(
            0f, inset, 0f, h - inset,
            Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
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

        val zeroY = (top + bottom) / 2f
        for (step in 1..3) {
            val y = top + (bottom - top) * step / 4f
            if (y != zeroY) canvas.drawLine(left, y, right, y, gridPaint)
        }
        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, zeroY, right, zeroY, axisPaint)

        val maxSpeed = max(1f, samples.mapNotNull { it.speedKmh }.maxOrNull() ?: 1f)
        val maxPower = max(1f, samples.mapNotNull { it.batteryPowerKw }.maxOfOrNull(::abs) ?: 1f)

        val speedRuns = runs(left, right) { sample ->
            sample.speedKmh?.let { bottom - (it / maxSpeed) * (bottom - top) }
        }
        speedRuns.forEach { run ->
            canvas.drawPath(smoothPath(run).apply { closeTo(run, bottom) }, speedFillPaint)
            canvas.drawPath(smoothPath(run), speedPaint)
            if (run.size == 1) canvas.drawCircle(run[0].first, run[0].second, 4f * density, speedPaint)
        }
        runs(left, right) { sample ->
            sample.batteryPowerKw?.let { zeroY - (it / maxPower) * (bottom - top) / 2f }
        }.forEach { run -> canvas.drawPath(smoothPath(run), powerPaint) }
    }

    private inline fun runs(
        left: Float,
        right: Float,
        y: (TripSample) -> Float?,
    ): List<List<Pair<Float, Float>>> {
        val firstAt = samples.first().atMs
        val span = (samples.last().atMs - firstAt).coerceAtLeast(1L)
        return contiguousRuns(
            samples.map { sample ->
                y(sample)?.let { value ->
                    left + ((sample.atMs - firstAt).toFloat() / span.toFloat()) * (right - left) to value
                }
            }
        )
    }

    internal companion object {
        /** Splits on the nulls so a gap in the record stays a gap on screen. */
        fun contiguousRuns(points: List<Pair<Float, Float>?>): List<List<Pair<Float, Float>>> {
            val out = mutableListOf<List<Pair<Float, Float>>>()
            var run = mutableListOf<Pair<Float, Float>>()
            points.forEach { point ->
                if (point == null) {
                    if (run.isNotEmpty()) out += run
                    run = mutableListOf()
                } else {
                    run += point
                }
            }
            if (run.isNotEmpty()) out += run
            return out
        }

        /** Quadratic through sample midpoints: rounds the corners without inventing overshoot. */
        fun smoothPath(run: List<Pair<Float, Float>>): Path {
            val path = Path()
            if (run.isEmpty()) return path
            path.moveTo(run[0].first, run[0].second)
            for (index in 1 until run.size) {
                val (x, y) = run[index]
                val (previousX, previousY) = run[index - 1]
                path.quadTo(previousX, previousY, (previousX + x) / 2f, (previousY + y) / 2f)
            }
            path.lineTo(run.last().first, run.last().second)
            return path
        }

        fun Path.closeTo(run: List<Pair<Float, Float>>, baseline: Float) {
            lineTo(run.last().first, baseline)
            lineTo(run.first().first, baseline)
            close()
        }
    }
}
