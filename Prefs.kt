package app.jarvis

import android.content.Context

/** Settings and small local data. Stored in the app's private storage. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) {
            sp.edit().putString("apiKey", v.trim()).apply()
        }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) {
            sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()
        }

    /** Speech recognition language: mr-IN, hi-IN or en-IN. */
    var lang: String
        get() = sp.getString("lang", "mr-IN") ?: "mr-IN"
        set(v) {
            sp.edit().putString("lang", v).apply()
        }

    /** Repeat what the user said before answering. */
    var echo: Boolean
        get() = sp.getBoolean("echo", true)
        set(v) {
            sp.edit().putBoolean("echo", v).apply()
        }

    /** Read replies aloud. */
    var voiceReplies: Boolean
        get() = sp.getBoolean("voiceReplies", true)
        set(v) {
            sp.edit().putBoolean("voiceReplies", v).apply()
        }

    /** After a reply, keep listening for a follow-up without the wake word. */
    var followUp: Boolean
        get() = sp.getBoolean("followUp", false)
        set(v) {
            sp.edit().putBoolean("followUp", v).apply()
        }

    var notes: String
        get() = sp.getString("notes", "[]") ?: "[]"
        set(v) {
            sp.edit().putString("notes", v).apply()
        }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
    }
}
