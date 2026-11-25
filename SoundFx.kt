package com.jarvis.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

private const val SR = 22050

/** Короткий звук-"событие" (колокольчик, капля, хлопок...). */
private class Ev {
    private var env = 0f
    private var decay = 0.9999f
    private var f = 1000f
    private var df = 1f
    private var noise = 0f
    private var ph = 0.0

    fun fire(env: Float, decay: Float, f: Float, df: Float = 1f, noise: Float = 0f) {
        this.env = env
        this.decay = decay
        this.f = f
        this.df = df
        this.noise = noise
    }

    fun tick(rnd: Random): Float {
        if (env < 0.0003f) return 0f
        ph += 2.0 * PI * f / SR
        if (ph > 2.0 * PI) ph -= 2.0 * PI
        f *= df
        env *= decay
        val tone = sin(ph).toFloat()
        val nz = rnd.nextFloat() * 2f - 1f
        return env * ((1f - noise) * tone + noise * nz)
    }
}

/** Таймер случайных событий. */
private class Sched(private val minS: Float, private val maxS: Float, private val rnd: Random) {
    private var left = (rnd.nextFloat() * maxS * SR).toInt() + 1
    fun due(): Boolean {
        left--
        if (left <= 0) {
            left = ((minS + rnd.nextFloat() * (maxS - minS)) * SR).toInt() + 1
            return true
        }
        return false
    }
}

/** Генератор звука эффекта прямо в коде, без файлов. */
private class Gen(kind: String) {
    private val rnd = Random()
    private val wind = kind == "snow" || kind == "wind" || kind == "tree"
    private val rain = kind == "rain" || kind == "storm"
    private val fire = kind == "fire"
    private val jingle = kind == "snow" || kind == "tree"
    private val chime = kind == "chime" || kind == "tree"
    private val thunder = kind == "storm" || kind == "fireworks"
    private val isStorm = kind == "storm"
    private val bubble = kind == "bubble"
    private val blip = kind == "blip"
    private val whoosh = kind == "whoosh"
    private val pop = kind == "pop" || kind == "fireworks"

    private var lpWind = 0f
    private var swell = 0f
    private var lpRain = 0f
    private var lpFire = 0f
    private var burstLeft = 0
    private var burstTimer = 0

    private val bell1 = Ev()
    private val bell2 = Ev()
    private val chimeEv = Ev()
    private val chime2 = Ev()
    private val crack = Ev()
    private val thun = Ev()
    private val bub = Ev()
    private val bl = Ev()
    private val wh = Ev()
    private val pp = Ev()

    private val sJingle = Sched(3f, 7f, rnd)
    private val sChime = Sched(1.2f, 3.5f, rnd)
    private val sCrack = Sched(0.05f, 0.35f, rnd)
    private val sThunder = Sched(if (kind == "storm") 5f else 1.6f, if (kind == "storm") 12f else 3.6f, rnd)
    private val sBub = Sched(0.4f, 1.6f, rnd)
    private val sBlip = Sched(0.15f, 0.7f, rnd)
    private val sWhoosh = Sched(2f, 6f, rnd)
    private val sPop = Sched(0.25f, 1.0f, rnd)

    private val scale = floatArrayOf(523f, 587f, 659f, 784f, 880f, 1047f, 1175f)

    fun next(): Float {
        var s = 0f
        val n = rnd.nextFloat() * 2f - 1f

        if (wind) {
            lpWind += 0.015f * (n - lpWind)
            swell += 0.0000427f
            if (swell > 6.2831855f) swell -= 6.2831855f
            s += lpWind * 2.4f * (0.55f + 0.45f * sin(swell))
        }
        if (rain) {
            lpRain += 0.35f * (n - lpRain)
            s += (n - lpRain) * 0.2f
        }
        if (fire) {
            lpFire += 0.01f * (n - lpFire)
            s += lpFire * 1.6f
            if (sCrack.due()) crack.fire(0.6f, 0.995f, 800f, 1f, 1f)
            s += crack.tick(rnd)
        }
        if (jingle) {
            if (sJingle.due()) burstLeft = 3 + rnd.nextInt(3)
            if (burstLeft > 0) {
                burstTimer--
                if (burstTimer <= 0) {
                    val f0 = 2400f + rnd.nextFloat() * 1200f
                    bell1.fire(0.28f, 0.9999f, f0)
                    bell2.fire(0.14f, 0.9998f, f0 * 1.51f)
                    burstLeft--
                    burstTimer = (0.11f * SR).toInt()
                }
            }
            s += bell1.tick(rnd) + bell2.tick(rnd)
        }
        if (chime) {
            if (sChime.due()) {
                val f0 = scale[rnd.nextInt(scale.size)]
                chimeEv.fire(0.22f, 0.99992f, f0)
                chime2.fire(0.08f, 0.99985f, f0 * 2f)
            }
            s += chimeEv.tick(rnd) + chime2.tick(rnd)
        }
        if (thunder) {
            if (sThunder.due()) thun.fire(0.8f, 0.99996f, if (isStorm) 45f else 70f, 0.99999f, 0.3f)
            s += thun.tick(rnd)
        }
        if (bubble) {
            if (sBub.due()) bub.fire(0.3f, 0.9992f, 380f + rnd.nextFloat() * 450f, 1.0003f)
            s += bub.tick(rnd)
        }
        if (blip) {
            if (sBlip.due()) bl.fire(0.18f, 0.9997f, 600f + rnd.nextInt(8) * 150f)
            s += bl.tick(rnd)
        }
        if (whoosh) {
            if (sWhoosh.due()) wh.fire(0.5f, 0.99993f, 1500f, 1f, 0.95f)
            s += wh.tick(rnd)
        }
        if (pop) {
            if (sPop.due()) pp.fire(0.5f, 0.996f, 1200f, 0.9995f, 0.6f)
            s += pp.tick(rnd)
        }
        return (s * 0.7f).coerceIn(-1f, 1f)
    }
}

object SoundFx {
    private val main = Handler(Looper.getMainLooper())
    private var flag: AtomicBoolean? = null

    private fun attrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun format(): AudioFormat = AudioFormat.Builder()
        .setSampleRate(SR)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    private fun kindOf(e: Effect): String? = when (e) {
        Effect.SNOW -> "snow"
        Effect.TREE -> "tree"
        Effect.RAIN -> "rain"
        Effect.STORM -> "storm"
        Effect.FIRE -> "fire"
        Effect.FIREWORKS -> "fireworks"
        Effect.STARS, Effect.FIREFLIES, Effect.HEARTS -> "chime"
        Effect.BUBBLES -> "bubble"
        Effect.LEAVES, Effect.SAKURA -> "wind"
        Effect.CONFETTI -> "pop"
        Effect.METEORS -> "whoosh"
        Effect.MATRIX -> "blip"
        else -> null
    }

    /** Фоновый звук эффекта (ветер и бубенцы для снега, шум дождя, треск огня...). */
    fun startAmbient(ctx: Context, e: Effect) {
        stopAmbient()
        if (!Prefs.bool(ctx, "sfx", true)) return
        val kind = kindOf(e) ?: return
        val f = AtomicBoolean(true)
        flag = f
        val t = Thread {
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val tr = AudioTrack.Builder()
                    .setAudioAttributes(attrs())
                    .setAudioFormat(format())
                    .setBufferSizeInBytes(max(minBuf, 4096) * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = tr
                tr.play()
                val g = Gen(kind)
                val buf = ShortArray(1024)
                while (f.get()) {
                    for (i in buf.indices) buf[i] = (g.next() * 32767f).toInt().toShort()
                    tr.write(buf, 0, buf.size)
                }
            } catch (ex: Exception) {
            } finally {
                try { track?.stop() } catch (ex: Exception) { }
                try { track?.release() } catch (ex: Exception) { }
            }
        }
        t.isDaemon = true
        t.start()
    }

    fun stopAmbient() {
        flag?.set(false)
        flag = null
    }

    private fun playTone(ctx: Context, f0: Float, f1: Float, ms: Int, vol: Float, noise: Float = 0f) {
        if (!Prefs.bool(ctx, "sfx", true)) return
        try {
            val n = SR * ms / 1000
            val buf = ShortArray(n)
            val rnd = Random()
            var ph = 0.0
            for (i in 0 until n) {
                val k = i / n.toFloat()
                val f = f0 + (f1 - f0) * k
                ph += 2.0 * PI * f / SR
                val env = (1f - k) * (1f - k)
                val v = ((1f - noise) * sin(ph).toFloat() + noise * (rnd.nextFloat() * 2f - 1f)) * env * vol
                buf[i] = (v * 32767f).toInt().toShort()
            }
            val t = AudioTrack(attrs(), format(), n * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            t.write(buf, 0, n)
            t.play()
            main.postDelayed({ try { t.release() } catch (ex: Exception) { } }, ms + 300L)
        } catch (ex: Exception) {
        }
    }

    fun click(ctx: Context) = playTone(ctx, 1800f, 1200f, 35, 0.3f)
    fun open(ctx: Context) = playTone(ctx, 600f, 950f, 90, 0.35f)
    fun delete(ctx: Context) = playTone(ctx, 700f, 220f, 260, 0.4f, 0.1f)
    fun loud(ctx: Context) = playTone(ctx, 950f, 700f, 220, 0.5f)
    fun chime(ctx: Context) = playTone(ctx, 880f, 1320f, 160, 0.35f)
}
