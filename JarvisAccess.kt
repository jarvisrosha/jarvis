package app.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lets Jarvis press Back/Home, read the visible screen text, tap a button by its label,
 * scroll and type. It does nothing on its own: it only acts when Claude calls a screen tool
 * after a spoken or typed request.
 */
class JarvisAccess : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Hub.log("SYSTEM", "Accessibility सेवा चालू झाली.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun global(action: String): String {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_menu" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return "ERROR: needs Android 9 or newer"
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return "ERROR: needs Android 9 or newer"
            else -> return "ERROR: unknown action '$action'"
        }
        return if (performGlobalAction(code)) "OK: $action" else "ERROR: the phone refused '$action'"
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "ERROR: no active window (the screen may be locked)"
        val out = StringBuilder()
        var count = 0

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count >= 80 || out.length > 3500) return
            if (n.isVisibleToUser) {
                val label = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (label.isNotEmpty() || n.isEditable) {
                    out.append(if (label.isEmpty()) "(text field)" else label.take(120))
                    if (n.isClickable) out.append(" [tap]")
                    if (n.isEditable) out.append(" [edit]")
                    if (n.isScrollable) out.append(" [scroll]")
                    out.append('\n')
                    count++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }

        walk(root)
        return "App: ${root.packageName}\n$out"
    }

    fun tap(text: String, confirmed: Boolean): String {
        val root = rootInActiveWindow ?: return "ERROR: no active window"
        if (text.isBlank()) return "ERROR: empty text"
        if (!confirmed && isRisky(text)) return riskMessage(text)
        val node = find(root, text)
            ?: return "ERROR: nothing on screen matches '$text'. Call read_screen to see what is visible."
        val label = (node.text ?: node.contentDescription)?.toString().orEmpty()
        if (!confirmed && isRisky(label)) return riskMessage(label)

        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && !target.isClickable && hops < 6) {
            target = target.parent
            hops++
        }
        val clickable = target ?: node
        return if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "OK: tapped '$label'" else "ERROR: could not tap '$label'"
    }

    fun scroll(direction: String): String {
        val root = rootInActiveWindow ?: return "ERROR: no active window"
        val node = firstWhere(root) { it.isScrollable } ?: return "ERROR: nothing scrollable on screen"
        val action = if (direction == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        return if (node.performAction(action)) "OK: scrolled $direction" else "ERROR: cannot scroll $direction any further"
    }

    fun typeText(text: String, append: Boolean, submit: Boolean): String {
        val root = rootInActiveWindow ?: return "ERROR: no active window"
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: firstWhere(root) { it.isEditable }
            ?: return "ERROR: no text field on screen"
        val base = if (append) node.text?.toString().orEmpty() else ""
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + text)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "ERROR: could not type into the field"
        if (submit && Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return "OK: typed"
    }

    private fun riskMessage(label: String) =
        "ERROR: '$label' looks like a sensitive button (send, pay, delete, buy...). Ask the user to say yes out loud, then call tap_text again with confirmed=true."

    private fun find(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val q = query.trim().lowercase()
        var exact: AccessibilityNodeInfo? = null
        var partial: AccessibilityNodeInfo? = null

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || exact != null) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase().orEmpty()
            if (t.isNotEmpty() && n.isVisibleToUser) {
                if (t == q) {
                    exact = n
                } else if (partial == null && t.contains(q)) {
                    partial = n
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }

        walk(root)
        return exact ?: partial
    }

    private fun firstWhere(root: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || hit != null) return
            if (n.isVisibleToUser && test(n)) {
                hit = n
                return
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }

        walk(root)
        return hit
    }

    companion object {
        @Volatile
        var instance: JarvisAccess? = null

        private val RISKY = listOf(
            "pay", "send", "transfer", "buy", "purchase", "order", "delete", "uninstall", "erase", "reset",
            "format", "subscribe", "डिलीट", "हटवा", "पैसे", "पेमेंट", "खरेदी", "पाठवा"
        )

        fun isRisky(label: String): Boolean {
            val l = label.lowercase()
            return RISKY.any { l.contains(it) }
        }
    }
}
