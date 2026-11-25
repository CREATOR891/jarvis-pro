package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Служба доступности: "руки" Джарвиса. Нажимает кнопки, свайпит, открывает шторку. */
class JarvisAccess : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccess? = null
    }

    private val h = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /** "Смотреть на экран": замечаем меню удаления приложений и сообщение об удалении. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val ctx = applicationContext
        if (!Prefs.bool(ctx, "watch", false)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val txt = (event.text?.joinToString(" ") ?: "").lowercase()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg.contains("packageinstaller") || txt.contains("удалить это приложение") ||
                    txt.contains("uninstall this app") || txt.contains("do you want to uninstall")
                ) {
                    Assistant.get(ctx).onUninstallMenu(false)
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (txt.contains("удален") || txt.contains("uninstalled") || txt.contains("deleted")) {
                    Assistant.get(ctx).onAppDeleted(false)
                }
            }
            else -> {
            }
        }
    }
    override fun onInterrupt() {}

    private fun roots(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { list.add(it) }
        try {
            windows?.forEach { w -> w.root?.let { list.add(it) } }
        } catch (e: Exception) {
        }
        return list
    }

    private fun find(n: AccessibilityNodeInfo?, words: List<String>): AccessibilityNodeInfo? {
        if (n == null) return null
        val t = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
        if (t.isNotBlank() && words.any { t.contains(it) }) return n
        for (i in 0 until n.childCount) {
            val r = find(n.getChild(i), words)
            if (r != null) return r
        }
        return null
    }

    private fun findAny(words: List<String>): AccessibilityNodeInfo? {
        for (r in roots()) {
            val n = find(r, words)
            if (n != null) return n
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
        }
        return false
    }

    fun clickByText(words: List<String>): Boolean {
        val n = findAny(words) ?: return false
        return click(n)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, durMs))
            .build()
        dispatchGesture(g, null, null)
    }

    fun scroll(down: Boolean) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val hh = dm.heightPixels.toFloat()
        if (down) swipe(x, hh * 0.7f, x, hh * 0.3f, 250) else swipe(x, hh * 0.3f, x, hh * 0.7f, 250)
    }

    private fun stateOf(n: AccessibilityNodeInfo): Boolean? {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isCheckable) return cur.isChecked
            val st = if (Build.VERSION.SDK_INT >= 30) cur.stateDescription?.toString() ?: "" else ""
            val l = (st + " " + (cur.contentDescription?.toString() ?: "")).lowercase()
            if (l.contains("выключ") || Regex("\\boff\\b").containsMatchIn(l)) return false
            if (l.contains("включ") || Regex("\\bon\\b").containsMatchIn(l)) return true
            if (cur.isClickable) break
            cur = cur.parent
        }
        return null
    }

    /** Открывает быстрые настройки, нажимает плитку (Bluetooth, Wi-Fi...) и закрывает шторку. */
    fun toggleTile(keys: List<String>, on: Boolean, done: (Boolean) -> Unit) {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        h.postDelayed({
            val node = findAny(keys)
            if (node == null) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                done(false)
            } else {
                val isOn = stateOf(node)
                if (isOn == null || isOn != on) click(node)
                h.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    done(true)
                }, 600)
            }
        }, 900)
    }

    fun closeCurrentApp(done: () -> Unit) {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        h.postDelayed({
            val dm = resources.displayMetrics
            swipe(dm.widthPixels / 2f, dm.heightPixels * 0.55f, dm.widthPixels / 2f, dm.heightPixels * 0.05f, 220)
            h.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                done()
            }, 700)
        }, 900)
    }

    fun closeAllApps(done: (Boolean) -> Unit) {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        h.postDelayed({
            val ok = clickByText(listOf("закрыть все", "очистить все", "clear all", "close all"))
            h.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                done(ok)
            }, 600)
        }, 900)
    }
}
