package app.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Text to speech. Picks a Marathi, Hindi or English voice from the text itself. Main thread only. */
class Speaker(context: Context, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var state = 0 // 0 = starting, 1 = ready, 2 = failed
    private var counter = 0
    private val active = HashSet<String>()
    private val callbacks = HashMap<String, () -> Unit>()
    private val waiting = ArrayList<Triple<String, Boolean, (() -> Unit)?>>()

    /** Called with true when speech starts and false when everything queued has finished. */
    var onActive: ((Boolean) -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    state = 1
                    val engine = tts
                    engine?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            finish(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            finish(utteranceId)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            finish(utteranceId)
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            finish(utteranceId)
                        }
                    })
                } else {
                    state = 2
                }
                val pending = ArrayList(waiting)
                waiting.clear()
                for (item in pending) say(item.first, item.second, item.third)
            }
        }
    }

    fun say(text: String, flush: Boolean = false, onDone: (() -> Unit)? = null) {
        val clean = clean(text)
        if (!prefs.voiceReplies || clean.isEmpty() || state == 2) {
            onDone?.invoke()
            return
        }
        if (state == 0) {
            waiting.add(Triple(clean, flush, onDone))
            return
        }
        val engine = tts
        if (engine == null) {
            onDone?.invoke()
            return
        }
        chooseVoice(engine, clean)
        if (flush) {
            active.clear()
            callbacks.clear()
        }
        counter++
        val id = "u$counter"
        active.add(id)
        if (onDone != null) callbacks[id] = onDone
        if (active.size == 1) onActive?.invoke(true)
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (engine.speak(clean, mode, null, id) == TextToSpeech.ERROR) finish(id)
    }

    /** Stop talking now. Callbacks of dropped utterances still run so the caller can move on. */
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        val dropped = ArrayList(callbacks.values)
        val wasActive = active.isNotEmpty()
        active.clear()
        callbacks.clear()
        if (wasActive) onActive?.invoke(false)
        for (cb in dropped) cb()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
    }

    private fun finish(id: String?) {
        main.post {
            if (id != null && active.remove(id)) {
                val cb = callbacks.remove(id)
                if (active.isEmpty()) onActive?.invoke(false)
                cb?.invoke()
            }
        }
    }

    private fun chooseVoice(engine: TextToSpeech, text: String) {
        val devanagari = text.any { it in 'ऀ'..'ॿ' }
        val tags = if (devanagari) listOf("mr-IN", "hi-IN", "en-IN") else listOf("en-IN", "en-US")
        for (tag in tags) {
            val loc = Locale.forLanguageTag(tag)
            if (engine.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                engine.setLanguage(loc)
                return
            }
        }
    }

    private fun clean(t: String): String {
        var s = t.replace(Regex("[*_`#>~|]"), "").replace(Regex("\\s+"), " ").trim()
        if (s.length > 600) s = s.substring(0, 600)
        return s
    }
}
