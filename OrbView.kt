package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Анимированная неоновая сфера: цвет и "лицо" зависят от эмоции. */
class OrbView(context: Context) : View(context) {

    private val a = Assistant.get(context)
    private val d = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var t = 0f
    private var last = 0L

    private fun alpha(col: Int, al: Int): Int = (col and 0x00FFFFFF) or (al.coerceIn(0, 255) shl 24)

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min(0.05f, (now - last) / 1e9f)
        last = now
        t += dt

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.27f
        val emo = a.emotion
        val speaking = a.speaking
        val listening = a.listening
        val baseColor = Personas.byId(Prefs.str(context, "persona", "jarvis")).color
        val col = when {
            emo == Emotion.SURPRISED -> Color.parseColor("#FFD740")
            emo == Emotion.SAD -> Color.parseColor("#5C6BC0")
            emo == Emotion.SCARED -> Color.parseColor("#FF5252")
            emo == Emotion.HAPPY -> Color.parseColor("#69F0AE")
            listening -> Color.parseColor("#2979FF")
            else -> baseColor
        }
        val pulse = when {
            speaking -> 1f + 0.10f * abs(sin(t * 9f))
            listening -> 1f + 0.07f * sin(t * 4f)
            else -> 1f + 0.03f * sin(t * 1.5f)
        }

        // свечение
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, r * 2.1f, intArrayOf(alpha(col, 110), alpha(col, 0)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 2.1f, paint)
        paint.shader = null

        // кольца
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * d
        for (i in 0..2) {
            val rr = r * (1.15f + i * 0.22f) * (1f + 0.04f * sin(t * 2f + i))
            paint.color = alpha(col, 150 - i * 40)
            c.drawCircle(cx, cy, rr, paint)
        }

        // ядро
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.3f, r * 1.3f,
            intArrayOf(Color.WHITE, col, alpha(col, 200)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * pulse, paint)
        paint.shader = null

        val dark = Color.parseColor("#0A1B3D")
        if (speaking || listening) {
            // звуковая волна
            path.reset()
            val amp = if (speaking) 0.22f else 0.08f
            for (i in 0..40) {
                val x = cx - r * 0.85f + 1.7f * r * i / 40f
                val y = cy + sin(t * 8f + i * 0.5f) * r * amp * abs(sin(t * 3f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * d
            paint.color = alpha(dark, 220)
            c.drawPath(path, paint)
        } else {
            // глаза и рот по эмоции
            val er = if (emo == Emotion.SURPRISED || emo == Emotion.SCARED) r * 0.11f else r * 0.07f
            paint.style = Paint.Style.FILL
            paint.color = alpha(dark, 230)
            c.drawCircle(cx - r * 0.32f, cy - r * 0.12f, er, paint)
            c.drawCircle(cx + r * 0.32f, cy - r * 0.12f, er, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * d
            if (emo == Emotion.SAD) {
                c.drawLine(cx - r * 0.45f, cy - r * 0.25f, cx - r * 0.2f, cy - r * 0.34f, paint)
                c.drawLine(cx + r * 0.2f, cy - r * 0.34f, cx + r * 0.45f, cy - r * 0.25f, paint)
                rect.set(cx - r * 0.2f, cy + r * 0.3f, cx + r * 0.2f, cy + r * 0.5f)
                c.drawArc(rect, 200f, 140f, false, paint)
            } else if (emo == Emotion.SURPRISED || emo == Emotion.SCARED) {
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, cy + r * 0.3f, r * 0.08f, paint)
            } else {
                rect.set(cx - r * 0.22f, cy + r * 0.05f, cx + r * 0.22f, cy + r * 0.38f)
                c.drawArc(rect, 20f, 140f, false, paint)
            }
        }
        postInvalidateOnAnimation()
    }
}
