package app.jarvis

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
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), Hub.Listener {
    private lateinit var logBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var core: CoreView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var input: EditText

    private val bg = Color.parseColor("#040F17")
    private val cyan = Color.parseColor("#4CC9F0")
    private val dim = Color.parseColor("#7FA5B6")
    private val amber = Color.parseColor("#FFB040")
    private val red = Color.parseColor("#FF5E6C")
    private val ink = Color.parseColor("#D6EEF8")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        buildUi()
        val need = ArrayList<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        if (Hub.entries.isEmpty()) {
            Hub.log("JARVIS", "नमस्कार. आधी सेटिंग्जमध्ये API key टाका, मग ‘चालू करा’ दाबा आणि ‘Hey Jarvis’ म्हणा.")
        }
    }

    override fun onStart() {
        super.onStart()
        Hub.appVisible = true
        Hub.add(this)
        logBox.removeAllViews()
        for (e in ArrayList(Hub.entries)) addEntry(e)
        onState(Hub.state)
    }

    override fun onStop() {
        Hub.appVisible = false
        Hub.remove(this)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && !has(Manifest.permission.RECORD_AUDIO)) {
            Hub.log("SYSTEM", "माइक परवानगी शिवाय Jarvis ऐकू शकत नाही. तुम्ही टाइप करून आदेश देऊ शकता.")
        }
    }

    // ---- Hub.Listener

    override fun onEntry(entry: Hub.Entry) {
        addEntry(entry)
    }

    override fun onState(state: Hub.State) {
        core.state = state
        status.text = when (state) {
            Hub.State.OFF -> "OFFLINE"
            Hub.State.LISTENING -> "LISTENING"
            Hub.State.AWAKE -> "AWAKE · बोला"
            Hub.State.THINKING -> "PROCESSING"
            Hub.State.SPEAKING -> "SPEAKING"
        }
        toggle.text = if (Hub.serviceRunning) "■  Jarvis बंद करा" else "▶  Jarvis चालू करा"
    }

    // ---- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "JARVIS"
            setTextColor(cyan)
            textSize = 22f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.3f
        }
        status = TextView(this).apply {
            setTextColor(dim)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
        }
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(status)
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        core = CoreView(this)
        root.addView(core, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))

        scroll = ScrollView(this)
        logBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(logBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = Button(this).apply {
            isAllCaps = false
            setOnClickListener { toggleService() }
        }
        styled(toggle, cyan)
        val setup = Button(this).apply {
            text = "सेटअप"
            isAllCaps = false
            setOnClickListener { showSetup() }
        }
        styled(setup, amber)
        val settings = Button(this).apply {
            text = "सेटिंग्ज"
            isAllCaps = false
            setOnClickListener { showSettings() }
        }
        styled(settings, dim)
        buttons.addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        buttons.addView(setup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(settings, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply {
            hint = "टाइप करून आदेश द्या…"
            setHintTextColor(dim)
            setTextColor(ink)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTyped()
                    true
                } else {
                    false
                }
            }
        }
        val send = Button(this).apply {
            text = "SEND"
            setOnClickListener { sendTyped() }
        }
        styled(send, cyan)
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun styled(b: Button, color: Int) {
        b.setTextColor(color)
        b.background = GradientDrawable().apply {
            setStroke(dp(1), color)
            setColor(Color.TRANSPARENT)
        }
    }

    private fun addEntry(e: Hub.Entry) {
        val color = when (e.who) {
            "JARVIS" -> cyan
            "YOU" -> dim
            "ACTION" -> amber
            else -> red
        }
        val sb = SpannableStringBuilder(e.who + "  ")
        sb.setSpan(ForegroundColorSpan(color), 0, e.who.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(e.text)
        val tv = TextView(this).apply {
            text = sb
            textSize = 15f
            setTextColor(ink)
            setPadding(0, dp(6), 0, dp(6))
        }
        logBox.addView(tv)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- actions

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun toggleService() {
        if (Hub.serviceRunning) {
            startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
            return
        }
        if (Core.prefs.apiKey.isEmpty()) {
            Hub.log("SYSTEM", "आधी सेटिंग्जमध्ये Anthropic API key टाका.")
            showSettings()
            return
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startForegroundService(Intent(this, JarvisService::class.java))
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        if (Core.prefs.apiKey.isEmpty()) {
            Hub.log("SYSTEM", "आधी सेटिंग्जमध्ये Anthropic API key टाका.")
            showSettings()
            return
        }
        input.setText("")
        Core.submit(t, false)
    }

    private fun showSetup() {
        val a11yOn = JarvisAccess.instance != null
        val contactsOn = has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.CALL_PHONE)
        val overlayOn = Settings.canDrawOverlays(this)
        val pm = getSystemService(PowerManager::class.java)
        val batteryOn = pm.isIgnoringBatteryOptimizations(packageName)
        fun mark(ok: Boolean) = if (ok) "✔  " else "✘  "
        val items = arrayOf(
            mark(contactsOn) + "संपर्क आणि कॉल परवानगी (नावाने कॉल/WhatsApp)",
            mark(overlayOn) + "इतर ॲप्सवर दाखवा (बॅकग्राउंडमधून ॲप उघडण्यासाठी)",
            mark(batteryOn) + "बॅटरी ऑप्टिमायझेशन बंद (Jarvis बंद पडू नये म्हणून)",
            mark(a11yOn) + "Accessibility सेवा (स्क्रीन वाचणे, टॅप, स्क्रोल, टाइप)"
        )
        AlertDialog.Builder(this)
            .setTitle("सेटअप")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), 2)
                    1 -> openSettingsPage(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    2 -> openSettingsPage(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    3 -> openSettingsPage(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton("बंद", null)
            .show()
    }

    private fun openSettingsPage(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            Hub.log("SYSTEM", "ही सेटिंग्ज उघडता आली नाही. फोनच्या सेटिंग्जमधून हाताने चालू करा.")
        }
    }

    private fun showSettings() {
        val p = Core.prefs
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(dim)
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }

        val key = EditText(this).apply {
            hint = "sk-ant-…"
            setText(p.apiKey)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val model = EditText(this).apply {
            setText(p.model)
            setSingleLine()
        }
        val langs = arrayOf("mr-IN", "hi-IN", "en-IN")
        val spin = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("मराठी", "हिंदी", "English (India)")
            )
            setSelection(maxOf(0, langs.indexOf(p.lang)))
        }
        val echo = Switch(this).apply {
            text = "मी बोललेले परत ऐकव (echo)"
            isChecked = p.echo
        }
        val voice = Switch(this).apply {
            text = "उत्तर मोठ्याने वाचा"
            isChecked = p.voiceReplies
        }
        val follow = Switch(this).apply {
            text = "उत्तरानंतर पुढचा आदेश (वेकवर्ड शिवाय)"
            isChecked = p.followUp
        }

        box.addView(label("Anthropic API key"))
        box.addView(key)
        box.addView(label("मॉडेल"))
        box.addView(model)
        box.addView(label("बोलण्याची भाषा"))
        box.addView(spin)
        box.addView(echo)
        box.addView(voice)
        box.addView(follow)

        AlertDialog.Builder(this)
            .setTitle("सेटिंग्ज")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("सेव्ह") { _, _ ->
                p.apiKey = key.text.toString()
                p.model = model.text.toString()
                p.lang = langs[spin.selectedItemPosition.coerceIn(0, langs.size - 1)]
                p.echo = echo.isChecked
                p.voiceReplies = voice.isChecked
                p.followUp = follow.isChecked
                Hub.log("SYSTEM", "सेटिंग्ज सेव्ह झाल्या.")
            }
            .setNeutralButton("संवाद पुसा") { _, _ ->
                Core.brain.reset()
                Hub.log("SYSTEM", "आधीचा संवाद विसरलो.")
            }
            .setNegativeButton("रद्द", null)
            .show()
    }
}
