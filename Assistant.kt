package com.jarvis.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Мозг Джарвиса: понимает текст команды, делает дело и отвечает голосом выбранного персонажа. */
class Assistant private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var inst: Assistant? = null
        fun get(c: Context): Assistant =
            inst ?: synchronized(this) { inst ?: Assistant(c.applicationContext).also { inst = it } }
    }

    private val main = Handler(Looper.getMainLooper())
    var onStatus: ((String) -> Unit)? = null
    @Volatile var speaking = false
    @Volatile var listening = false
    @Volatile var emotion = Emotion.IDLE
    @Volatile var lastText = ""
    @Volatile var lastTextAt = 0L

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, Emotion?>? = null
    private var pitchAdj = 1f
    private var rateAdj = 1f
    private var slotOverride: Int? = null
    private var ruVoices: List<Voice> = emptyList()
    private var emoToken = 0
    private var loud = 0
    private val lastReact = HashMap<String, Long>()

    private val jokes = listOf(
        "Жена просит программиста: купи батон, и если будут яйца, возьми десяток. Он вернулся с десятью батонами. Потому что яйца были.",
        "Я бы рассказал шутку про UDP, но не уверен, что она до тебя дойдёт.",
        "Почему телефон пошёл к врачу? Потому что поймал вирус."
    )

    private val aliases = mapOf(
        "телеграм" to "telegram", "телега" to "telegram", "ватсап" to "whatsapp", "вацап" to "whatsapp",
        "ютуб" to "youtube", "хром" to "chrome", "браузер" to "chrome", "камера" to "camera",
        "камеру" to "camera", "галерея" to "gallery", "фото" to "photos",
        "инстаграм" to "instagram", "тикток" to "tiktok", "вайбер" to "viber", "вк" to "vk",
        "почта" to "gmail", "почту" to "gmail", "карты" to "maps", "карту" to "maps",
        "часы" to "clock", "калькулятор" to "calculator", "календарь" to "calendar",
        "контакты" to "contacts", "телефон" to "phone", "сообщения" to "messages",
        "файлы" to "files", "плей маркет" to "play", "гугл" to "google", "музыку" to "music"
    )

    private val tiles: List<Triple<String, List<String>, List<String>>> = listOf(
        Triple("режим полёта", listOf("режим полета", "авиарежим", "авиа режим", "в самолете"), listOf("airplane", "режим полета", "в самолете", "авиа")),
        Triple("не беспокоить", listOf("не беспокоить"), listOf("do not disturb", "не беспокоить")),
        Triple("экономия заряда", listOf("экономи", "энергосбереж"), listOf("battery saver", "экономия", "энергосбереж")),
        Triple("местоположение", listOf("местоположен", "геолокац", "геопозиц"), listOf("location", "местоположение", "геолокация", "геопозиция")),
        Triple("точка доступа", listOf("точку доступа", "точка доступа", "хотспот", "модем"), listOf("hotspot", "точка доступа", "модем")),
        Triple("NFC", listOf("nfc", "нфс"), listOf("nfc")),
        Triple("мобильный интернет", listOf("мобильный интернет", "мобильные данные", "передачу данных"), listOf("mobile data", "мобильный интернет", "мобильные данные", "передача данных"))
    )

    init {
        tts = TextToSpeech(ctx) { code -> onTtsInit(code) }
    }

    private fun onTtsInit(code: Int) {
        val t = tts ?: return
        if (code != TextToSpeech.SUCCESS) return
        t.language = Locale("ru", "RU")
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) { endSpeak() }
            override fun onError(utteranceId: String?) { endSpeak() }
        })
        ruVoices = try {
            t.voices?.filter { it.locale.language == "ru" }?.sortedBy { it.name } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        ready = true
        pending?.let { pending = null; speakNow(it.first, it.second) }
    }

    private fun endSpeak() {
        main.postDelayed({ speaking = false }, 600)
    }

    fun persona(): Persona = Personas.byId(Prefs.str(ctx, "persona", "jarvis"))

    // ---------------------------------------------------------------- речь и эмоции

    fun say(text: String, emo: Emotion? = null, raw: Boolean = false) {
        val p = persona()
        val spoken = if (raw) text else p.style(text)
        lastText = spoken
        lastTextAt = System.currentTimeMillis()
        if (emo != null) setEmotion(emo)
        main.post { onStatus?.invoke(spoken) }
        if (!Prefs.bool(ctx, "tts", true)) return
        speakNow(spoken, emo)
    }

    private fun speakNow(spoken: String, emo: Emotion?) {
        val t = tts
        if (!ready || t == null) { pending = Pair(spoken, emo); return }
        applyVoice(t, persona(), emo)
        speaking = true
        main.postDelayed({ speaking = false }, 25000)
        t.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun applyVoice(t: TextToSpeech, p: Persona, emo: Emotion?) {
        if (ruVoices.isNotEmpty()) {
            val n = ruVoices.size
            val idx = (((slotOverride ?: p.voiceSlot) % n) + n) % n
            try {
                if (t.voice?.name != ruVoices[idx].name) t.voice = ruVoices[idx]
            } catch (e: Exception) {
            }
        }
        var pitch = p.pitch * pitchAdj
        var rate = p.rate * rateAdj
        when (emo) {
            Emotion.SURPRISED -> { pitch *= 1.15f; rate *= 1.1f }
            Emotion.SAD -> { pitch *= 0.9f; rate *= 0.85f }
            Emotion.SCARED -> { pitch *= 1.25f; rate *= 1.2f }
            Emotion.HAPPY -> { pitch *= 1.08f; rate *= 1.05f }
            else -> {
            }
        }
        t.setPitch(pitch.coerceIn(0.3f, 2f))
        t.setSpeechRate(rate.coerceIn(0.4f, 2f))
    }

    fun setEmotion(e: Emotion, holdMs: Long = 3500) {
        main.post {
            emotion = e
            val tok = ++emoToken
            main.postDelayed({ if (tok == emoToken) emotion = Emotion.IDLE }, holdMs)
        }
    }

    // ---------------------------------------------------------------- реакции на экран и крик

    private fun throttled(key: String, ms: Long, force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - (lastReact[key] ?: 0L) < ms) return true
        lastReact[key] = now
        return false
    }

    fun onUninstallMenu(force: Boolean) {
        if (throttled("uninstall", 10000, force)) return
        if (!Prefs.bool(ctx, "fx_uninstall", true)) EffectService.stopAll(ctx)
        SoundFx.open(ctx)
        say(persona().surprise, Emotion.SURPRISED, true)
    }

    fun onAppDeleted(force: Boolean) {
        if (throttled("deleted", 8000, force)) return
        SoundFx.delete(ctx)
        say(persona().sad, Emotion.SAD, true)
    }

    fun onScream(force: Boolean) {
        if (throttled("scream", 12000, force)) return
        SoundFx.loud(ctx)
        say(persona().scared, Emotion.SCARED, true)
    }

    /** Уровень звука с микрофона (из распознавания речи). */
    fun onLoudness(rms: Float) {
        if (!Prefs.bool(ctx, "scream", false)) return
        if (rms >= 9.5f) loud++ else loud = 0
        if (loud >= 3) {
            loud = 0
            onScream(false)
        }
    }

    fun reactTest(kind: String) {
        when (kind) {
            "surprise" -> onUninstallMenu(true)
            "sad" -> onAppDeleted(true)
            else -> onScream(true)
        }
    }

    // ---------------------------------------------------------------- утилиты

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun startNew(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) { say("Не получилось это открыть") }
    }

    private fun access(block: (JarvisAccess) -> Unit) {
        val a = JarvisAccess.instance
        if (a == null) {
            say("Включи службу доступности Джарвис в настройках телефона")
            startNew(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        block(a)
    }

    private fun tileFor(norm: String): Triple<String, List<String>, List<String>>? =
        tiles.firstOrNull { t -> t.second.any { norm.contains(it) } }

    // ---------------------------------------------------------------- команды

    fun handle(raw: String) {
        val norm = raw.lowercase(Locale("ru")).replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}:+]+"), " ").trim()
        val words = norm.split(' ').filter { it.isNotEmpty() }

        fun m(w: String, kw: String) = if (kw.endsWith("*")) w.startsWith(kw.dropLast(1)) else w == kw
        fun has(vararg k: String) = words.any { w -> k.any { kw -> m(w, kw) } }
        fun after(vararg k: String): List<String> {
            val i = words.indexOfFirst { w -> k.any { kw -> m(w, kw) } }
            return if (i < 0) emptyList() else words.drop(i + 1)
        }

        val offVerb = has("выключи", "отключи", "убери", "останови", "хватит", "прекрати", "выруби", "закрой", "спрячь")
        val onVerb = has("включи", "запусти", "покажи", "пусти", "добавь", "давай", "сделай", "хочу")
        val eff = Effect.values().firstOrNull { e -> has(*e.words.toTypedArray()) }
        val pm = Personas.match(words)
        val tile = if (onVerb || offVerb) tileFor(norm) else null
        val hasDigits = Regex("\\d").containsMatchIn(norm)

        when {
            // звонки
            has("возьми", "ответь", "прими", "подними") && has("трубку", "звонок", "вызов") -> answerCall()
            has("положи", "сбрось", "отклони", "заверши", "повесь", "скинь") && has("трубку", "звонок", "вызов") -> endCall()
            has("позвони", "набери") -> callPerson(after("позвони", "набери"))

            // сообщения
            has("напиши", "отправь", "пошли") && words.size >= 3 -> sendMessage(words)

            // удаление приложения (откроется системное окно, подтверждаешь сам)
            has("удали", "удалить", "деинсталлируй") && words.size >= 2 ->
                uninstallApp(after("удали", "удалить", "деинсталлируй")
                    .filter { it !in setOf("приложение", "программу", "приложения", "это") }.joinToString(" "))

            // персонажи и режимы
            (has("обычный", "стандартный") && has("режим*")) || (offVerb && has("режим*")) -> switchPersona(Personas.all[0])
            pm != null && (onVerb || has("режим*", "персонаж*", "переключи*", "поговори*", "стань", "стать", "голос*", "говори")) ->
                switchPersona(pm)
            offVerb && has("персонаж*", "человечк*") -> hideCharacter()
            (onVerb || has("покажись", "выйди", "появись")) && has("персонаж*", "человечк*") -> showCharacter()

            // тембр голоса
            has("голос", "голоса", "голосом") -> voiceCommand(words)

            // эффекты
            eff != null && offVerb -> stopEffect()
            eff != null && onVerb -> startEffect(eff)
            offVerb && has("эффект*", "анимаци*") -> stopEffect()
            onVerb && has("эффект*", "анимаци*") ->
                say("Какой эффект? Снег, дождь, ёлка, гроза, конфетти, фейерверк, звёзды, метеоры, сердечки, огонь, светлячки, пузыри, листопад, сакура или матрица")

            // настройки самого Джарвиса
            has("звуки", "звуков", "sfx") && (onVerb || offVerb) -> toggleSetting("sfx", !offVerb, "Звуки включены", "Звуки выключены")
            has("озвучк*") && (onVerb || offVerb) -> toggleSetting("tts", !offVerb, "Озвучка включена", "Озвучка выключена")
            has("замолчи", "помолчи", "молчи") -> {
                tts?.stop()
                speaking = false
                Prefs.setBool(ctx, "tts", false)
                say("Хорошо, молчу")
            }
            has("смотри", "следи", "наблюдай") && has("экран", "экраном", "экрану") ->
                toggleSetting("watch", !(has("не") || offVerb), "Слежу за экраном", "Больше не слежу за экраном")
            has("крик*", "кричу", "кричать") && has("реагируй", "реагировать", "реакци*") ->
                toggleSetting("scream", !(has("не") || offVerb), "Буду реагировать на крик", "Не буду реагировать на крик")

            // настройки телефона
            has("фонарик*", "фонарь", "вспышку") -> setTorch(!offVerb)
            has("блютуз*", "блютус*", "bluetooth") -> setBluetooth(!offVerb)
            norm.contains("вай фай") || norm.contains("wi fi") || has("вайфай", "wifi", "вифи") -> setWifi(!offVerb)
            tile != null -> viaQuickSettings(tile.third, !offVerb, tile.first)
            norm.contains("поворот") && (onVerb || offVerb) -> autoRotate(!offVerb)
            has("таймаут*") || norm.contains("тайм аут") -> screenTimeout(norm)
            has("громче", "погромче") -> volume(true)
            has("тише", "потише") -> volume(false)
            has("громкость*") && has("максимум", "максимальную", "полную") -> volumeMax()
            has("громкость*") && hasDigits -> setVolumePercent(norm)
            norm.contains("без звука") || has("беззвучный", "беззвучку") || (has("звук") && offVerb) -> mute()
            has("звук") && onVerb -> unmute()
            has("ярче", "поярче") -> brightness(60)
            has("темнее", "потемнее", "тусклее") -> brightness(-60)
            has("яркость*") && has("максимум", "максимальную") -> brightness(255, true)
            has("яркость*") && has("минимум", "минимальную") -> brightness(15, true)
            has("будильник*") -> alarm(norm)
            has("таймер*") -> timer(norm)
            has("найди", "поищи", "загугли", "погугли", "поиск") ->
                webSearch(after("найди", "поищи", "загугли", "погугли", "поиск")
                    .filter { it !in setOf("в", "интернете", "гугле", "пожалуйста") }.joinToString(" "))

            // музыка
            has("следующая", "следующую", "следующий", "дальше") && has("песня", "песню", "трек", "композиция", "композицию") ->
                media(KeyEvent.KEYCODE_MEDIA_NEXT)
            has("предыдущая", "предыдущую", "предыдущий") && has("песня", "песню", "трек", "композиция", "композицию") ->
                media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            has("пауза", "паузу") -> media(KeyEvent.KEYCODE_MEDIA_PAUSE)
            has("играй", "продолжи", "воспроизведи") || (onVerb && has("музыку", "музыка")) -> media(KeyEvent.KEYCODE_MEDIA_PLAY)

            // управление экраном (служба доступности)
            words == listOf("назад") || (has("вернись", "назад") && words.size <= 3) ->
                access { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); say("Назад") }
            has("домой") || norm.contains("главный экран") || norm.contains("на главную") || norm.contains("рабочий стол") ->
                access { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); say("Иду домой") }
            norm.contains("последние приложения") || has("недавние") ->
                access { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }
            has("закрой") && has("все") && has("приложения", "приложений") ->
                access { a -> a.closeAllApps { ok -> say(if (ok) "Закрыл все приложения" else "Не нашёл кнопку закрытия") } }
            has("закрой") -> access { a -> a.closeCurrentApp { say("Закрыл") } }
            has("скриншот*") || norm.contains("снимок экрана") ->
                access {
                    if (Build.VERSION.SDK_INT >= 28) {
                        it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                        say("Скриншот")
                    } else say("На этой версии Android скриншот сделать не получится")
                }
            has("заблокируй", "блокировка") ->
                access {
                    if (Build.VERSION.SDK_INT >= 28) it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    else say("На этой версии Android заблокировать не получится")
                }
            has("уведомления", "шторку", "шторка") ->
                access { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
            has("листай", "прокрути", "пролистай") -> access { it.scroll(!has("вверх")) }

            // приложения
            has("открой", "запусти", "зайди", "открыть") ->
                openApp(after("открой", "запусти", "зайди", "открыть")
                    .filter { it !in setOf("приложение", "в", "на", "мне") }.joinToString(" "))

            // информация
            norm.contains("который час") || norm.contains("сколько времени") || has("время", "времени") ->
                say("Сейчас " + SimpleDateFormat("H:mm", Locale("ru")).format(Date()))
            norm.contains("какой сегодня день") || has("число", "дату", "дата") ->
                say("Сегодня " + SimpleDateFormat("d MMMM, EEEE", Locale("ru")).format(Date()))
            has("заряд*", "батарея", "батарею", "батареи") -> battery()

            // разговор
            has("привет", "здравствуй", "здравствуйте", "хай") -> say("Привет! Чем могу помочь?", Emotion.HAPPY)
            norm.contains("как дела") -> say("Отлично, работаю без выходных. Чем помочь?", Emotion.HAPPY)
            norm.contains("кто ты") || norm.contains("как тебя зовут") ->
                say("Я " + persona().name + ", твой голосовой помощник. Пока в бета-версии, но уже многое умею")
            has("спасибо", "благодарю") -> say("Всегда пожалуйста", Emotion.HAPPY)
            has("анекдот*", "шутку", "шутка", "пошути") ->
                say(jokes[(System.currentTimeMillis() % jokes.size).toInt()], Emotion.HAPPY)
            has("помощь", "умеешь", "команды") -> say(
                "Я умею: звонить и отвечать на звонки, писать сообщения, менять настройки телефона, " +
                    "ставить будильник и таймер, искать в интернете, открывать и удалять приложения, " +
                    "управлять экраном, показывать эффекты и ходячего персонажа, менять голоса и режимы"
            )
            else -> say("Не понял команду. Скажи «помощь», и я расскажу, что умею")
        }
    }

    private fun toggleSetting(key: String, on: Boolean, msgOn: String, msgOff: String) {
        Prefs.setBool(ctx, key, on)
        if (key == "sfx" && !on) SoundFx.stopAmbient()
        say(if (on) msgOn else msgOff)
        if (on && key == "watch" && JarvisAccess.instance == null) {
            main.postDelayed({ say("Для этого включи службу доступности Джарвис в настройках") }, 2500)
        }
        if (on && key == "scream" && !VoiceService.running) {
            main.postDelayed({ say("Включи в приложении слушание в фоне, тогда я услышу крик") }, 2500)
        }
    }

    // ---------------------------------------------------------------- персонажи

    fun switchPersona(p: Persona) {
        if (!Unlock.isUnlocked(ctx, p)) {
            say("Персонаж " + p.name + " платный. Открой вкладку «Персонажи» и введи ключ", null, true)
            return
        }
        Prefs.setStr(ctx, "persona", p.id)
        slotOverride = null
        pitchAdj = 1f
        rateAdj = 1f
        if (EffectService.charOn) EffectService.showCharacter(ctx)
        say(p.greeting, Emotion.HAPPY, true)
    }

    private fun needOverlay(): Boolean {
        if (Settings.canDrawOverlays(ctx)) return false
        say("Разреши показ поверх других окон, потом повтори команду")
        startNew(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
        return true
    }

    fun showCharacter() {
        if (needOverlay()) return
        try {
            EffectService.showCharacter(ctx)
            say("Я здесь")
        } catch (e: Exception) { say("Не получилось показать персонажа") }
    }

    fun hideCharacter() {
        EffectService.stopCharacter(ctx)
        say("Ухожу")
    }

    private fun voiceCommand(words: List<String>) {
        fun has(vararg k: String) = words.any { it in k }
        when {
            has("робота", "робот") -> preset(0.55f, 0.9f, "робота")
            has("мышонка", "мыши", "писклявый", "тонкий", "детский", "ребенка") -> preset(1.8f, 1.1f, "мышонка")
            has("баса", "бас", "низкий") -> preset(0.45f, 0.85f, "баса")
            has("обычный", "нормальный", "стандартный") -> preset(1f, 1f, "обычный")
            else -> nextVoice()
        }
    }

    private fun preset(p: Float, r: Float, name: String) {
        pitchAdj = p
        rateAdj = r
        say("Голос $name включён")
    }

    private fun nextVoice() {
        if (ruVoices.size < 2) {
            say("Других русских голосов на телефоне нет. Установи ещё голоса в настройках синтеза речи Google, или скажи: голос робота, мышонка или баса")
            return
        }
        slotOverride = ((slotOverride ?: persona().voiceSlot) + 1) % ruVoices.size
        say("Привет, это новый голос")
    }

    // ---------------------------------------------------------------- звонки и сообщения

    @Suppress("MissingPermission")
    private fun answerCall() {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            say("Нужно разрешение на ответ на звонки. Нажми «Разрешения» в приложении"); return
        }
        try {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
            say("Беру трубку")
        } catch (e: Exception) { say("Не получилось ответить") }
    }

    @Suppress("MissingPermission")
    private fun endCall() {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            say("Нужно разрешение на звонки. Нажми «Разрешения» в приложении"); return
        }
        if (Build.VERSION.SDK_INT < 28) { say("На этой версии Android завершить звонок не получится"); return }
        try {
            val ok = (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
            say(if (ok) "Звонок завершён" else "Сейчас нет активного звонка")
        } catch (e: Exception) { say("Не получилось завершить звонок") }
    }

    private fun callPerson(rest: List<String>) {
        val q = rest.joinToString(" ").trim()
        if (q.isEmpty()) { say("Кому позвонить?"); return }
        val digits = q.filter { it.isDigit() || it == '+' }
        val (name, number) = if (digits.count { it.isDigit() } >= 5) Pair(digits, digits)
        else (findContact(q) ?: run {
            say(if (!granted(Manifest.permission.READ_CONTACTS)) "Нужен доступ к контактам. Нажми «Разрешения»" else "Не нашёл контакт $q")
            return
        })
        if (!granted(Manifest.permission.CALL_PHONE)) { say("Нужно разрешение на звонки. Нажми «Разрешения»"); return }
        say("Звоню $name")
        startNew(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
    }

    private fun nameMatches(name: String, q: String): Boolean {
        val a = name.lowercase(Locale("ru")).replace('ё', 'е')
        if (a == q || (q.length >= 3 && a.contains(q))) return true
        val an = a.split(' ')[0]
        val qn = q.split(' ')[0]
        if (abs(an.length - qn.length) > 1) return false
        val k = min(an.length, qn.length) - 1
        return k >= 3 && an.take(k) == qn.take(k)
    }

    private fun findContact(q: String): Pair<String, String>? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val qq = q.lowercase(Locale("ru")).replace('ё', 'е').trim()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (nameMatches(n, qq)) return Pair(n, num)
            }
        }
        return null
    }

    private fun sendMessage(words: List<String>) {
        if (words.any { it.startsWith("телеграм") }) {
            say("В телеграм писать пока не умею. Скажи: напиши в ватсап, или отправь смс"); return
        }
        val viaWa = words.any { it == "ватсап" || it == "вацап" || it == "whatsapp" || it == "вотсап" }
        val filler = setOf("смс", "сообщение", "в", "ватсап", "вацап", "whatsapp", "вотсап", "текст", "текстом", "что", "мне")
        val verbs = setOf("напиши", "отправь", "пошли")
        val rest = words.dropWhile { it !in verbs }.drop(1).dropWhile { it in filler }
        if (rest.isEmpty()) { say("Кому и что написать?"); return }
        val body = rest.drop(1).dropWhile { it == "что" || it == "текст" }.joinToString(" ")
        if (body.isEmpty()) { say("Что написать?"); return }
        val (name, number) = findContact(rest[0]) ?: run {
            say(if (!granted(Manifest.permission.READ_CONTACTS)) "Нужен доступ к контактам. Нажми «Разрешения»" else "Не нашёл контакт " + rest[0])
            return
        }
        if (viaWa) {
            var d = number.filter { it.isDigit() }
            if (!number.trim().startsWith("+") && d.startsWith("8") && d.length == 11) d = "7" + d.drop(1)
            val url = "https://wa.me/$d?text=" + Uri.encode(body)
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (e: Exception) {
                startNew(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            val a = JarvisAccess.instance
            if (a != null) {
                main.postDelayed({
                    val ok = a.clickByText(listOf("отправить", "send"))
                    say(if (ok) "Отправил $name" else "Открыл чат с $name, нажми отправить")
                }, 3500)
            } else say("Открыл чат с $name. Включи службу доступности, и я буду нажимать отправить сам")
        } else {
            if (!granted(Manifest.permission.SEND_SMS)) { say("Нужно разрешение на смс. Нажми «Разрешения»"); return }
            try {
                sendSms(number, body)
                say("Отправил смс контакту $name")
            } catch (e: Exception) { say("Не получилось отправить смс") }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendSms(number: String, text: String) {
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
    }

    // ---------------------------------------------------------------- настройки телефона

    private fun setTorch(on: Boolean) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) { say("Фонарика не нашёл"); return }
            cm.setTorchMode(id, on)
            say(if (on) "Фонарик включён" else "Фонарик выключен")
        } catch (e: Exception) { say("Не получилось управлять фонариком") }
    }

    private fun viaQuickSettings(keys: List<String>, on: Boolean, name: String) {
        access { a ->
            a.toggleTile(keys, on) { ok -> say(if (ok) "Готово" else "Не нашёл кнопку $name в шторке") }
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun setBluetooth(on: Boolean) {
        if (Build.VERSION.SDK_INT < 33) {
            try {
                if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                    say("Нужно разрешение для блютуз. Нажми «Разрешения»"); return
                }
                val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter != null) {
                    if (on) adapter.enable() else adapter.disable()
                    say(if (on) "Блютуз включён" else "Блютуз выключен")
                    return
                }
            } catch (e: Exception) {
            }
        }
        viaQuickSettings(listOf("bluetooth", "блютуз", "блютус"), on, "Bluetooth")
    }

    @Suppress("DEPRECATION")
    private fun setWifi(on: Boolean) {
        if (Build.VERSION.SDK_INT < 29) {
            try {
                (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = on
                say(if (on) "Вайфай включён" else "Вайфай выключен")
                return
            } catch (e: Exception) {
            }
        }
        viaQuickSettings(listOf("wi-fi", "wifi", "вай-фай", "вайфай", "интернет", "internet"), on, "Wi-Fi")
    }

    private fun audio() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volume(up: Boolean) {
        val am = audio()
        repeat(3) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                if (it == 2) AudioManager.FLAG_SHOW_UI else 0
            )
        }
        say(if (up) "Громче" else "Тише")
    }

    private fun volumeMax() {
        val am = audio()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
        say("Громкость на максимум")
    }

    private fun setVolumePercent(norm: String) {
        val pct = Regex("(\\d{1,3})").find(norm)?.groupValues?.get(1)?.toInt()?.coerceIn(0, 100) ?: return
        val stream = when {
            norm.contains("звонка") || norm.contains("рингтон") -> AudioManager.STREAM_RING
            norm.contains("будильник") -> AudioManager.STREAM_ALARM
            norm.contains("уведомлен") -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val am = audio()
        val max = am.getStreamMaxVolume(stream)
        try {
            am.setStreamVolume(stream, (max * pct / 100f).toInt(), AudioManager.FLAG_SHOW_UI)
            say("Громкость $pct процентов")
        } catch (e: Exception) { say("Не получилось изменить громкость") }
    }

    private fun mute() {
        val am = audio()
        try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (e: Exception) { }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
    }

    private fun unmute() {
        val am = audio()
        try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (e: Exception) { }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, AudioManager.FLAG_SHOW_UI)
        say("Звук включён")
    }

    private fun ensureWrite(): Boolean {
        if (Settings.System.canWrite(ctx)) return true
        say("Разреши изменение системных настроек, потом повтори команду")
        startNew(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        return false
    }

    private fun brightness(v: Int, absolute: Boolean = false) {
        if (!ensureWrite()) return
        val cr = ctx.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = (if (absolute) v else cur + v).coerceIn(10, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        say("Яркость изменена")
    }

    private fun autoRotate(on: Boolean) {
        if (!ensureWrite()) return
        Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
        say(if (on) "Автоповорот включён" else "Автоповорот выключен")
    }

    private fun parseSeconds(norm: String): Int {
        var sec = 0
        if (norm.contains("полчаса")) return 1800
        Regex("(\\d+)?\\s*(час|мин|сек)").findAll(norm).forEach { m ->
            val n = m.groupValues[1].ifEmpty { "1" }.toInt()
            sec += when (m.groupValues[2]) { "час" -> n * 3600; "мин" -> n * 60; else -> n }
        }
        return sec
    }

    private fun screenTimeout(norm: String) {
        val sec = parseSeconds(norm)
        if (sec <= 0) { say("На сколько секунд или минут поставить таймаут экрана?"); return }
        if (!ensureWrite()) return
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, sec * 1000)
        say("Экран будет гаснуть через $sec секунд")
    }

    private fun alarm(norm: String) {
        val m = Regex("(\\d{1,2})(?:[: ](\\d{2}))?").find(norm)
        if (m == null) { say("На какое время поставить будильник?"); return }
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (norm.contains("вечера") && h < 12) h += 12
        if (norm.contains("дня") && h in 1..5) h += 12
        if (norm.contains("ночи") && h == 12) h = 0
        if (h > 23 || min > 59) { say("Не понял время"); return }
        startNew(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Джарвис")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        say(String.format(Locale.US, "Будильник на %02d:%02d", h, min))
    }

    private fun timer(norm: String) {
        val sec = parseSeconds(norm)
        if (sec <= 0) { say("На сколько поставить таймер?"); return }
        startNew(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, sec)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        say("Таймер запущен")
    }

    private fun webSearch(q: String) {
        if (q.isBlank()) { say("Что найти?"); return }
        say("Ищу $q")
        startNew(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
    }

    private fun media(code: Int) {
        val am = audio()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun battery() {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        say("Заряд батареи " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " процентов")
    }

    // ---------------------------------------------------------------- приложения

    private fun findApp(spoken: String): ResolveInfo? {
        val s = spoken.trim()
        val keys = listOfNotNull(s, aliases[s], aliases.entries.firstOrNull { s.contains(it.key) }?.value)
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return apps.firstOrNull { ri ->
            val l = ri.loadLabel(pm).toString().lowercase(Locale("ru")).replace('ё', 'е')
            keys.any { l.contains(it) }
        }
    }

    private fun openApp(spoken: String) {
        val s = spoken.trim()
        if (s.isEmpty()) { say("Какое приложение открыть?"); return }
        if (s.startsWith("настройк")) { openSettingsScreen(s); return }
        val hit = findApp(s)
        val intent = hit?.let { ctx.packageManager.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (intent == null) { say("Не нашёл приложение $s"); return }
        say("Открываю $s")
        startNew(intent)
    }

    private fun openSettingsScreen(s: String) {
        val action = when {
            s.contains("вай") || s.contains("wi") -> Settings.ACTION_WIFI_SETTINGS
            s.contains("блютуз") || s.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            s.contains("звук") -> Settings.ACTION_SOUND_SETTINGS
            s.contains("экран") || s.contains("дисплей") -> Settings.ACTION_DISPLAY_SETTINGS
            s.contains("батаре") || s.contains("заряд") -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            s.contains("приложен") -> Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
            s.contains("доступ") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            s.contains("местополож") || s.contains("геолок") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            s.contains("памят") -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            s.contains("язык") -> Settings.ACTION_LOCALE_SETTINGS
            s.contains("дат") || s.contains("врем") -> Settings.ACTION_DATE_SETTINGS
            s.contains("сеть") || s.contains("сети") -> Settings.ACTION_WIRELESS_SETTINGS
            s.contains("vpn") -> Settings.ACTION_VPN_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        say("Открываю настройки")
        startNew(Intent(action))
    }

    private fun uninstallApp(name: String) {
        if (name.isEmpty()) { say("Какое приложение удалить?"); return }
        val hit = findApp(name)
        if (hit == null) { say("Не нашёл приложение $name"); return }
        val pkg = hit.activityInfo.packageName
        say("Открываю меню удаления. Подтверди сам, без тебя я ничего не удалю")
        startNew(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    // ---------------------------------------------------------------- эффекты

    fun startEffect(e: Effect) {
        if (!Prefs.bool(ctx, "fx_on", true)) {
            say("Эффекты выключены. Включи их на вкладке «Эффекты»")
            return
        }
        if (needOverlay()) return
        try {
            EffectService.showEffect(ctx, e)
            say("Включаю: " + e.label, Emotion.HAPPY)
        } catch (ex: Exception) { say("Не получилось включить эффект") }
    }

    fun stopEffect() {
        EffectService.stopEffect(ctx)
        say("Эффект убран")
    }
}
