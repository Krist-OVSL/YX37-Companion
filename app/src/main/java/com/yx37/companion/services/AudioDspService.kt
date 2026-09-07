package com.yx37.companion.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yx37.companion.MainActivity
import com.yx37.companion.R
import com.yx37.companion.audio.AudioDspManager
import com.yx37.companion.data.PreferencesManager

class AudioDspService : Service() {

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            AudioDspManager.init(applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onCreate", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_SERVICE) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (t: Throwable) {
                // ignore
            }
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasPerm) {
                        Log.w(TAG, "BLUETOOTH_CONNECT not granted, stopping service gracefully.")
                        stopSelf()
                        return START_NOT_STICKY
                    }

                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException on startForeground: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        } catch (t: Throwable) {
            Log.e(TAG, "Throwable on startForeground: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val prefs = PreferencesManager(this)
        val isConn = prefs.isConnected
        val title = "🎧 YX37 Equalizer"
        val statusText = if (isConn) {
            val bat = prefs.lastBatteryLevel
            val batStr = if (bat >= 0) " (Pin $bat%)" else ""
            "Đang hoạt động trên tai nghe YX37$batStr"
        } else {
            "Sẵn sàng • Chờ kết nối tai nghe YX37"
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "YX37 Dịch vụ Âm thanh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Duy trì hoạt động liên tục của Equalizer khi nghe nhạc"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "AudioDspService"
        private const val CHANNEL_ID = "yx37_audio_dsp_channel"
        private const val NOTIFICATION_ID = 3701
        const val ACTION_STOP_SERVICE = "com.yx37.companion.action.STOP_SERVICE"

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPerm) {
                    Log.d(TAG, "Will not start service yet: BLUETOOTH_CONNECT not granted")
                    return
                }
            }

            try {
                val intent = Intent(context, AudioDspService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not start AudioDspService: ${t.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, AudioDspService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            } catch (t: Throwable) {
                // ignore
            }
        }
    }
}
