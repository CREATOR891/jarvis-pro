package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Нарисованный человечек: ходит по низу экрана, двигает ногами, открывает рот и говорит в облачке. */
class CharacterView(context: Context, private val persona: Persona) : View(context) {

    private val d = context.resources.displayMetrics.density
    private val a = Assistant.get(context)
    private val rnd = Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#102027")
        textSize = 14f * d
    }
    private val skin = Color.parseColor("#FFD9B3")
    private val dark = Color.parseColor("#263238")

    private var x = 0f
    private var dir = 1f
    private var walk = 0f
    private var talkPh = 0f
    private var blink = 0f
    private var blinkIn = 2.5f
    private var last = 0L
    private var layout: StaticLayout? = null
    private var layoutText = ""

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        x = w * 0.3f
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min(0.05f, (now - last) / 1e9f)
        last = now
        val w = width.toFloat()
        val h = height.toFloat()
        val nowMs = System.currentTimeMillis()
        val talking = a.speaking || nowMs - a.lastTextAt < 1800

        blinkIn -= dt
        if (blinkIn <= 0f) {
            blink = 0.14f
            blinkIn = 2f + rnd.nextFloat() * 3f
        }
        if (blink > 0f) blink -= dt

        val moving = !talking
        if (moving) {
            x += dir * 60f * d * dt
            walk += dt * 9f
            if (x > w - 40f * d) { x = w - 40f * d; dir = -1f }
            if (x < 40f * d) { x = 40f * d; dir = 1f }
            if (rnd.nextFloat() < dt * 0.1f) dir = -dir
        } else {
            talkPh += dt * 14f
        }

        val ground = h - 88f * d
        drawBody(c, x, ground, moving, talking)
        drawBubble(c, x, ground, w, nowMs)
        postInvalidateOnAnimation()
    }

    private fun drawBody(c: Canvas, cx: Float, gy: Float, moving: Boolean, talking: Boolean) {
        c.save()
        c.translate(cx, gy)
        c.scale(dir, 1f)

        val legLen = 44f * d
        val torsoH = 50f * d
        val torsoW = 32f * d
        val headR = 21f * d
        val armLen = 38f * d
        val hipY = -legLen
        val shY = hipY - torsoH
        val headCY = shY - headR + 3f * d
        val pants = Color.parseColor("#37474F")
        val pi = PI.toFloat()
        paint.strokeCap = Paint.Cap.ROUND

        // ноги
        for (side in intArrayOf(-1, 1)) {
            val ang = if (moving) sin(walk + (if (side > 0) pi else 0f)) * 0.65f else 0f
            val hx = side * 8f * d
            val fx = hx + sin(ang) * legLen
            val fy = hipY + cos(ang) * legLen
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 9f * d
            paint.color = pants
            c.drawLine(hx, hipY, fx, fy, paint)
            paint.style = Paint.Style.FILL
            paint.color = dark
            c.drawCircle(fx + 3f * d, fy - 2f * d, 6f * d, paint)
        }

        // туловище
        paint.style = Paint.Style.FILL
        paint.color = persona.color
        rect.set(-torsoW / 2f, shY, torsoW / 2f, hipY + 6f * d)
        c.drawRoundRect(rect, 10f * d, 10f * d, paint)

        // руки
        for (side in intArrayOf(-1, 1)) {
            val ang = if (moving) {
                sin(walk + (if (side > 0) 0f else pi)) * 0.7f
            } else {
                if (side > 0) -0.9f + sin(talkPh * 0.6f) * 0.5f else 0.12f
            }
            val sx = side * torsoW / 2f
            val sy = shY + 8f * d
            val hx = sx + sin(ang) * armLen
            val hy = sy + cos(ang) * armLen
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 7f * d
            paint.color = persona.color
            c.drawLine(sx, sy, hx, hy, paint)
            paint.style = Paint.Style.FILL
            paint.color = skin
            c.drawCircle(hx, hy, 4.5f * d, paint)
        }

        // волосы (полумесяц сверху) и лицо
        paint.style = Paint.Style.FILL
        paint.color = persona.hair
        c.drawCircle(0f, headCY - 3f * d, headR + 1f * d, paint)
        paint.color = skin
        c.drawCircle(0f, headCY + 2f * d, headR - 1f * d, paint)

        val emo = a.emotion
        val ey = headCY - 1f * d
        val er = if (emo == Emotion.SURPRISED || emo == Emotion.SCARED) 4f * d else 2.8f * d
        paint.color = dark
        if (blink > 0f && emo == Emotion.IDLE) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * d
            c.drawLine(-10f * d, ey, -4f * d, ey, paint)
            c.drawLine(4f * d, ey, 10f * d, ey, paint)
        } else {
            paint.style = Paint.Style.FILL
            c.drawCircle(-7f * d, ey, er, paint)
            c.drawCircle(7f * d, ey, er, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * d
        if (emo == Emotion.SAD) {
            c.drawLine(-11f * d, ey - 5f * d, -4f * d, ey - 8f * d, paint)
            c.drawLine(4f * d, ey - 8f * d, 11f * d, ey - 5f * d, paint)
        } else if (emo == Emotion.SURPRISED || emo == Emotion.SCARED) {
            c.drawLine(-10f * d, ey - 10f * d, -4f * d, ey - 10f * d, paint)
            c.drawLine(4f * d, ey - 10f * d, 10f * d, ey - 10f * d, paint)
        }

        // рот
        val my = headCY + 9f * d
        if (talking) {
            val open = abs(sin(talkPh)) * 6f * d + 1.5f * d
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#7B1F1F")
            rect.set(-5f * d, my - open / 2f, 5f * d, my + open / 2f)
            c.drawOval(rect, paint)
        } else if (emo == Emotion.SURPRISED || emo == Emotion.SCARED) {
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#7B1F1F")
            c.drawCircle(0f, my, 5f * d, paint)
        } else if (emo == Emotion.SAD) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * d
            paint.color = dark
            rect.set(-6f * d, my, 6f * d, my + 8f * d)
            c.drawArc(rect, 200f, 140f, false, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * d
            paint.color = dark
            rect.set(-7f * d, my - 5f * d, 7f * d, my + 4f * d)
            c.drawArc(rect, 20f, 140f, false, paint)
        }

        drawAccessory(c, headCY, headR, ey)
        c.restore()
    }

    private fun drawAccessory(c: Canvas, headCY: Float, headR: Float, ey: Float) {
        when (persona.id) {
            "jarvis" -> {
                rect.set(-12f * d, ey - 5f * d, 12f * d, ey + 4f * d)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(80, 0, 229, 255)
                c.drawRoundRect(rect, 4f * d, 4f * d, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f * d
                paint.color = Color.parseColor("#00E5FF")
                c.drawRoundRect(rect, 4f * d, 4f * d, paint)
            }
            "nova" -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#FF4081")
                path.reset()
                path.moveTo(10f * d, headCY - headR + 2f * d)
                path.lineTo(21f * d, headCY - headR - 7f * d)
                path.lineTo(21f * d, headCY - headR + 8f * d)
                path.close()
                c.drawPath(path, paint)
                path.reset()
                path.moveTo(10f * d, headCY - headR + 2f * d)
                path.lineTo(0f, headCY - headR - 7f * d)
                path.lineTo(0f, headCY - headR + 8f * d)
                path.close()
                c.drawPath(path, paint)
            }
            "professor" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.6f * d
                paint.color = dark
                c.drawCircle(-7f * d, ey, 6.5f * d, paint)
                c.drawCircle(7f * d, ey, 6.5f * d, paint)
                c.drawLine(-0.5f * d, ey, 0.5f * d, ey, paint)
            }
            "zen" -> {
                paint.style = Paint.Style.FILL
                paint.color = persona.color
                rect.set(-headR * 0.92f, headCY - headR * 0.55f, headR * 0.92f, headCY - headR * 0.32f)
                c.drawRect(rect, paint)
            }
            "kent" -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#6D4C41")
                rect.set(-headR - 1f * d, headCY - headR - 3f * d, headR + 1f * d, headCY - 5f * d)
                c.drawArc(rect, 180f, 180f, true, paint)
                rect.set(-headR - 9f * d, headCY - 11f * d, headR * 0.2f, headCY - 7f * d)
                c.drawRect(rect, paint)
            }
            "aether" -> {
                val base = headCY - headR + 2f * d
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#FFD54F")
                path.reset()
                path.moveTo(-12f * d, base)
                path.lineTo(-12f * d, base - 12f * d)
                path.lineTo(-6f * d, base - 5f * d)
                path.lineTo(0f, base - 14f * d)
                path.lineTo(6f * d, base - 5f * d)
                path.lineTo(12f * d, base - 12f * d)
                path.lineTo(12f * d, base)
                path.close()
                c.drawPath(path, paint)
            }
            else -> {
            }
        }
    }

    private fun drawBubble(c: Canvas, cx: Float, gy: Float, w: Float, nowMs: Long) {
        if (a.lastText.isEmpty() || nowMs - a.lastTextAt > 6000) return
        val text = a.lastText.take(110)
        val maxW = min(220f * d, w - 24f * d).toInt()
        if (text != layoutText) {
            layoutText = text
            layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
        }
        val l = layout ?: return
        val bw = l.width + 20f * d
        val bh = l.height + 16f * d
        val bx = (cx - bw / 2f).coerceIn(8f * d, max(8f * d, w - bw - 8f * d))
        val by = max(8f * d, gy - 148f * d - bh)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(240, 255, 255, 255)
        rect.set(bx, by, bx + bw, by + bh)
        c.drawRoundRect(rect, 14f * d, 14f * d, paint)
        val tx = cx.coerceIn(bx + 16f * d, bx + bw - 16f * d)
        path.reset()
        path.moveTo(tx - 7f * d, by + bh - 1f)
        path.lineTo(tx + 7f * d, by + bh - 1f)
        path.lineTo(tx, by + bh + 10f * d)
        path.close()
        c.drawPath(path, paint)
        c.save()
        c.translate(bx + 10f * d, by + 8f * d)
        l.draw(c)
        c.restore()
    }
}
