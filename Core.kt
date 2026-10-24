package app.jarvis

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** One place that owns the speaker, the brain and the phone actions. Used by the service and the screen. */
object Core {
    lateinit var prefs: Prefs
        private set
    lateinit var speaker: Speaker
        private set
    lateinit var brain: Brain
        private set
    lateinit var actions: Actions
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var ready = false
    private var turn = 0

    /** True from the moment a command is accepted until its reply has been spoken. */
    @Volatile
    var busy = false
        private set

    /** Set by the service: speech started (true) or everything queued finished (false). */
    var onSpeechActive: ((Boolean) -> Unit)? = null

    /** Set by the service: one command has been fully handled. */
    var onTurnDone: (() -> Unit)? = null

    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        prefs = Prefs(app)
        speaker = Speaker(app, prefs)
        actions = Actions(app, prefs)
        brain = Brain(prefs, actions)
        speaker.onActive = { active ->
            if (active) {
                Hub.set(Hub.State.SPEAKING)
            } else if (!busy) {
                Hub.set(if (Hub.serviceRunning) Hub.State.LISTENING else Hub.State.OFF)
            }
            onSpeechActive?.invoke(active)
        }
        ready = true
    }

    /** Handle one command, typed or spoken. Must be called on the main thread. */
    fun submit(text: String, fromVoice: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        Hub.log("YOU", t)
        if (isStop(t)) {
            turn++
            speaker.stop()
            Hub.log("JARVIS", "ठीक आहे.")
            finishTurn()
            return
        }
        if (busy) {
            Hub.log("SYSTEM", "मी आधीच एका कामात आहे. थांबवायचे असेल तर ‘थांब’ म्हणा.")
            return
        }
        busy = true
        turn++
        val myTurn = turn
        Hub.set(Hub.State.THINKING)
        if (fromVoice && prefs.echo) speaker.say(echoPhrase(t), flush = true)
        worker.execute {
            val reply: String = try {
                brain.ask(t)
            } catch (e: BrainException) {
                e.userMessage
            } catch (e: Exception) {
                "काहीतरी चुकले: " + (e.message ?: e.javaClass.simpleName)
            }
            main.post {
                if (myTurn == turn) {
                    Hub.log("JARVIS", reply)
                    speaker.say(reply, flush = false) { finishTurn() }
                }
            }
        }
    }

    private fun finishTurn() {
        busy = false
        Hub.set(if (Hub.serviceRunning) Hub.State.LISTENING else Hub.State.OFF)
        onTurnDone?.invoke()
    }

    private fun echoPhrase(t: String): String {
        val devanagari = t.any { it in 'ऀ'..'ॿ' }
        return if (devanagari) "तुम्ही म्हणालात: $t" else "You said: $t"
    }

    private fun isStop(t: String): Boolean {
        val s = t.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() || it in 'ऀ'..'ॿ' }.trim()
        return s in STOP_WORDS
    }

    private val STOP_WORDS = setOf(
        "stop", "cancel", "never mind", "shut up", "be quiet", "quiet",
        "थांब", "थांबा", "बस", "बस झाले", "बंद कर", "बंद करा", "स्टॉप", "राहू दे", "राहूदे"
    )
}
