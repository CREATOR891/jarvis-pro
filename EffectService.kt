package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.view.View
import android.view.WindowManager

/** Показывает поверх всего эффект (снег, дождь, ёлка...) и/или ходячего персонажа. Нажатия проходят насквозь. */
class EffectService : Service() {

    companion object {
        const val ACTION_EFFECT = "com.jarvis.app.EFFECT"
        const val ACTION_CHAR = "com.jarvis.app.CHAR"
        const val ACTION_STOP_EFFECT = "com.jarvis.app.STOP_EFFECT"
        const val ACTION_STOP_CHAR = "com.jarvis.app.STOP_CHAR"
        const val ACTION_STOP = "com.jarvis.app.STOP_ALL"

        @Volatile var effectOn = false
        @Volatile var charOn = false

        private fun go(ctx: Context, action: String, e: Effect? = null) {
            val i = Intent(ctx, EffectService::class.java).setAction(action)
            if (e != null) i.putExtra("effect", e.name)
            ctx.startForegroundService(i)
        }

        fun showEffect(ctx: Context, e: Effect) = go(ctx, ACTION_EFFECT, e)
        fun showCharacter(ctx: Context) = go(ctx, ACTION_CHAR)
        fun stopEffect(ctx: Context) { if (effectOn) go(ctx, ACTION_STOP_EFFECT) }
        fun stopCharacter(ctx: Context) { if (charOn) go(ctx, ACTION_STOP_CHAR) }
        fun stopAll(ctx: Context) { if (effectOn || charOn) go(ctx, ACTION_STOP) }
    }

    private var effectView: View? = null
    private var charView: View? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_EFFECT -> {
                val e = try {
                    Effect.valueOf(intent?.getStringExtra("effect") ?: "")
                } catch (ex: Exception) {
                    Effect.SNOW
                }
                addEffect(e)
            }
            ACTION_CHAR -> addCharacter()
            ACTION_STOP_EFFECT -> removeEffect()
            ACTION_STOP_CHAR -> removeCharacter()
            ACTION_STOP -> {
                removeEffect()
                removeCharacter()
            }
            else -> {
            }
        }
        if (effectView == null && charView == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        if (foreground) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("effects", "Эффекты", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, EffectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Выключить всё",
            stopPi
        ).build()
        val n = Notification.Builder(this, "effects")
            .setContentTitle("Эффекты и персонаж на экране")
            .setContentText("Скажи: Джарвис, убери эффект")
            .setSmallIcon(android.R.drawable.star_on)
            .addAction(action)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        foreground = true
    }

    private fun params(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    )

    private fun prepare(v: View) {
        v.isClickable = false
        v.isFocusable = false
        v.isFocusableInTouchMode = false
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun addEffect(e: Effect) {
        removeEffect()
        val v = EffectView(this, e)
        prepare(v)
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, params())
            effectView = v
            effectOn = true
            SoundFx.startAmbient(this, e)
        } catch (ex: Exception) {
        }
    }

    private fun addCharacter() {
        removeCharacter()
        val v = CharacterView(this, Assistant.get(this).persona())
        prepare(v)
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, params())
            charView = v
            charOn = true
        } catch (ex: Exception) {
        }
    }

    private fun removeView(v: View?) {
        if (v == null) return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (ex: Exception) {
        }
    }

    private fun removeEffect() {
        removeView(effectView)
        effectView = null
        effectOn = false
        SoundFx.stopAmbient()
    }

    private fun removeCharacter() {
        removeView(charView)
        charView = null
        charOn = false
    }

    override fun onDestroy() {
        removeEffect()
        removeCharacter()
        super.onDestroy()
    }
}
