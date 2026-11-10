package com.meshtalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service (tipe microphone): menjaga mikrofon, WebRTC, dan koneksi mesh
 * tetap hidup saat aplikasi di latar belakang atau layar mati.
 * Mesh dimiliki service ini, bukan Activity.
 */
class CallService : Service() {
    inner class LocalBinder : Binder() {
        val service: CallService get() = this@CallService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    lateinit var mesh: Mesh
        private set

    override fun onCreate() {
        super.onCreate()
        running = true
        mesh = Mesh(applicationContext).also { it.onDead = { main.post { stopCall() } } }
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Panggilan MeshTalk", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCall()
            return START_NOT_STICKY
        }
        // Wajib dipanggil <5 detik setelah startForegroundService().
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification("Menyiapkan panggilan…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        return START_NOT_STICKY
    }

    fun startCall(url: String, room: String, name: String, speaker: Boolean) {
        val am = getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION // echo cancel + rute headset Bluetooth
        am.isSpeakerphoneOn = speaker
        wake?.takeIf { it.isHeld }?.release()
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meshtalk:call")
            .apply { acquire(12 * 60 * 60 * 1000L) } // batas aman 12 jam
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notification("Tersambung di ruang $room"))
        mesh.join(url, room, name)
    }

    fun setSpeaker(on: Boolean) {
        getSystemService(AudioManager::class.java).isSpeakerphoneOn = on
    }

    fun stopCall() {
        mesh.leave()
        getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        mesh.leave()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MeshTalk")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Keluar", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.meshtalk.STOP"
        private const val CHANNEL = "call"
        private const val NOTIF_ID = 1
        @Volatile var running = false
    }
}
