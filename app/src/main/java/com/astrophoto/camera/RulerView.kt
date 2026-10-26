package com.astrophoto.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.roundToInt

/** Horizontal draggable ruler (like DeepSkyCamera's). Drag to change, release to snap, fling supported. */
class RulerView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    var steps = 2
        private set
    var tickPx = 18f * d
    var isMajor: (Int) -> Boolean = { it % 5 == 0 }
    var label: (Int) -> String = { it.toString() }
    var onChange: (Int) -> Unit = {}

    private var pos = 0f
    private var lastIdx = 0
    private var anim: ValueAnimator? = null
    private var vt: VelocityTracker? = null
    private var lastX = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color_WHITE; strokeWidth = 2f * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color_WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12f * d
    }
    private val tri = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color_WHITE }
    private val path = Path()

    val index: Int get() = pos.roundToInt().coerceIn(0, steps - 1)

    fun setup(steps: Int, index: Int) {
        anim?.cancel()
        this.steps = steps.coerceAtLeast(2)
        pos = index.coerceIn(0, this.steps - 1).toFloat()
        lastIdx = pos.roundToInt()
        invalidate()
    }

    /** Move the ruler without calling onChange. */
    fun setIndexSilently(i: Int) {
        anim?.cancel()
        pos = i.coerceIn(0, steps - 1).toFloat()
        lastIdx = pos.roundToInt()
        invalidate()
    }

    fun nudge(delta: Int) = animateTo((index + delta).toFloat(), 150L)

    private fun fire() {
        val i = index
        if (i != lastIdx) {
            lastIdx = i
            onChange(i)
        }
    }

    private fun animateTo(target: Float, ms: Long) {
        anim?.cancel()
        val to = target.coerceIn(0f, (steps - 1).toFloat())
        val a = ValueAnimator.ofFloat(pos, to)
        a.duration = ms
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            pos = it.animatedValue as Float
            fire()
            invalidate()
        }
        anim = a
        a.start()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                lastX = e.x
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(e)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - lastX
                lastX = e.x
                pos = (pos - dx / tickPx).coerceIn(0f, (steps - 1).toFloat())
                fire()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                var vx = 0f
                vt?.let {
                    it.addMovement(e)
                    it.computeCurrentVelocity(1000)
                    vx = it.xVelocity
                    it.recycle()
                }
                vt = null
                val target = (pos - vx * 0.2f / tickPx).roundToInt().toFloat()
                animateTo(target, 350L)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val first = Math.floor((pos - cx / tickPx).toDouble()).toInt().coerceAtLeast(0)
        val last = Math.ceil((pos + cx / tickPx).toDouble()).toInt().coerceAtMost(steps - 1)
        val top = h * 0.28f
        for (i in first..last) {
            val x = cx + (i - pos) * tickPx
            val major = isMajor(i)
            val len = if (major) h * 0.34f else h * 0.20f
            line.alpha = if (major) 255 else 140
            c.drawLine(x, top, x, top + len, line)
            if (major) {
                c.drawText(label(i), x, top + len + txt.textSize * 1.25f, txt)
            }
        }
        path.reset()
        path.moveTo(cx - 9f * d, 0f)
        path.lineTo(cx + 9f * d, 0f)
        path.lineTo(cx, h * 0.22f)
        path.close()
        c.drawPath(path, tri)
    }

    companion object {
        private const val Color_WHITE = -1 // 0xFFFFFFFF
    }
}
