package app.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View

/** The rotating arc reactor in the middle of the screen. Colour and speed follow the assistant's state. */
class CoreView(context: Context) : View(context) {
    var state: Hub.State = Hub.State.OFF
        set(value) {
            field = value
            invalidate()
        }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val box = RectF()
    private var rot = 0f
    private var last = 0L

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else minOf(0.05f, (now - last) / 1000f)
        last = now

        val spin = when (state) {
            Hub.State.OFF -> 0.2f
            Hub.State.LISTENING -> 0.8f
            Hub.State.AWAKE -> 1.6f
            Hub.State.THINKING -> 3.0f
            Hub.State.SPEAKING -> 1.4f
        }
        rot += dt * spin * 40f

        val color = when (state) {
            Hub.State.OFF -> 0xFF4A6572.toInt()
            Hub.State.THINKING -> 0xFFFFB040.toInt()
            else -> 0xFF4CC9F0.toInt()
        }
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - dp(4f)
        if (r <= 10f) return

        val t = now / 1000f
        val pulse = when (state) {
            Hub.State.SPEAKING -> 0.10f * Math.abs(Math.sin(t * 7.3)).toFloat()
            Hub.State.THINKING -> 0.06f * Math.sin(t * 5.0).toFloat()
            Hub.State.OFF -> 0f
            else -> 0.035f * Math.sin(t * 1.3).toFloat()
        }

        // glow
        glow.color = color
        for (k in 3 downTo 1) {
            glow.alpha = 18 * (4 - k)
            canvas.drawCircle(cx, cy, r * (0.30f + 0.09f * k + pulse), glow)
        }
        glow.alpha = 120
        canvas.drawCircle(cx, cy, r * (0.12f + pulse * 0.5f), glow)

        // tick marks
        ring.color = color
        for (i in 0 until 72) {
            val big = i % 6 == 0
            val a = Math.toRadians((i * 5f + rot * 0.12f).toDouble())
            val inner = r - dp(if (big) 11f else 5f)
            ring.strokeWidth = dp(if (big) 1.5f else 1f)
            ring.alpha = if (big) 190 else 80
            canvas.drawLine(
                cx + (Math.cos(a) * inner).toFloat(), cy + (Math.sin(a) * inner).toFloat(),
                cx + (Math.cos(a) * r).toFloat(), cy + (Math.sin(a) * r).toFloat(), ring
            )
        }

        // thin outer ring
        ring.strokeWidth = dp(1f)
        ring.alpha = 70
        canvas.drawCircle(cx, cy, r * 0.9f, ring)

        // heavy arcs
        ring.strokeWidth = dp(3f)
        ring.alpha = 220
        box.set(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f)
        canvas.drawArc(box, -rot * 0.5f, 85f, false, ring)
        canvas.drawArc(box, -rot * 0.5f + 126f, 50f, false, ring)
        canvas.drawArc(box, -rot * 0.5f + 235f, 32f, false, ring)

        // dashed ring
        ring.strokeWidth = dp(1f)
        ring.alpha = 120
        for (i in 0 until 48) {
            val a = Math.toRadians((i * 7.5f + rot * 0.6f).toDouble())
            val rr = r * 0.66f
            canvas.drawPoint(cx + (Math.cos(a) * rr).toFloat(), cy + (Math.sin(a) * rr).toFloat(), ring)
        }

        // inner arcs
        ring.strokeWidth = dp(2f)
        ring.alpha = 190
        box.set(cx - r * 0.52f, cy - r * 0.52f, cx + r * 0.52f, cy + r * 0.52f)
        canvas.drawArc(box, rot * 0.9f + 20f, 62f, false, ring)
        canvas.drawArc(box, rot * 0.9f + 190f, 62f, false, ring)

        ring.strokeWidth = dp(1f)
        ring.alpha = 110
        canvas.drawCircle(cx, cy, r * 0.36f, ring)

        // state label
        label.color = color
        label.textSize = r * 0.11f
        label.letterSpacing = 0.2f
        val text = when (state) {
            Hub.State.OFF -> "OFFLINE"
            Hub.State.LISTENING -> "LISTENING"
            Hub.State.AWAKE -> "AWAKE"
            Hub.State.THINKING -> "PROCESSING"
            Hub.State.SPEAKING -> "SPEAKING"
        }
        canvas.drawText(text, cx, cy + r * 0.04f, label)

        postInvalidateOnAnimation()
    }
}
