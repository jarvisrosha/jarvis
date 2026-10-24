package app.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ActionError(message: String) : Exception(message)

/** Everything Jarvis can do on the phone. Claude calls these as tools. */
class Actions(private val context: Context, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private var appCache: List<Pair<String, String>>? = null
    private var appCacheTime = 0L

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ACTIONS, "Jarvis actions", NotificationManager.IMPORTANCE_HIGH))
    }

    // ---------------------------------------------------------------- tool list for Claude

    fun toolSpecs(): JSONArray {
        val t = JSONArray()
        t.put(tool("open_app", "Launch an installed app by its launcher name, for example YouTube, WhatsApp, Camera, Photos, Snapseed, CapCut. Returns OK or an error listing installed apps.",
            props("name" to str("App name in English as shown on the home screen")), listOf("name")))
        t.put(tool("youtube_search", "Open YouTube search results for a query (song, video, channel). It cannot auto-play, the user taps a result.",
            props("query" to str("What to search for")), listOf("query")))
        t.put(tool("web_search", "Search Google in the browser.",
            props("query" to str("Search text")), listOf("query")))
        t.put(tool("open_url", "Open an http or https link in the browser.",
            props("url" to str("Full URL")), listOf("url")))
        t.put(tool("call", "Call a contact by name or a phone number. Uses the saved contacts.",
            props("contact" to str("Contact name as the user said it, or a phone number")), listOf("contact")))
        t.put(tool("whatsapp", "Open a WhatsApp chat with a contact or number and prefill a message. The user still has to tap send.",
            props("contact" to str("Contact name or number"), "message" to str("Message text, optional")), listOf("contact")))
        t.put(tool("sms", "Open the SMS app with a recipient and prefilled text. The user still has to tap send.",
            props("contact" to str("Contact name or number"), "message" to str("Message text, optional")), listOf("contact")))
        t.put(tool("email", "Open the email app with a draft.",
            props("to" to str("Email address, optional"), "subject" to str("Subject, optional"), "body" to str("Body, optional"))))
        t.put(tool("set_alarm", "Set an alarm in the Clock app at a time of day (24 hour clock).",
            props("hour" to num("0 to 23"), "minute" to num("0 to 59"), "label" to str("Optional label")), listOf("hour", "minute")))
        t.put(tool("set_timer", "Start a countdown timer.",
            props("seconds" to num("Duration in seconds"), "label" to str("Optional label")), listOf("seconds")))
        t.put(tool("navigate", "Start turn by turn navigation to a place in Google Maps.",
            props("destination" to str("Place name or address")), listOf("destination")))
        t.put(tool("flashlight", "Turn the flashlight on or off.",
            props("on" to bool("true for on, false for off")), listOf("on")))
        t.put(tool("media", "Control whatever media is playing: play, pause, next, previous, stop, or toggle.",
            props("command" to enumStr("Media command", "play", "pause", "next", "previous", "stop", "toggle")), listOf("command")))
        t.put(tool("volume", "Change media volume. action is up, down, max, mute or set (then give level 0 to 100). Returns the new percentage.",
            props("action" to enumStr("Volume action", "up", "down", "max", "mute", "set"), "level" to num("Percent, only for set")), listOf("action")))
        t.put(tool("battery", "Get the battery percentage and whether it is charging."))
        t.put(tool("add_note", "Save a short note in Jarvis's own notebook.",
            props("text" to str("Note text")), listOf("text")))
        t.put(tool("read_notes", "Read back the saved notes."))
        t.put(tool("open_settings", "Open a system settings page.",
            props("page" to enumStr("Settings page", "wifi", "bluetooth", "display", "sound", "battery", "location", "airplane", "apps", "main")), listOf("page")))
        t.put(tool("phone_global", "Press a system button: back, home, recents, notifications, quick_settings, lock_screen, screenshot, power_menu. Needs the Jarvis accessibility service.",
            props("action" to enumStr("System action", "back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot", "power_menu")), listOf("action")))
        t.put(tool("read_screen", "Read the visible text on the current screen, with [tap], [edit], [scroll] markers. The text is sent to you only for this request. Needs the Jarvis accessibility service."))
        t.put(tool("tap_text", "Tap the button or item whose visible text or description matches. Sensitive labels (send, pay, delete, buy...) are refused unless confirmed is true, and confirmed may only be true after the user said yes.",
            props("text" to str("Label to tap"), "confirmed" to bool("true only after the user agreed out loud")), listOf("text")))
        t.put(tool("scroll", "Scroll the current screen up or down.",
            props("direction" to enumStr("Direction", "down", "up")), listOf("direction")))
        t.put(tool("type_text", "Type into the focused text field, or the first text field on screen. Replaces the text unless append is true. submit presses the keyboard action key.",
            props("text" to str("Text to type"), "append" to bool("Add to existing text"), "submit" to bool("Press enter after typing")), listOf("text")))
        return t
    }

    /** How long to wait after a tool so the next screen has appeared before Claude looks again. */
    fun settleMs(tool: String): Long = when (tool) {
        "open_app", "youtube_search", "web_search", "open_url", "navigate", "call", "whatsapp", "sms", "email",
        "open_settings", "set_alarm", "set_timer" -> 1300L
        "tap_text", "scroll", "phone_global", "type_text" -> 800L
        else -> 0L
    }

    /** Run one tool. Safe to call from a worker thread. Always returns text, never throws. */
    fun run(name: String, input: JSONObject): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return runNow(name, input)
        val box = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        main.post {
            box[0] = runNow(name, input)
            latch.countDown()
        }
        if (!latch.await(10, TimeUnit.SECONDS)) return "ERROR: the phone did not answer in time"
        return box[0] ?: "ERROR: no result"
    }

    private fun runNow(name: String, input: JSONObject): String {
        Hub.log("ACTION", name + if (input.length() > 0) " " + input.toString() else "")
        return try {
            dispatch(name, input)
        } catch (e: ActionError) {
            "ERROR: " + (e.message ?: "failed")
        } catch (e: Exception) {
            "ERROR: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun dispatch(name: String, a: JSONObject): String = when (name) {
        "open_app" -> openApp(a.optString("name"))
        "youtube_search" -> youtube(a.optString("query"))
        "web_search" -> webSearch(a.optString("query"))
        "open_url" -> openUrl(a.optString("url"))
        "call" -> call(a.optString("contact"))
        "whatsapp" -> whatsapp(a.optString("contact"), a.optString("message"))
        "sms" -> sms(a.optString("contact"), a.optString("message"))
        "email" -> email(a.optString("to"), a.optString("subject"), a.optString("body"))
        "set_alarm" -> setAlarm(a.optInt("hour", -1), a.optInt("minute", 0), a.optString("label"))
        "set_timer" -> setTimer(a.optInt("seconds", 0), a.optString("label"))
        "navigate" -> navigate(a.optString("destination"))
        "flashlight" -> flashlight(a.optBoolean("on", true))
        "media" -> media(a.optString("command"))
        "volume" -> volume(a.optString("action"), a.optInt("level", -1))
        "battery" -> battery()
        "add_note" -> addNote(a.optString("text"))
        "read_notes" -> readNotes()
        "open_settings" -> openSettings(a.optString("page"))
        "phone_global" -> access { it.global(a.optString("action")) }
        "read_screen" -> access { it.readScreen() }
        "tap_text" -> access { it.tap(a.optString("text"), a.optBoolean("confirmed", false)) }
        "scroll" -> access { it.scroll(a.optString("direction")) }
        "type_text" -> access { it.typeText(a.optString("text"), a.optBoolean("append", false), a.optBoolean("submit", false)) }
        else -> "ERROR: unknown tool '$name'"
    }

    // ---------------------------------------------------------------- apps and links

    fun apps(): List<Pair<String, String>> {
        val cached = appCache
        if (cached != null && System.currentTimeMillis() - appCacheTime < 10 * 60 * 1000L) return cached
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(probe, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        appCache = list
        appCacheTime = System.currentTimeMillis()
        return list
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun openApp(name: String): String {
        val q = norm(name)
        if (q.isEmpty()) throw ActionError("no app name given")
        val all = apps()
        val hit = all.firstOrNull { norm(it.first) == q }
            ?: all.filter { norm(it.first).startsWith(q) }.minByOrNull { it.first.length }
            ?: all.filter { norm(it.first).contains(q) }.minByOrNull { it.first.length }
            ?: all.filter { norm(it.first).length >= 4 && q.contains(norm(it.first)) }.maxByOrNull { it.first.length }
            ?: throw ActionError("no installed app named '$name'. Installed apps: " + all.take(80).joinToString { it.first })
        val intent = context.packageManager.getLaunchIntentForPackage(hit.second)
            ?: throw ActionError("cannot launch ${hit.first}")
        return launch(intent, "opened ${hit.first}")
    }

    private fun youtube(query: String): String {
        if (query.isBlank()) throw ActionError("empty query")
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        return viewPreferring(uri, listOf("com.google.android.youtube"), "YouTube search for '$query'")
    }

    private fun webSearch(query: String): String {
        if (query.isBlank()) throw ActionError("empty query")
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
        return launch(Intent(Intent.ACTION_VIEW, uri), "Google search for '$query'")
    }

    private fun openUrl(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) throw ActionError("empty url")
        if (!u.contains("://")) u = "https://$u"
        val uri = Uri.parse(u)
        if (uri.scheme != "http" && uri.scheme != "https") throw ActionError("only http and https links are allowed")
        return launch(Intent(Intent.ACTION_VIEW, uri), "opened link")
    }

    private fun navigate(dest: String): String {
        if (dest.isBlank()) throw ActionError("empty destination")
        val nav = Uri.parse("google.navigation:q=" + Uri.encode(dest))
        val i = Intent(Intent.ACTION_VIEW, nav).setPackage("com.google.android.apps.maps")
        if (start(i) == null) return done("navigating to $dest", i)
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))), "map for $dest")
    }

    private fun openSettings(page: String): String {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return launch(Intent(action), "settings: $page")
    }

    // ---------------------------------------------------------------- people

    private class Match(val name: String, val number: String)

    private fun findContact(query: String): Match {
        val q = query.trim()
        if (q.isEmpty()) throw ActionError("no contact given")
        if (q.all { it.isDigit() || it in "+ -()" } && q.count { it.isDigit() } >= 5) {
            return Match(q, q.filter { it.isDigit() || it == '+' })
        }
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw ActionError("contacts permission is not granted. Tell the user to open the Jarvis app, tap Setup, then Contacts and Call permission.")
        }
        val nameCol = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        val numCol = ContactsContract.CommonDataKinds.Phone.NUMBER
        var best: Match? = null
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(nameCol, numCol),
            "$nameCol LIKE ?",
            arrayOf("%$q%"),
            null
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val cur = best
                val better = cur == null ||
                    (name.equals(q, true) && !cur.name.equals(q, true)) ||
                    (!cur.name.equals(q, true) && name.length < cur.name.length)
                if (better) best = Match(name, num)
            }
        }
        return best ?: throw ActionError("no contact matching '$q'. Retry with the name in the other script (Devanagari or English).")
    }

    private fun call(contact: String): String {
        val m = findContact(contact)
        val canCall = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", m.number, null))
        val what = if (canCall) "calling ${m.name}" else "dialer opened for ${m.name}, the user must press call"
        return launch(i, what)
    }

    private fun whatsappNumber(n: String): String {
        var d = n.filter { it.isDigit() }
        if (n.trim().startsWith("+")) return d
        if (d.startsWith("00")) return d.substring(2)
        if (d.length == 11 && d.startsWith("0")) d = d.substring(1)
        if (d.length == 10) d = "91$d"
        return d
    }

    private fun whatsapp(contact: String, message: String): String {
        val m = findContact(contact)
        var url = "https://wa.me/" + whatsappNumber(m.number)
        if (message.isNotBlank()) url += "?text=" + Uri.encode(message)
        return viewPreferring(Uri.parse(url), listOf("com.whatsapp", "com.whatsapp.w4b"), "WhatsApp chat with ${m.name}, the user must tap send")
    }

    private fun sms(contact: String, message: String): String {
        val m = findContact(contact)
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + m.number))
        if (message.isNotBlank()) i.putExtra("sms_body", message)
        return launch(i, "message to ${m.name}, the user must tap send")
    }

    private fun email(to: String, subject: String, body: String): String {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        if (to.isNotBlank()) i.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        if (subject.isNotBlank()) i.putExtra(Intent.EXTRA_SUBJECT, subject)
        if (body.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, body)
        return launch(i, "email draft")
    }

    // ---------------------------------------------------------------- time

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23 || minute !in 0..59) throw ActionError("invalid time")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return launch(i, "alarm at %02d:%02d".format(hour, minute))
    }

    private fun setTimer(seconds: Int, label: String): String {
        if (seconds < 1 || seconds > 86400) throw ActionError("seconds must be between 1 and 86400")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        if (start(i) == null) return done("timer for $seconds seconds", i)
        // No Clock app can take it: count down inside Jarvis instead.
        main.postDelayed({
            val msg = if (label.isBlank()) "वेळ संपली." else "वेळ संपली: $label"
            Hub.log("JARVIS", msg)
            Core.speaker.say(msg)
        }, seconds * 1000L)
        return "OK: in-app timer for $seconds seconds (works only while Jarvis is running)"
    }

    // ---------------------------------------------------------------- device

    private fun flashlight(on: Boolean): String {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw ActionError("this phone has no flashlight")
        cm.setTorchMode(id, on)
        return "OK: flashlight " + if (on) "on" else "off"
    }

    private fun media(command: String): String {
        val code = when (command) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "OK: media $command"
    }

    private fun volume(action: String, level: Int): String {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = maxOf(1, max / 5)
        val target = when (action) {
            "up" -> minOf(max, cur + step)
            "down" -> maxOf(0, cur - step)
            "max" -> max
            "mute" -> 0
            "set" -> {
                if (level !in 0..100) throw ActionError("level must be 0 to 100")
                Math.round(level / 100f * max)
            }
            else -> throw ActionError("unknown volume action '$action'")
        }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "OK: media volume is now ${Math.round(target * 100f / max)} percent"
    }

    private fun battery(): String {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw ActionError("battery information is not available")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "OK: battery ${Math.round(level * 100f / scale)} percent, " + if (charging) "charging" else "not charging"
    }

    // ---------------------------------------------------------------- notes

    private fun addNote(text: String): String {
        if (text.isBlank()) throw ActionError("empty note")
        val arr = JSONArray(prefs.notes)
        arr.put(text.trim())
        prefs.notes = arr.toString()
        return "OK: note saved (${arr.length()} notes in total)"
    }

    private fun readNotes(): String {
        val arr = JSONArray(prefs.notes)
        if (arr.length() == 0) return "OK: there are no notes yet"
        val sb = StringBuilder("OK: ")
        for (i in 0 until arr.length()) sb.append(i + 1).append(". ").append(arr.getString(i)).append(' ')
        return sb.toString().trim()
    }

    // ---------------------------------------------------------------- accessibility

    private fun access(block: (JarvisAccess) -> String): String {
        val svc = JarvisAccess.instance
        if (svc == null) {
            start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return "ERROR: the Jarvis accessibility service is switched off. I opened Accessibility settings. Tell the user to switch on Jarvis there and ask again."
        }
        return block(svc)
    }

    // ---------------------------------------------------------------- launching

    /** Starts an activity. Returns an error text, or null when it was started. */
    private fun start(intent: Intent): String? {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "no app on this phone can do that"
        } catch (e: SecurityException) {
            "not allowed: " + (e.message ?: "")
        }
    }

    private fun done(what: String, intent: Intent): String {
        var note = ""
        if (!Hub.appVisible && !(Hub.overlayAttached && Settings.canDrawOverlays(context))) {
            notifyTap(what, intent)
            note = ". Android may block opening apps from the background, so the user may need to tap the Jarvis notification"
        }
        return "OK: $what$note"
    }

    private fun launch(intent: Intent, what: String): String {
        val err = start(intent)
        if (err != null) throw ActionError(err)
        return done(what, intent)
    }

    private fun viewPreferring(uri: Uri, packages: List<String>, what: String): String {
        for (p in packages) {
            val i = Intent(Intent.ACTION_VIEW, uri).setPackage(p)
            if (start(i) == null) return done(what, i)
        }
        return launch(Intent(Intent.ACTION_VIEW, uri), what)
    }

    private fun notifyTap(what: String, intent: Intent) {
        try {
            val pi = PendingIntent.getActivity(
                context,
                (System.currentTimeMillis() and 0xFFFFFF).toInt(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(context, CH_ACTIONS)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Jarvis")
                .setContentText("टॅप करा: $what")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(20000)
                .build()
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.notify((System.currentTimeMillis() % 100000).toInt() + 100, n)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- schema helpers

    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", props)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", desc).put("input_schema", schema)
    }

    private fun props(vararg p: Pair<String, JSONObject>): JSONObject {
        val o = JSONObject()
        for ((k, v) in p) o.put(k, v)
        return o
    }

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun enumStr(desc: String, vararg values: String) =
        JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values.toList()))

    companion object {
        const val CH_ACTIONS = "jarvis_actions"
    }
}
