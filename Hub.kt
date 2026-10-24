package app.jarvis

import android.os.Handler
import android.os.Looper

/** Shared state and the on-screen log. Everything here is touched on the main thread. */
object Hub {
    enum class State { OFF, LISTENING, AWAKE, THINKING, SPEAKING }

    class Entry(val who: String, val text: String)

    interface Listener {
        fun onEntry(entry: Entry)
        fun onState(state: State)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()

    val entries = ArrayList<Entry>()

    @Volatile
    var state: State = State.OFF
        private set

    /** True while MainActivity is on screen. */
    @Volatile
    var appVisible = false

    /** True while the listening service is running. */
    @Volatile
    var serviceRunning = false

    /** True while the 1x1 overlay window that allows background app launches is attached. */
    @Volatile
    var overlayAttached = false

    fun add(l: Listener) {
        listeners.add(l)
    }

    fun remove(l: Listener) {
        listeners.remove(l)
    }

    fun log(who: String, text: String) {
        runMain {
            val e = Entry(who, text)
            entries.add(e)
            if (entries.size > 200) entries.removeAt(0)
            for (l in ArrayList(listeners)) l.onEntry(e)
        }
    }

    fun set(s: State) {
        runMain {
            if (state != s) {
                state = s
                for (l in ArrayList(listeners)) l.onState(s)
            }
        }
    }

    private fun runMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
