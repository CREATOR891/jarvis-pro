package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class EffectView(context: Context, private val effect: Effect) : View(context) {

    private class P(
        var x: Float = 0f, var y: Float = 0f,
        var vx: Float = 0f, var vy: Float = 0f,
        var size: Float = 0f, var rot: Float = 0f, var vrot: Float = 0f,
        var color: Int = Color.WHITE, var life: Float = 1f, var phase: Float = 0f,
        var glyph: String = ""
    )

    // гроза = дождь + молнии, ёлка = снег + украшения
    private val base: Effect = when (effect) {
        Effect.STORM -> Effect.RAIN
        Effect.TREE -> Effect.SNOW
        else -> effect
    }

    private val d = context.resources.displayMetrics.density
    private val rnd = Random()
    private val ps = ArrayList<P>()
    private val sparks = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var last = 0L
    private var time = 0f
    private var rocketTimer = 0.3f
    private var flash = 0f
    private var flashTimer = 2f
    private val bolt = FloatArray(16)

    private val confettiColors = intArrayOf(
        Color.parseColor("#FF5252"), Color.parseColor("#FFD740"), Color.parseColor("#69F0AE"),
        Color.parseColor("#40C4FF"), Color.parseColor("#E040FB"), Color.parseColor("#FF6E40")
    )
    private val petalColors = intArrayOf(
        Color.parseColor("#FFB7C5"), Color.parseColor("#FF9DB5"), Color.parseColor("#FFC9D6"), Color.parseColor("#FF8FAB")
    )
    private val hearts = arrayOf("❤️", "💖", "💗", "💕", "💘")
    private val leaves = arrayOf("🍂", "🍁", "🍃")
    private val matrixChars = "01ABCDEFGHJKLMNPRSTUVXZ#%&=+"

    init {
        paint.typeface = Typeface.DEFAULT
    }

    private fun r(a: Float, b: Float): Float = a + rnd.nextFloat() * (b - a)
    private fun frac(v: Float): Float = v - floor(v)

    private fun count(): Int = when (effect) {
        Effect.SNOW -> 90
        Effect.RAIN -> 140
        Effect.STORM -> 170
        Effect.TREE -> 55
        Effect.CONFETTI -> 80
        Effect.STARS -> 50
        Effect.HEARTS -> 22
        Effect.FIRE -> 90
        Effect.FIREFLIES -> 35
        Effect.BUBBLES -> 28
        Effect.LEAVES -> 24
        Effect.SAKURA -> 40
        Effect.METEORS -> 5
        Effect.MATRIX -> max(8, (width / (16f * d)).toInt())
        Effect.FIREWORKS -> 0
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ps.clear()
        repeat(count()) {
            val p = P()
            spawn(p, true)
            ps.add(p)
        }
    }

    private fun spawn(p: P, initial: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (base) {
            Effect.SNOW -> {
                p.x = r(0f, w); p.y = if (initial) r(0f, h) else -10f * d
                p.size = r(1.5f, 5f) * d; p.vy = r(40f, 110f) * d
                p.vx = r(-8f, 8f) * d; p.phase = r(0f, 6.28f)
            }
            Effect.RAIN -> {
                p.x = r(0f, w + 100f * d); p.y = if (initial) r(0f, h) else -30f * d
                p.size = r(10f, 22f) * d; p.vy = r(700f, 1100f) * d; p.vx = -90f * d
            }
            Effect.CONFETTI -> {
                p.x = r(0f, w); p.y = if (initial) r(0f, h) else -10f * d
                p.size = r(5f, 10f) * d; p.vy = r(110f, 260f) * d; p.vx = r(-30f, 30f) * d
                p.rot = r(0f, 360f); p.vrot = r(-300f, 300f); p.phase = r(0f, 6.28f)
                p.color = confettiColors[rnd.nextInt(confettiColors.size)]
            }
            Effect.STARS -> {
                p.x = r(0f, w); p.y = r(0f, h); p.size = r(1.5f, 4.5f) * d
                p.phase = r(0f, 6.28f); p.vrot = r(1f, 3f)
                p.color = if (rnd.nextInt(3) == 0) Color.parseColor("#FFE082") else Color.WHITE
            }
            Effect.HEARTS -> {
                p.x = r(0f, w); p.y = if (initial) r(0f, h) else h + 30f * d
                p.size = r(16f, 34f) * d; p.vy = -r(60f, 140f) * d; p.phase = r(0f, 6.28f)
                p.glyph = hearts[rnd.nextInt(hearts.size)]
            }
            Effect.FIRE -> {
                p.x = r(0f, w); p.y = h + 5f * d; p.vy = -r(90f, 260f) * d; p.vx = r(-20f, 20f) * d
                p.size = r(3f, 9f) * d; p.phase = r(0.5f, 1.2f)
                p.life = if (initial) r(0.1f, 1f) else 1f
            }
            Effect.FIREFLIES -> {
                p.x = r(0f, w); p.y = r(h * 0.3f, h)
                p.vx = r(-25f, 25f) * d; p.vy = r(-25f, 25f) * d
                p.size = r(2f, 4f) * d; p.phase = r(0f, 6.28f); p.vrot = r(1f, 3f)
            }
            Effect.BUBBLES -> {
                p.x = r(0f, w); p.y = if (initial) r(0f, h) else h + 40f * d
                p.size = r(8f, 30f) * d; p.vy = -r(40f, 100f) * d; p.phase = r(0f, 6.28f)
            }
            Effect.LEAVES -> {
                p.x = r(0f, w); p.y = if (initial) r(0f, h) else -30f * d
                p.size = r(18f, 32f) * d; p.vy = r(50f, 120f) * d; p.vx = r(-20f, 20f) * d
                p.rot = r(0f, 360f); p.vrot = r(-90f, 90f); p.phase = r(0f, 6.28f)
                p.glyph = leaves[rnd.nextInt(leaves.size)]
            }
            Effect.SAKURA -> {
                p.x = r(0f, w); p.y = if (initial) r(0f, h) else -20f * d
                p.size = r(5f, 10f) * d; p.vy = r(40f, 90f) * d; p.vx = r(-15f, 15f) * d
                p.rot = r(0f, 360f); p.vrot = r(-120f, 120f); p.phase = r(0f, 6.28f)
                p.color = petalColors[rnd.nextInt(petalColors.size)]
            }
            Effect.METEORS -> {
                p.life = 0f
                p.phase = r(0f, 3f)
            }
            Effect.MATRIX -> {
                val cols = max(1, (w / (16f * d)).toInt())
                p.x = rnd.nextInt(cols) * 16f * d + 8f * d
                p.size = 14f * d
                p.y = if (initial) r(0f, h) else -r(0f, h * 0.5f)
                p.vy = r(120f, 300f) * d
                p.life = (8 + rnd.nextInt(10)).toFloat()
                val sb = StringBuilder()
                repeat(20) { sb.append(matrixChars[rnd.nextInt(matrixChars.length)]) }
                p.glyph = sb.toString()
            }
            else -> {
            }
        }
    }

    private fun update(p: P, dt: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (base) {
            Effect.SNOW -> {
                p.phase += dt * 2f
                p.x += (p.vx + sin(p.phase) * 14f * d) * dt
                p.y += p.vy * dt
                if (p.y > h + p.size) spawn(p, false)
            }
            Effect.RAIN -> {
                p.x += p.vx * dt
                p.y += p.vy * dt
                if (p.y > h + p.size) spawn(p, false)
            }
            Effect.CONFETTI -> {
                p.phase += dt * 3f
                p.x += (p.vx + sin(p.phase) * 30f * d) * dt
                p.y += p.vy * dt
                p.rot += p.vrot * dt
                if (p.y > h + p.size) spawn(p, false)
            }
            Effect.STARS -> {
                p.phase += dt * p.vrot
            }
            Effect.HEARTS -> {
                p.phase += dt * 2f
                p.x += sin(p.phase) * 30f * d * dt
                p.y += p.vy * dt
                if (p.y < -p.size) spawn(p, false)
            }
            Effect.FIRE -> {
                p.x += p.vx * dt
                p.y += p.vy * dt
                p.life -= dt * p.phase
                if (p.life <= 0f) spawn(p, false)
            }
            Effect.FIREFLIES -> {
                p.phase += dt * p.vrot
                p.x += p.vx * dt
                p.y += p.vy * dt
                if (rnd.nextFloat() < dt * 0.8f) {
                    p.vx = r(-25f, 25f) * d
                    p.vy = r(-25f, 25f) * d
                }
                if (p.x < 0f) p.x = w
                if (p.x > w) p.x = 0f
                if (p.y < 0f) p.y = h
                if (p.y > h) p.y = 0f
            }
            Effect.BUBBLES -> {
                p.phase += dt * 1.5f
                p.x += sin(p.phase) * 20f * d * dt
                p.y += p.vy * dt
                if (p.y < -p.size * 2f) spawn(p, false)
            }
            Effect.LEAVES, Effect.SAKURA -> {
                p.phase += dt * 1.5f
                p.x += (p.vx + sin(p.phase) * 35f * d) * dt
                p.y += p.vy * dt
                p.rot += p.vrot * dt
                if (p.y > h + p.size) spawn(p, false)
            }
            Effect.METEORS -> {
                if (p.life <= 0f) {
                    p.phase -= dt
                    if (p.phase <= 0f) {
                        p.life = 1f
                        p.x = r(w * 0.2f, w * 1.1f)
                        p.y = r(-40f * d, h * 0.3f)
                        p.vx = -r(500f, 800f) * d
                        p.vy = r(300f, 520f) * d
                        p.size = r(1.5f, 3f) * d
                    }
                } else {
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                    if (p.y > h || p.x < -100f * d) {
                        p.life = 0f
                        p.phase = r(0.5f, 3.5f)
                    }
                }
            }
            Effect.MATRIX -> {
                p.y += p.vy * dt
                if (rnd.nextFloat() < dt * 6f) {
                    val idx = rnd.nextInt(p.glyph.length)
                    p.glyph = p.glyph.substring(0, idx) + matrixChars[rnd.nextInt(matrixChars.length)] + p.glyph.substring(idx + 1)
                }
                if (p.y - p.life * p.size > h) spawn(p, false)
            }
            else -> {
            }
        }
    }

    private fun drawGlyph(c: Canvas, g: String, x: Float, y: Float, size: Float, rot: Float, alpha: Int) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = alpha
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        c.save()
        c.translate(x, y)
        c.rotate(rot)
        c.drawText(g, 0f, size * 0.35f, paint)
        c.restore()
    }

    private fun draw(c: Canvas, p: P) {
        when (base) {
            Effect.SNOW -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.alpha = 220
                c.drawCircle(p.x, p.y, p.size, paint)
            }
            Effect.RAIN -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f * d
                paint.color = Color.parseColor("#AEE1FF")
                paint.alpha = 160
                c.drawLine(p.x, p.y, p.x - p.vx * 0.03f, p.y - p.size, paint)
            }
            Effect.CONFETTI -> {
                paint.style = Paint.Style.FILL
                paint.color = p.color
                c.save()
                c.translate(p.x, p.y)
                c.rotate(p.rot)
                c.drawRect(-p.size / 2f, -p.size / 4f, p.size / 2f, p.size / 4f, paint)
                c.restore()
            }
            Effect.STARS -> {
                val a = (140 + 115 * sin(p.phase)).toInt().coerceIn(0, 255)
                paint.style = Paint.Style.FILL
                paint.color = p.color
                paint.alpha = a
                c.drawCircle(p.x, p.y, p.size, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.8f * d
                paint.color = p.color
                paint.alpha = a
                c.drawLine(p.x - p.size * 2.2f, p.y, p.x + p.size * 2.2f, p.y, paint)
                c.drawLine(p.x, p.y - p.size * 2.2f, p.x, p.y + p.size * 2.2f, paint)
            }
            Effect.HEARTS -> drawGlyph(c, p.glyph, p.x, p.y, p.size, 0f, 230)
            Effect.FIRE -> {
                val l = p.life.coerceIn(0f, 1f)
                val col = when {
                    l > 0.66f -> Color.parseColor("#FFEB3B")
                    l > 0.33f -> Color.parseColor("#FF9800")
                    else -> Color.parseColor("#F44336")
                }
                paint.style = Paint.Style.FILL
                paint.color = col
                paint.alpha = (l * 220).toInt()
                c.drawCircle(p.x, p.y, p.size * (0.4f + l * 0.6f), paint)
            }
            Effect.FIREFLIES -> {
                val a = 0.5f + 0.5f * sin(p.phase)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#E8FF6B")
                paint.alpha = (60 * a).toInt()
                c.drawCircle(p.x, p.y, p.size * 4f, paint)
                paint.color = Color.parseColor("#E8FF6B")
                paint.alpha = (80 + 120 * a).toInt().coerceIn(0, 255)
                c.drawCircle(p.x, p.y, p.size, paint)
            }
            Effect.BUBBLES -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f * d
                paint.color = Color.WHITE
                paint.alpha = 140
                c.drawCircle(p.x, p.y, p.size, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.alpha = 120
                c.drawCircle(p.x - p.size * 0.35f, p.y - p.size * 0.35f, p.size * 0.15f, paint)
            }
            Effect.LEAVES -> drawGlyph(c, p.glyph, p.x, p.y, p.size, p.rot, 235)
            Effect.SAKURA -> {
                paint.style = Paint.Style.FILL
                paint.color = p.color
                c.save()
                c.translate(p.x, p.y)
                c.rotate(p.rot)
                rect.set(-p.size, -p.size * 0.55f, p.size, p.size * 0.55f)
                c.drawOval(rect, paint)
                c.restore()
            }
            Effect.METEORS -> {
                if (p.life > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.color = Color.WHITE
                    for (i in 0 until 8) {
                        val k = i / 8f
                        paint.alpha = ((1f - k) * 220).toInt()
                        paint.strokeWidth = p.size * (1f - k * 0.6f)
                        c.drawLine(
                            p.x - p.vx * 0.015f * i, p.y - p.vy * 0.015f * i,
                            p.x - p.vx * 0.015f * (i + 1), p.y - p.vy * 0.015f * (i + 1), paint
                        )
                    }
                    paint.style = Paint.Style.FILL
                    paint.alpha = 255
                    c.drawCircle(p.x, p.y, p.size * 1.2f, paint)
                }
            }
            Effect.MATRIX -> {
                paint.style = Paint.Style.FILL
                paint.textSize = p.size
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.MONOSPACE
                val n = p.life.toInt()
                for (i in 0 until n) {
                    val y = p.y - i * p.size
                    if (y < 0f || y > height + p.size) continue
                    paint.color = if (i == 0) Color.parseColor("#CCFFCC") else Color.parseColor("#00FF41")
                    paint.alpha = (255 * (1f - i / p.life)).toInt().coerceIn(0, 255)
                    c.drawText(p.glyph[i].toString(), p.x, y, paint)
                }
                paint.typeface = Typeface.DEFAULT
            }
            else -> {
            }
        }
    }

    private fun stepFireworks(c: Canvas, dt: Float) {
        rocketTimer -= dt
        if (rocketTimer <= 0f) {
            rocketTimer = r(0.7f, 1.8f)
            val cx = r(width * 0.15f, width * 0.85f)
            val cy = r(height * 0.1f, height * 0.45f)
            val col = confettiColors[rnd.nextInt(confettiColors.size)]
            repeat(46) {
                val a = r(0f, 6.283f)
                val sp = r(60f, 230f) * d
                sparks.add(P(x = cx, y = cy, vx = cos(a) * sp, vy = sin(a) * sp, size = r(1.5f, 3.2f) * d, color = col, life = 1f))
            }
        }
        val iter = sparks.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            s.vy += 180f * d * dt
            s.x += s.vx * dt
            s.y += s.vy * dt
            s.vx *= (1f - 0.6f * dt)
            s.life -= dt * 0.75f
            if (s.life <= 0f) {
                iter.remove()
            } else {
                paint.style = Paint.Style.FILL
                paint.color = s.color
                paint.alpha = (s.life * 255).toInt().coerceIn(0, 255)
                c.drawCircle(s.x, s.y, s.size, paint)
            }
        }
    }

    private fun stepStorm(dt: Float) {
        flashTimer -= dt
        if (flashTimer <= 0f) {
            flash = 1f
            flashTimer = r(2.5f, 7f)
            var bx = r(width * 0.15f, width * 0.85f)
            for (i in 0 until 8) {
                bolt[i * 2] = bx
                bolt[i * 2 + 1] = height * 0.12f * i
                bx += r(-30f, 30f) * d
            }
        }
        flash = max(0f, flash - dt * 2.2f)
    }

    private fun drawFlash(c: Canvas) {
        if (flash <= 0f) return
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = (flash * 120).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        if (flash > 0.4f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * d
            paint.color = Color.WHITE
            paint.alpha = (flash * 255).toInt().coerceIn(0, 255)
            for (i in 0 until 7) {
                c.drawLine(bolt[i * 2], bolt[i * 2 + 1], bolt[i * 2 + 2], bolt[i * 2 + 3], paint)
            }
        }
    }

    private fun drawTreeDecor(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val lights = intArrayOf(
            Color.parseColor("#FF5252"), Color.parseColor("#FFD740"),
            Color.parseColor("#69F0AE"), Color.parseColor("#40C4FF")
        )
        // гирлянда по верху экрана
        path.reset()
        val steps = 60
        for (i in 0..steps) {
            val x = w * i / steps
            val y = 14f * d + 18f * d * abs(sin(x / w * 4f * PI.toFloat()))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.color = Color.parseColor("#2E7D32")
        paint.alpha = 255
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        val n = 16
        for (i in 0..n) {
            val x = w * i / n
            val y = 14f * d + 18f * d * abs(sin(x / w * 4f * PI.toFloat()))
            paint.color = lights[(i + (time * 2f).toInt()) % 4]
            paint.alpha = if ((i + (time * 3f).toInt()) % 2 == 0) 255 else 110
            c.drawCircle(x, y + 5f * d, 4.5f * d, paint)
        }
        // ёлка в правом верхнем углу
        val th = min(h * 0.28f, 220f * d)
        val tw = th * 0.62f
        val y0 = 56f * d
        val cx = w - tw * 0.6f - 12f * d
        paint.alpha = 255
        for (t in 0 until 3) {
            val top = y0 + t * th * 0.26f
            val bottom = y0 + th * (0.42f + t * 0.26f)
            val half = tw * 0.5f * (0.55f + t * 0.25f)
            path.reset()
            path.moveTo(cx, top)
            path.lineTo(cx - half, bottom)
            path.lineTo(cx + half, bottom)
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = if (t % 2 == 0) Color.parseColor("#1B5E20") else Color.parseColor("#2E7D32")
            c.drawPath(path, paint)
        }
        paint.color = Color.parseColor("#5D4037")
        c.drawRect(cx - 6f * d, y0 + th, cx + 6f * d, y0 + th + 14f * d, paint)
        paint.color = Color.parseColor("#FFD740")
        c.drawCircle(cx, y0 - 2f * d, 7f * d, paint)
        for (k in 0 until 14) {
            val ty = y0 + th * (0.15f + 0.8f * frac(k * 0.37f))
            val half = tw * 0.5f * ((ty - y0) / th) * 0.9f
            val tx = cx + half * (2f * frac(k * 0.61f) - 1f) * 0.85f
            paint.color = lights[(k + (time * 2f).toInt()) % 4]
            paint.alpha = if ((k + (time * 3f).toInt()) % 2 == 0) 255 else 120
            c.drawCircle(tx, ty, 3.5f * d, paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min(0.05f, (now - last) / 1e9f)
        last = now
        time += dt
        if (effect == Effect.STORM) {
            stepStorm(dt)
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            paint.alpha = 50
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        for (p in ps) {
            update(p, dt)
            draw(canvas, p)
        }
        when (effect) {
            Effect.FIREWORKS -> stepFireworks(canvas, dt)
            Effect.STORM -> drawFlash(canvas)
            Effect.TREE -> drawTreeDecor(canvas)
            else -> {
            }
        }
        postInvalidateOnAnimation()
    }
}
