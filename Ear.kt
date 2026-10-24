package app.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Microphone side. Runs the system speech recognizer in a loop, looking for the wake word.
 * After the wake word it can also take one command utterance.
 * All calls must be made on the main thread.
 */
class Ear(
    private val context: Context,
    private val prefs: Prefs,
    private val cb: Callback
) {
    interface Callback {
        /** The wake word was heard. [inlineCommand] is whatever followed it in the same breath (may be empty). */
        fun onWake(inlineCommand: String)

        /** One command utterance was heard after the wake word. */
        fun onCommand(text: String)

        /** Nothing usable was heard while waiting for a command. */
        fun onCommandFailed()

        /** Listening cannot continue (permission or language missing). */
        fun onFatal()
    }

    private enum class Mode { WAKE, COMMAND }

    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var enabled = false
    private var paused = false
    private var mode = Mode.WAKE
    private var errors = 0
    private var preferOffline = true
    private val beginTask = Runnable { begin() }

    fun start() {
        enabled = true
        paused = false
        mode = Mode.WAKE
        schedule(0)
    }

    fun stop() {
        enabled = false
        main.removeCallbacks(beginTask)
        killRecognizer()
    }

    /** Stop listening for now (while Jarvis speaks or thinks). */
    fun pause() {
        paused = true
        main.removeCallbacks(beginTask)
        try {
            rec?.cancel()
        } catch (e: Exception) {
        }
    }

    /** Go back to waiting for the wake word. */
    fun resumeWake() {
        if (!enabled) return
        paused = false
        mode = Mode.WAKE
        Hub.set(Hub.State.LISTENING)
        schedule(600)
    }

    /** Listen for one command without needing the wake word. */
    fun listenCommand() {
        if (!enabled) return
        paused = false
        mode = Mode.COMMAND
        Hub.set(Hub.State.AWAKE)
        schedule(0)
    }

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(beginTask)
        main.postDelayed(beginTask, delayMs)
    }

    private fun begin() {
        if (!enabled || paused) return
        try {
            var r = rec
            if (r == null) {
                r = SpeechRecognizer.createSpeechRecognizer(context)
                r.setRecognitionListener(listener)
                rec = r
            }
            r.startListening(buildIntent())
        } catch (e: Exception) {
            killRecognizer()
            schedule(2000)
        }
    }

    private fun buildIntent(): Intent {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.lang)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline && mode == Mode.WAKE)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
        return i
    }

    private fun killRecognizer() {
        try {
            rec?.cancel()
            rec?.destroy()
        } catch (e: Exception) {
        }
        rec = null
    }

    private fun handleResults(list: List<String>) {
        if (!enabled || paused) return
        errors = 0
        if (mode == Mode.WAKE) {
            for (c in list) {
                val rest = Wake.split(c)
                if (rest != null) {
                    paused = true
                    cb.onWake(rest)
                    return
                }
            }
            schedule(150)
        } else {
            val best = list.firstOrNull()?.trim().orEmpty()
            paused = true
            if (best.isEmpty()) cb.onCommandFailed() else cb.onCommand(best)
        }
    }

    private fun failCommand() {
        paused = true
        cb.onCommandFailed()
    }

    private fun handleError(code: Int) {
        if (!enabled || paused) return
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                errors = 0
                if (mode == Mode.WAKE) schedule(250) else failCommand()
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                killRecognizer()
                schedule(1000)
            }
            SpeechRecognizer.ERROR_CLIENT -> {
                killRecognizer()
                schedule(600)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                enabled = false
                Hub.log("SYSTEM", "माइक परवानगी नाही. फोनच्या सेटिंग्जमधून द्या.")
                cb.onFatal()
            }
            12, 13 -> {
                // 12 = language not supported, 13 = language unavailable (offline pack missing)
                if (preferOffline) {
                    preferOffline = false
                    killRecognizer()
                    schedule(300)
                } else {
                    enabled = false
                    Hub.log("SYSTEM", "ही भाषा या फोनवर उपलब्ध नाही. सेटिंग्जमध्ये दुसरी भाषा निवडा.")
                    cb.onFatal()
                }
            }
            else -> {
                errors++
                if (errors == 3) {
                    Hub.log("SYSTEM", "Speech recognizer मध्ये अडचण (कोड $code). इंटरनेट आणि Google अ‍ॅप तपासा.")
                }
                killRecognizer()
                if (mode == Mode.COMMAND) failCommand() else schedule(minOf(1000L * errors, 8000L))
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            handleResults(list ?: emptyList())
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
