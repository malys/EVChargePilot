package com.evsuite.chargepilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.evsuite.hardware.telemetry.TripSample
import java.util.Locale
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

    /**
     * Full-screen mode: scales, axis labels and a draggable cursor.
     *
     * The ledger's plot is a thumbnail — a few hundred pixels tall on the car, where a label
     * would cover the curve it annotates. Rather than a second view drawing the same samples,
     * the same one is asked to be legible at two sizes, and only the large one spends space on
     * telling you what the axes mean.
     */
    var detailed = false
        set(value) {
            field = value
            invalidate()
        }

    /** Called with the sample under the cursor, or null when the cursor is lifted. */
    var onScrub: ((TripSample?) -> Unit)? = null

    private var scrubIndex: Int? = null

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
    /**
     * State of charge, on its own scale.
     *
     * This car publishes no battery power, so the power trace above is an empty line on every
     * record it will ever hold here — the plot was showing one curve and legending two. Charge
     * is published, and over a short drive it moves by a couple of percent, which is invisible
     * against a 0–100 axis. It is drawn against its own observed range for that reason, and the
     * range is written beside it so the amplitude is never read as absolute.
     */
    private val socPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ev_warn)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ev_text_secondary)
        textSize = resources.getDimension(R.dimen.text_label)
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ev_text_primary)
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }

    fun setSamples(value: List<TripSample>) {
        samples = value
        scrubIndex = null
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

    /** Only the full-screen plot has a cursor, so only it takes the touch away from a tap. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!detailed || samples.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val index = indexAt(event.x)
                if (index != scrubIndex) {
                    scrubIndex = index
                    onScrub?.invoke(index?.let(samples::get))
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Nearest sample in time, not in pixels: an idle stretch is still a moment of the trip. */
    private fun indexAt(x: Float): Int? {
        val geometry = geometry() ?: return null
        val firstAt = samples.first().atMs
        val span = (samples.last().atMs - firstAt).coerceAtLeast(1L)
        val fraction = ((x - geometry.left) / (geometry.right - geometry.left)).coerceIn(0f, 1f)
        val targetMs = firstAt + (fraction * span).toLong()
        return samples.indices.minByOrNull { abs(samples[it].atMs - targetMs) }
    }

    private data class Geometry(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun geometry(): Geometry? {
        val inset = 16f * density
        // The gutters exist only where something is written in them.
        val left = inset + if (detailed) LABEL_GUTTER_DP * density else 0f
        val bottom = height - inset - if (detailed) labelPaint.textSize * 1.6f else 0f
        val right = width - inset - if (detailed) LABEL_GUTTER_DP * density else 0f
        return Geometry(left, inset, right, bottom).takeIf { it.right > it.left && it.bottom > it.top }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.isEmpty()) return
        val (left, top, right, bottom) = geometry() ?: return

        val zeroY = (top + bottom) / 2f
        for (step in 1..3) {
            val y = top + (bottom - top) * step / 4f
            if (y != zeroY) canvas.drawLine(left, y, right, y, gridPaint)
        }
        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, zeroY, right, zeroY, axisPaint)

        val maxSpeed = max(1f, samples.mapNotNull { it.speedKmh }.maxOrNull() ?: 1f)
        val maxPower = max(1f, samples.mapNotNull { it.batteryPowerKw }.maxOfOrNull(::abs) ?: 1f)
        val socValues = samples.mapNotNull { it.socPercent }
        val minSoc = socValues.minOrNull()
        val socSpan = max(SOC_MIN_SPAN_PERCENT, (socValues.maxOrNull() ?: 0f) - (minSoc ?: 0f))

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
        if (minSoc != null) {
            runs(left, right) { sample ->
                sample.socPercent?.let { bottom - ((it - minSoc) / socSpan) * (bottom - top) }
            }.forEach { run -> canvas.drawPath(smoothPath(run), socPaint) }
        }

        if (detailed) drawScales(canvas, left, top, right, bottom, maxSpeed, minSoc, socSpan)
    }

    /** Axis labels: speed on the left in its own units, charge on the right in its own. */
    private fun drawScales(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        maxSpeed: Float,
        minSoc: Float?,
        socSpan: Float,
    ) {
        val baseline = labelPaint.textSize / 3f
        labelPaint.textAlign = Paint.Align.RIGHT
        for (step in 0..4) {
            val y = bottom - (bottom - top) * step / 4f
            canvas.drawText(
                format("%.0f", maxSpeed * step / 4f),
                left - 6f * density,
                y + baseline,
                labelPaint,
            )
        }
        if (minSoc != null) {
            labelPaint.textAlign = Paint.Align.LEFT
            for (step in 0..4) {
                val y = bottom - (bottom - top) * step / 4f
                canvas.drawText(
                    format("%.1f", minSoc + socSpan * step / 4f),
                    right + 6f * density,
                    y + baseline,
                    labelPaint,
                )
            }
        }

        val firstAt = samples.first().atMs
        val spanMs = (samples.last().atMs - firstAt).coerceAtLeast(1L)
        labelPaint.textAlign = Paint.Align.CENTER
        for (step in 0..4) {
            val x = left + (right - left) * step / 4f
            canvas.drawText(
                elapsed(spanMs * step / 4L),
                x,
                bottom + labelPaint.textSize * 1.4f,
                labelPaint,
            )
        }

        scrubIndex?.let { index ->
            val x = left + (right - left) *
                ((samples[index].atMs - firstAt).toFloat() / spanMs.toFloat())
            canvas.drawLine(x, top, x, bottom, cursorPaint)
        }
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

    private fun format(pattern: String, value: Float) =
        String.format(Locale.getDefault(), pattern, value)

    internal companion object {
        /** Wide enough for "100" at the label size, on either side of the plot. */
        private const val LABEL_GUTTER_DP = 44f

        /**
         * A flat charge trace is a flat line, not a magnified one.
         *
         * Scaling to the observed range alone would turn the quantisation step of a charge that
         * never really moved into a full-height sawtooth. Half a percent is the floor below
         * which the axis stops stretching.
         */
        private const val SOC_MIN_SPAN_PERCENT = 0.5f

        fun elapsed(milliseconds: Long): String {
            val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
            return String.format(
                Locale.getDefault(),
                "%d:%02d",
                totalSeconds / 60L,
                totalSeconds % 60L,
            )
        }

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
