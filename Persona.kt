package com.jarvis.app

import android.content.Context

enum class Emotion { IDLE, HAPPY, SURPRISED, SAD, SCARED }

/** Простое хранилище настроек. */
object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun bool(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }
    fun str(c: Context, k: String, d: String): String = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
}

class Persona(
    val id: String,
    val name: String,
    val tagline: String,
    val description: String,
    val paid: Boolean,
    val color: Int,
    val hair: Int,
    val pitch: Float,
    val rate: Float,
    val voiceSlot: Int,
    val words: List<String>,
    val greeting: String,
    val surprise: String,
    val sad: String,
    val scared: String,
    val style: (String) -> String
) {
    fun voiceInfo(): String = "тон " + pitch + " · скорость " + rate
}

object Personas {
    private fun low(t: String): String = t.replaceFirstChar { it.lowercase() }

    val all: List<Persona> = listOf(
        Persona(
            "jarvis", "JARVIS", "Спокойный и вежливый",
            "Классический помощник: чётко, по делу и с лёгкой иронией.",
            false, 0xFF00B8D4.toInt(), 0xFF263238.toInt(), 0.9f, 1.0f, 0,
            listOf("джарвис", "джарвиса", "jarvis"),
            "Слушаю вас.",
            "Вы открыли меню удаления. Надеюсь, вы знаете, что делаете.",
            "Приложение удалено. Мне будет его не хватать.",
            "Ого, как громко! Прошу вас, потише."
        ) { it },
        Persona(
            "nova", "NOVA", "Бодрая и весёлая",
            "Энергичная подруга: быстро говорит, радуется каждой команде.",
            false, 0xFFE040FB.toInt(), 0xFF7B1FA2.toInt(), 1.2f, 1.1f, 1,
            listOf("нова", "нову", "нове"),
            "Приветики! Я Нова, давай веселиться!",
            "Ой! Ты что, что-то удаляешь?!",
            "Ну вот, ещё одно приложение улетело. Грустно.",
            "Аааа! Ты чего так кричишь?!"
        ) { t -> t.trimEnd('.', '!', '?') + "!" },
        Persona(
            "professor", "PROFESSOR", "Мудрый и неторопливый",
            "Объясняет обстоятельно, говорит медленно и очень солидно.",
            false, 0xFF8D6E63.toInt(), 0xFFB0BEC5.toInt(), 0.85f, 0.9f, 2,
            listOf("профессор", "профессора", "профессору"),
            "Добрый день. Я профессор. Чем могу быть полезен?",
            "Любопытно. Вы собираетесь удалять приложение.",
            "Увы. Ещё одно приложение ушло в небытие.",
            "Тише, молодой человек. У меня чувствительный слух."
        ) { t -> "Итак. $t" },
        Persona(
            "zen", "ZEN", "Спокойный как море",
            "Говорит тихо и мягко. Помогает не нервничать.",
            false, 0xFF26A69A.toInt(), 0xFF5D4037.toInt(), 1.0f, 0.85f, 3,
            listOf("дзен", "зен"),
            "Дыши ровно. Я здесь, чтобы помочь.",
            "Отпускать нужно с лёгкостью. Но зачем удалять?",
            "Приложение ушло. Всё приходит и уходит.",
            "Дыши глубже. Не нужно кричать."
        ) { it },
        Persona(
            "kent", "KENT / BRO", "Свой парень, всегда говорит «брат»",
            "Расслабленный дружеский стиль: в каждой фразе «брат».",
            true, 0xFFFF7043.toInt(), 0xFF212121.toInt(), 0.8f, 1.05f, 4,
            listOf("кент", "кента", "брат", "брата", "бро", "братан"),
            "Здарова, брат! Я Кент, твой брат. Что надо, брат?",
            "Эй, брат, ты что удаляешь?! Осторожнее, брат!",
            "Эх, брат, удалил… Грустно, брат.",
            "Брат, не ори так, брат, я чуть не упал!"
        ) { t -> "Брат, " + low(t).trimEnd('.', '!', '?') + ", брат!" },
        Persona(
            "aether", "AETHER PRIME", "VIP: торжественный и точный",
            "Премиальный персонаж: докладывает чётко, как капитан корабля.",
            true, 0xFFFFD54F.toInt(), 0xFFFFC107.toInt(), 0.95f, 0.95f, 5,
            listOf("эфир", "эфира", "aether", "прайм"),
            "Aether Prime на связи. Все системы в норме.",
            "Обнаружено меню удаления. Подтвердите намерение.",
            "Приложение стёрто. Фиксирую потерю.",
            "Зафиксирован пик громкости. Рекомендую снизить уровень."
        ) { t -> "Докладываю: " + low(t) }
    )

    fun byId(id: String): Persona = all.firstOrNull { it.id == id } ?: all[0]
    fun match(words: List<String>): Persona? = all.firstOrNull { p -> words.any { it in p.words } }
}

/** Платные персонажи. Ключи активации беты можно поменять на свои. */
object Unlock {
    private val keys = mapOf("BRO-2026" to "kent", "VIP-2026" to "aether", "BETA-ALL" to "*")

    fun isUnlocked(c: Context, p: Persona): Boolean = !p.paid || Prefs.bool(c, "unlock_" + p.id, false)

    fun unlock(c: Context, id: String) { Prefs.setBool(c, "unlock_$id", true) }

    fun tryKey(c: Context, key: String): Boolean {
        val v = keys[key.trim().uppercase()] ?: return false
        if (v == "*") Personas.all.filter { it.paid }.forEach { unlock(c, it.id) } else unlock(c, v)
        return true
    }
}
