package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var assistant: Assistant
    private lateinit var content: FrameLayout
    private val tabButtons = ArrayList<TextView>()
    private var statusTv: TextView? = null
    private var heardTv: TextView? = null
    private var logTv: TextView? = null
    private var wakeBtn: Button? = null
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private val logLines = ArrayList<String>()

    private val cyan = Color.parseColor("#00E5FF")
    private val grey = Color.parseColor("#8FA3BF")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assistant = Assistant.get(this)
        buildRoot()
        showTab(1)
        assistant.onStatus = { s ->
            runOnUiThread {
                statusTv?.text = s
                addLog(s)
            }
        }
        requestAll(false)
    }

    override fun onResume() {
        super.onResume()
        refreshWakeButton()
    }

    override fun onDestroy() {
        assistant.onStatus = null
        recognizer?.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------ помощники для интерфейса

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun glass(radius: Int = 18, stroke: String = "#5500E5FF", fill: String = "#99102040"): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun tv(text: String, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            background = glass(14, "#8800E5FF", "#CC15305F")
            setOnClickListener {
                SoundFx.click(this@MainActivity)
                onClick()
            }
        }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun row(vararg v: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            v.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
        }

    private fun toggle(label: String, key: String, def: Boolean, onChange: ((Boolean) -> Unit)? = null): Switch =
        Switch(this).apply {
            text = label
            setTextColor(Color.WHITE)
            isChecked = Prefs.bool(this@MainActivity, key, def)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnCheckedChangeListener { _, v ->
                Prefs.setBool(this@MainActivity, key, v)
                SoundFx.click(this@MainActivity)
                onChange?.invoke(v)
            }
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun addLog(s: String) {
        logLines.add(s)
        if (logLines.size > 30) logLines.removeAt(0)
        renderLog()
    }

    private fun renderLog() {
        logTv?.text = if (logLines.isEmpty()) "Здесь появятся ответы и реакции" else logLines.takeLast(8).joinToString("\n")
    }

    // ------------------------------------------------------------ каркас: 3 вкладки

    private fun buildRoot() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#050B1A"), Color.parseColor("#0A1B3D"))
            )
            setPadding(0, dp(24), 0, 0)
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60A1530"))
                setStroke(dp(1), Color.parseColor("#3300E5FF"))
            }
        }
        listOf("👤\nПерсонажи", "🎙\nПомощник", "✨\nЭффекты").forEachIndexed { i, name ->
            val t = tv(name, 12f, grey, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener {
                    SoundFx.open(this@MainActivity)
                    showTab(i)
                }
            }
            tabButtons.add(t)
            nav.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
    }

    private fun showTab(i: Int) {
        content.removeAllViews()
        val v: View = when (i) {
            0 -> charactersTab()
            1 -> assistantTab()
            else -> effectsTab()
        }
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        tabButtons.forEachIndexed { idx, b -> b.setTextColor(if (idx == i) cyan else grey) }
    }

    // ------------------------------------------------------------ вкладка "Персонажи"

    private fun charactersTab(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
        }
        col.addView(tv("Персонажи", 24f, cyan, true))
        col.addView(tv("У каждого свой голос, характер и реакции. VIP открываются ключом.", 13f, grey))
        val cur = assistant.persona().id
        for (p in Personas.all) col.addView(personaCard(p, p.id == cur), lp(12))
        sv.addView(col)
        return sv
    }

    private fun personaCard(p: Persona, selected: Boolean): View {
        val unlocked = Unlock.isUnlocked(this, p)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = glass(18, if (selected) "#FF00E5FF" else "#4400E5FF", "#AA102040")
            setOnClickListener { onPersonaTap(p) }
        }
        val avatar = TextView(this).apply {
            text = p.name.take(1)
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(p.color)
            }
        }
        card.addView(avatar, LinearLayout.LayoutParams(dp(56), dp(56)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv(p.name, 18f, Color.WHITE, true))
        val badge = tv(if (p.paid) "  VIP  " else "  FREE  ", 11f, Color.BLACK, true).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (p.paid) Color.parseColor("#FFD740") else Color.parseColor("#69F0AE"))
            }
        }
        head.addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        info.addView(head)
        info.addView(tv(p.tagline, 13f, cyan))
        info.addView(tv(p.description, 13f, Color.LTGRAY))
        info.addView(tv("Голос: " + p.voiceInfo(), 12f, grey))
        val status = when {
            selected -> "✓ Выбран"
            p.paid && !unlocked -> "🔒 Заблокирован — нажми, чтобы открыть"
            else -> "Нажми, чтобы выбрать"
        }
        info.addView(tv(status, 12f, if (selected) cyan else grey))
        card.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
        return card
    }

    private fun onPersonaTap(p: Persona) {
        SoundFx.click(this)
        if (p.paid && !Unlock.isUnlocked(this, p)) {
            showUnlockDialog(p)
        } else {
            assistant.switchPersona(p)
            showTab(0)
        }
    }

    private fun showUnlockDialog(p: Persona) {
        val input = EditText(this).apply { hint = "Промо-ключ" }
        AlertDialog.Builder(this)
            .setTitle("🔒 " + p.name + " · VIP")
            .setMessage("Этот персонаж платный. В бета-версии его можно открыть промо-ключом или тестовой активацией.")
            .setView(input)
            .setPositiveButton("Активировать ключ") { _, _ ->
                if (Unlock.tryKey(this, input.text.toString()) && Unlock.isUnlocked(this, p)) {
                    toast("Ключ принят: " + p.name + " открыт")
                    assistant.switchPersona(p)
                    showTab(0)
                } else {
                    toast("Неверный ключ")
                }
            }
            .setNeutralButton("Тест-активация (бета)") { _, _ ->
                Unlock.unlock(this, p.id)
                toast("Тестовая активация: " + p.name + " открыт")
                assistant.switchPersona(p)
                showTab(0)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ------------------------------------------------------------ вкладка "Помощник"

    private fun assistantTab(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(20))
        }
        val p = assistant.persona()
        col.addView(tv("JARVIS v2.0  β", 22f, cyan, true).apply { gravity = Gravity.CENTER })
        col.addView(tv("Персонаж: " + p.name, 13f, grey).apply { gravity = Gravity.CENTER })
        col.addView(OrbView(this), LinearLayout.LayoutParams(MATCH, dp(250)))

        val st = tv("Нажми на микрофон или скажи «Джарвис»", 16f).apply { gravity = Gravity.CENTER }
        statusTv = st
        col.addView(st)
        val hd = tv("", 13f, Color.LTGRAY).apply { gravity = Gravity.CENTER }
        heardTv = hd
        col.addView(hd)

        col.addView(btn("🎤  Говорить") { onMicPressed() }, lp(10))
        val wb = btn("") { toggleWake() }
        wakeBtn = wb
        col.addView(wb, lp(6))
        refreshWakeButton()
        col.addView(
            row(
                btn("🧍 Персонаж на экране") { assistant.showCharacter() },
                btn("🙈 Спрятать") { assistant.hideCharacter() }
            ), lp(6)
        )

        col.addView(tv("Лента диалога и реакций", 15f, cyan, true), lp(16))
        val lg = tv("", 13f, Color.LTGRAY).apply {
            background = glass()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = 3
        }
        logTv = lg
        col.addView(lg, lp(6))
        renderLog()

        col.addView(tv("Тест: меню удаления приложений", 15f, cyan, true), lp(16))
        col.addView(tv("Включи на вкладке «Эффекты» «Смотреть на экран» и службу доступности: ассистент среагирует на меню удаления.", 12f, grey))
        col.addView(btn("📱 Открыть список приложений (удаление)") { openAppList() }, lp(6))
        col.addView(
            row(
                btn("😮 Удивление") { assistant.reactTest("surprise") },
                btn("😢 Грусть") { assistant.reactTest("sad") },
                btn("😱 Испуг") { assistant.reactTest("scared") }
            ), lp(6)
        )
        sv.addView(col)
        return sv
    }

    private fun openAppList() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ------------------------------------------------------------ вкладка "Эффекты"

    private fun effectsTab(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }
        col.addView(tv("Эффекты и настройки", 24f, cyan, true))

        val sw = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass()
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        sw.addView(toggle("Эффекты включены", "fx_on", true) { v -> if (!v) EffectService.stopEffect(this@MainActivity) })
        sw.addView(toggle("Эффекты в меню удаления приложений", "fx_uninstall", true))
        sw.addView(toggle("Звуки интерфейса и эффектов (SFX)", "sfx", true) { v -> if (!v) SoundFx.stopAmbient() })
        sw.addView(toggle("Озвучка голосом (TTS)", "tts", true))
        sw.addView(toggle("Смотреть на экран", "watch", false) { v ->
            if (v && JarvisAccess.instance == null) {
                toast("Включи службу доступности Джарвис, иначе я не увижу экран")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        sw.addView(toggle("Реакция на крик", "scream", false) { v ->
            if (v) {
                if (!VoiceService.running) toggleWake()
                toast("Крик слышно, пока включено прослушивание в фоне")
            }
        })
        col.addView(sw, lp(10))

        col.addView(tv("Эффекты", 16f, cyan, true), lp(16))
        Effect.values().toList().chunked(2).forEach { pair ->
            col.addView(row(*pair.map { e -> btn(e.emoji + " " + e.label) { assistant.startEffect(e) } }.toTypedArray()), lp(4))
        }
        col.addView(btn("⛔ Убрать эффект") { assistant.stopEffect() }, lp(8))
        col.addView(
            row(
                btn("🧍 Персонаж") { assistant.showCharacter() },
                btn("🙈 Спрятать") { assistant.hideCharacter() }
            ), lp(4)
        )

        col.addView(tv("Разрешения (один раз)", 16f, cyan, true), lp(20))
        setupItem(col, "🎤 Микрофон, звонки, контакты, смс, блютуз",
            "Нужны для голоса, ответа на звонки, сообщений и реакции на крик.") { requestAll(true) }
        setupItem(col, "🖐 Служба доступности",
            "Чтобы Джарвис нажимал кнопки, закрывал приложения и видел меню удаления. На Android 13+ сначала: «Инфо о приложении → ⋮ → Разрешить ограниченные настройки».") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        setupItem(col, "🪟 Поверх других окон", "Нужно, чтобы снег, дождь, ёлка и персонаж были видны над другими приложениями.") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        setupItem(col, "💡 Изменение системных настроек", "Для яркости, автоповорота и таймаута экрана.") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        setupItem(col, "🔋 Работа в фоне", "Чтобы слово «Джарвис» слушалось, когда приложение закрыто.") {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        setupItem(col, "🔕 Доступ к «Не беспокоить»", "Помогает убрать писк при фоновом прослушивании.") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        setupItem(col, "ℹ️ Инфо о приложении", "Здесь же можно разрешить ограниченные настройки (Android 13+).") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        sv.addView(col)
        return sv
    }

    private fun setupItem(parent: LinearLayout, title: String, hint: String, onClick: () -> Unit) {
        parent.addView(btn(title, onClick), lp(8))
        parent.addView(tv(hint, 12f, grey))
    }

    // ------------------------------------------------------------ разрешения

    private fun requestAll(showToast: Boolean) {
        val p = ArrayList<String>()
        p.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        p.add(Manifest.permission.ANSWER_PHONE_CALLS)
        p.add(Manifest.permission.CALL_PHONE)
        p.add(Manifest.permission.READ_CONTACTS)
        p.add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 31) p.add(Manifest.permission.BLUETOOTH_CONNECT)
        val need = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        else if (showToast) toast("Все разрешения уже выданы")
    }

    // ------------------------------------------------------------ слово "Джарвис" в фоне

    private fun refreshWakeButton() {
        wakeBtn?.text = if (VoiceService.running) "👂 Слушаю «Джарвис» в фоне: ВКЛ" else "👂 Слушать «Джарвис» в фоне: ВЫКЛ"
    }

    private fun toggleWake() {
        if (VoiceService.running) {
            stopService(Intent(this, VoiceService::class.java))
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestAll(false)
                return
            }
            startForegroundService(Intent(this, VoiceService::class.java))
        }
        main.postDelayed({ refreshWakeButton() }, 400)
    }

    private fun wakeAction(action: String) {
        if (VoiceService.running) startService(Intent(this, VoiceService::class.java).setAction(action))
    }

    // ------------------------------------------------------------ кнопка микрофона

    private fun onMicPressed() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAll(false)
            return
        }
        val wasRunning = VoiceService.running
        wakeAction(VoiceService.ACTION_PAUSE)
        main.postDelayed({ startListening() }, if (wasRunning) 600L else 0L)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusTv?.text = "На телефоне нет службы распознавания речи (нужно приложение Google)"
            wakeAction(VoiceService.ACTION_RESUME)
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        assistant.listening = true
                        statusTv?.text = "Слушаю…"
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) { assistant.onLoudness(rmsdB) }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { assistant.listening = false }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onError(error: Int) {
                        assistant.listening = false
                        statusTv?.text = "Не расслышал, попробуй ещё раз"
                        wakeAction(VoiceService.ACTION_RESUME)
                    }
                    override fun onResults(results: Bundle?) {
                        assistant.listening = false
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (text != null) {
                            heardTv?.text = "«$text»"
                            assistant.handle(text)
                        }
                        wakeAction(VoiceService.ACTION_RESUME)
                    }
                })
            }
        }
        recognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            }
        )
    }
}
