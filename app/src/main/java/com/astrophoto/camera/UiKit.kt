package com.astrophoto.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Vector icons drawn with plain strokes, so there are no image assets to maintain. */
object Ic {
    const val NONE = 0
    const val FOLDER = 1
    const val GAUGE = 2
    const val TIMER = 3
    const val IMAGES = 4
    const val THERMO = 5
    const val HOURGLASS = 6
    const val FOCUS = 7
    const val ZOOM = 8
    const val LENS = 9

    const val ACCENT = 0xFF2B6CB8.toInt()

    /** Draws [kind] inside a box of half-size [r] centred at ([cx], [cy]). [p] must be a STROKE paint. */
    fun draw(c: Canvas, kind: Int, cx: Float, cy: Float, r: Float, p: Paint) {
        fun x(u: Float) = cx + u * r
        fun y(u: Float) = cy + u * r
        val round = r * 0.18f
        when (kind) {
            FOLDER -> {
                val t = Path()
                t.moveTo(x(-1f), y(-0.55f)); t.lineTo(x(-1f), y(-0.85f))
                t.lineTo(x(-0.3f), y(-0.85f)); t.lineTo(x(0f), y(-0.55f))
                c.drawPath(t, p)
                c.drawRoundRect(RectF(x(-1f), y(-0.55f), x(1f), y(0.85f)), round, round, p)
            }
            GAUGE -> {
                c.drawCircle(x(0f), y(0.15f), r * 0.6f, p)
                c.drawLine(x(0f), y(0.15f), x(0.3f), y(-0.25f), p)
                for (a in intArrayOf(-160, -125, -90, -55, -20)) {
                    val rad = Math.toRadians(a.toDouble())
                    val cs = Math.cos(rad).toFloat()
                    val sn = Math.sin(rad).toFloat()
                    c.drawLine(x(0.8f * cs), y(0.15f + 0.8f * sn), x(1f * cs), y(0.15f + 1f * sn), p)
                }
            }
            TIMER -> {
                c.drawCircle(x(0f), y(0.2f), r * 0.75f, p)
                c.drawLine(x(0f), y(-0.55f), x(0f), y(-0.85f), p)
                c.drawLine(x(-0.3f), y(-0.85f), x(0.3f), y(-0.85f), p)
                c.drawLine(x(-0.2f), y(-0.05f), x(-0.2f), y(0.45f), p)
                c.drawLine(x(0.2f), y(-0.05f), x(0.2f), y(0.45f), p)
            }
            IMAGES -> {
                c.drawRoundRect(RectF(x(-0.5f), y(-0.95f), x(0.95f), y(0.4f)), round, round, p)
                c.drawRoundRect(RectF(x(-0.95f), y(-0.5f), x(0.5f), y(0.95f)), round, round, p)
                val m = Path()
                m.moveTo(x(-0.8f), y(0.7f)); m.lineTo(x(-0.4f), y(0.15f))
                m.lineTo(x(-0.1f), y(0.5f)); m.lineTo(x(0.05f), y(0.35f)); m.lineTo(x(0.35f), y(0.7f))
                c.drawPath(m, p)
            }
            THERMO -> {
                c.drawRoundRect(RectF(x(-0.2f), y(-0.95f), x(0.2f), y(0.35f)), r * 0.2f, r * 0.2f, p)
                c.drawCircle(x(0f), y(0.62f), r * 0.36f, p)
                for (t in floatArrayOf(-0.75f, -0.45f, -0.15f)) c.drawLine(x(-0.8f), y(t), x(-0.5f), y(t), p)
            }
            HOURGLASS -> {
                val h = Path()
                h.moveTo(x(-0.6f), y(-0.9f)); h.lineTo(x(0.6f), y(-0.9f)); h.lineTo(x(0f), y(0f))
                h.lineTo(x(0.6f), y(0.9f)); h.lineTo(x(-0.6f), y(0.9f)); h.lineTo(x(0f), y(0f)); h.close()
                c.drawPath(h, p)
            }
            FOCUS -> {
                for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) {
                    c.drawLine(x(sx * 0.9f), y(sy * 0.9f), x(sx * 0.45f), y(sy * 0.9f), p)
                    c.drawLine(x(sx * 0.9f), y(sy * 0.9f), x(sx * 0.9f), y(sy * 0.45f), p)
                }
                val f = Paint(p)
                f.style = Paint.Style.FILL
                c.drawCircle(cx, cy, r * 0.2f, f)
            }
            ZOOM -> {
                c.drawCircle(x(-0.2f), y(-0.2f), r * 0.68f, p)
                c.drawLine(x(0.3f), y(0.3f), x(0.95f), y(0.95f), p)
                c.drawLine(x(-0.5f), y(-0.2f), x(0.1f), y(-0.2f), p)
                c.drawLine(x(-0.2f), y(-0.5f), x(-0.2f), y(0.1f), p)
            }
            LENS -> {
                c.drawCircle(cx, cy, r * 0.95f, p)
                c.drawCircle(cx, cy, r * 0.5f, p)
                val f = Paint(p)
                f.style = Paint.Style.FILL
                c.drawCircle(cx, cy, r * 0.14f, f)
            }
        }
    }
}

/** Round camera button: icon (or big text) above a small value/label, blue when selected. */
class RoundBtn(ctx: Context) : View(ctx) {
    var icon = Ic.NONE
    var big: String? = null
    var sub = ""
    var selectedOn = false
    var textScale = 1f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = -1
    }
    private val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    init { isClickable = true }

    fun set(s: String) {
        if (s != sub) { sub = s; invalidate() }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private fun fit(text: String, maxW: Float, size: Float): Float {
        tx.textSize = size
        val m = tx.measureText(text)
        return if (m > maxW && m > 0f) size * maxW / m else size
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f
        bg.color = if (selectedOn) Ic.ACCENT else 0xFF3A3B3E.toInt()
        if (isPressed) bg.color = if (selectedOn) 0xFF1F5190.toInt() else 0xFF55575B.toInt()
        c.drawCircle(cx, cy, r, bg)

        val maxW = r * 1.5f
        tx.color = -1
        if (icon != Ic.NONE) {
            line.strokeWidth = r * 0.085f
            Ic.draw(c, icon, cx, cy - r * 0.22f, r * 0.40f, line)
        } else if (big != null) {
            tx.textSize = fit(big!!, maxW, r * 0.52f * textScale)
            c.drawText(big!!, cx, cy - r * 0.02f, tx)
        }
        tx.color = if (selectedOn) 0xFFD6E4F7.toInt() else 0xFFB4B6BA.toInt()
        val size = fit(sub, maxW, r * 0.30f * textScale)
        tx.textSize = size
        c.drawText(sub, cx, cy + r * 0.66f, tx)
    }
}

/** Vertical slider: white track, blue thumb, bottom = 0, top = 1. */
class VSlider(ctx: Context) : View(ctx) {
    var fraction = 0f
    var onChange: (Float) -> Unit = {}
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -1
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f * d
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ic.ACCENT }
    private val thumbR = 13f * d

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val top = thumbR
        val bottom = height - thumbR
        c.drawLine(cx, top, cx, bottom, track)
        val ty = bottom - fraction.coerceIn(0f, 1f) * (bottom - top)
        c.drawCircle(cx, ty, thumbR, thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val top = thumbR
                val bottom = height - thumbR
                val f = (1f - (e.y - top) / (bottom - top)).coerceIn(0f, 1f)
                if (f != fraction) {
                    fraction = f
                    onChange(f)
                    invalidate()
                }
                return true
            }
        }
        return true
    }
}
