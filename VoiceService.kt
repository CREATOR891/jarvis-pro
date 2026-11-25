package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Слушает микрофон в фоне. Услышал слово "Джарвис" - выполняет команду, даже если приложение закрыто. */
class VoiceService : Service() {

    companion object {
        const val ACTION_STOP = "com.jarvis.app.STOP_VOICE"
        const val ACTION_PAUSE = "com.jarvis.app.PAUSE_VOICE"
        const val ACTION_RESUME = "com.jarvis.app.RESUME_VOICE"
        @Volatile var running = false
        private val wake = Regex("(джарвис|жарвис|джарвиз|джервис|джарви|jarvis|джарвіс)")
    }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var paused = false
    private var awaitingUntil = 0L
    private var errors = 0
    private var muted = false
    private lateinit var assistant: Assistant
    private lateinit var am: AudioManager
    private val startRunnable = Runnable { listen() }
    private val beepStreams = intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_MUSIC)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        assistant = Assistant.get(this)
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            startAsForeground()
            running = true
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("voice", "Слушаю Джарвиса", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Выключить", stopPi
        ).build()
        val n = Notification.Builder(this, "voice")
            .setContentTitle("Джарвис слушает")
            .setContentText("Скажи «Джарвис» и команду")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(action)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(2, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PAUSE -> {
                paused = true
                main.removeCallbacks(startRunnable)
                destroyRec()
            }
            ACTION_RESUME -> {
                paused = false
                listenSoon(400)
            }
            else -> listenSoon(200)
        }
        return START_NOT_STICKY
    }

    private fun listenSoon(ms: Long) {
        main.removeCallbacks(startRunnable)
        main.postDelayed(startRunnable, ms)
    }

    private fun destroyRec() {
        rec?.let {
            try { it.cancel(); it.destroy() } catch (e: Exception) { }
        }
        rec = null
        assistant.listening = false
    }

    /** Пытаемся приглушить "бунг" распознавания на момент старта (если не играет музыка). */
    private fun muteBeeps() {
        if (muted || am.isMusicActive) return
        for (s in beepStreams) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) } catch (e: Exception) { }
        }
        muted = true
        main.postDelayed({ unmuteBeeps() }, 800)
    }

    private fun unmuteBeeps() {
        if (!muted) return
        for (s in beepStreams) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        }
        muted = false
    }

    private fun backoff(): Long {
        errors++
        return when {
            errors < 3 -> 500L
            errors < 8 -> 1500L
            else -> 3000L
        }
    }

    private fun listen() {
        if (!running || paused) return
        if (assistant.speaking) { listenSoon(700); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            assistant.say("На телефоне нет службы распознавания речи")
            stopSelf()
            return
        }
        destroyRec()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { assistant.listening = true }
            override fun onBeginningOfSpeech() { errors = 0 }
            override fun onRmsChanged(rmsdB: Float) { assistant.onLoudness(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { assistant.listening = false }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                assistant.listening = false
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stopSelf(); return }
                listenSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500L else backoff())
            }
            override fun onResults(results: Bundle?) {
                assistant.listening = false
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) {
                    errors = 0
                    process(t)
                } else {
                    errors++
                }
                listenSoon(if (assistant.speaking) 900L else 400L)
            }
        })
        muteBeeps()
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
        )
    }

    private fun process(raw: String) {
        if (assistant.speaking) return
        val t = raw.lowercase(Locale("ru")).replace('ё', 'е')
        val m = wake.find(t)
        val now = System.currentTimeMillis()
        if (m != null) {
            val cmd = t.substring(m.range.last + 1).trim(' ', ',', '.', '!', '?')
            if (cmd.isEmpty()) {
                awaitingUntil = now + 8000
                assistant.say("Слушаю")
            } else {
                assistant.handle(cmd)
            }
        } else if (now < awaitingUntil) {
            awaitingUntil = 0
            assistant.handle(t)
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        unmuteBeeps()
        destroyRec()
        super.onDestroy()
    }
}
