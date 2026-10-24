package app.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Keeps the microphone loop alive in the background so "Hey Jarvis" works with the screen off. */
class JarvisService : Service(), Ear.Callback {
    private var ear: Ear? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: View? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ear == null) beginListening()
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean {
        return try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            Hub.log("SYSTEM", "बॅकग्राउंड सेवा सुरू होऊ शकली नाही: " + (e.message ?: ""))
            false
        }
    }

    private fun beginListening() {
        Core.init(this)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Hub.log("SYSTEM", "या फोनवर speech recognition उपलब्ध नाही. Google अ‍ॅप अपडेट करा.")
            stopSelf()
            return
        }
        Hub.serviceRunning = true
        acquireWakeLock()
        attachOverlay()

        Core.onSpeechActive = { active ->
            if (active) {
                ear?.pause()
            } else if (!Core.busy) {
                ear?.resumeWake()
            }
        }
        Core.onTurnDone = {
            if (Core.prefs.followUp) ear?.listenCommand() else ear?.resumeWake()
        }

        val e = Ear(this, Core.prefs, this)
        ear = e
        e.start()
        Hub.set(Hub.State.LISTENING)
        Hub.log("SYSTEM", "Jarvis चालू आहे. ‘Hey Jarvis’ म्हणा.")
    }

    // ---- Ear.Callback

    override fun onWake(inlineCommand: String) {
        if (Core.busy) {
            ear?.resumeWake()
            return
        }
        beep()
        if (inlineCommand.length >= 2) {
            Core.submit(inlineCommand, true)
        } else {
            ear?.listenCommand()
        }
    }

    override fun onCommand(text: String) {
        if (Core.busy) {
            ear?.resumeWake()
            return
        }
        Core.submit(text, true)
    }

    override fun onCommandFailed() {
        Hub.log("SYSTEM", "काही ऐकू आले नाही.")
        ear?.resumeWake()
    }

    override fun onFatal() {
        stopSelf()
    }

    // ---- helpers

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            main.postDelayed({ tg.release() }, 500)
        } catch (e: Exception) {
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:listen")
            wl.setReferenceCounted(false)
            wl.acquire(12 * 60 * 60 * 1000L)
            wakeLock = wl
        } catch (e: Exception) {
        }
    }

    /**
     * A 1x1 transparent overlay window. Android lets apps that show an overlay start other apps
     * from the background, which is what "Hey Jarvis, open YouTube" needs with the screen off.
     */
    private fun attachOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(WindowManager::class.java)
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.START or Gravity.TOP
            wm.addView(v, lp)
            overlay = v
            Hub.overlayAttached = true
        } catch (e: Exception) {
            Hub.overlayAttached = false
        }
    }

    private fun detachOverlay() {
        val v = overlay ?: return
        try {
            getSystemService(WindowManager::class.java).removeView(v)
        } catch (e: Exception) {
        }
        overlay = null
        Hub.overlayAttached = false
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "थांबवा", stop
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis ऐकत आहे")
            .setContentText("‘Hey Jarvis’ म्हणा")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    override fun onDestroy() {
        ear?.stop()
        ear = null
        Core.onSpeechActive = null
        Core.onTurnDone = null
        try {
            Core.speaker.stop()
        } catch (e: Exception) {
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        detachOverlay()
        Hub.serviceRunning = false
        Hub.set(Hub.State.OFF)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.jarvis.STOP"
        private const val CHANNEL = "jarvis_service"
        private const val NOTIF_ID = 1
    }
}
