package com.mikhaildezprofi.mobileaiagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Keeps the app process important while local GGUF inference runs in the background. */
class GenerationForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                BackgroundGenerationController.cancelCurrent()
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseWakeLock()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_COMPLETE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Ответ готов"
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "MOBILE AI завершил генерацию ответа"
                showCompletedNotification(title, message)
                // Detach the ongoing foreground notification; the completed notification remains tappable.
                stopForeground(STOP_FOREGROUND_DETACH)
                releaseWakeLock()
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        val localInference = intent?.getBooleanExtra(EXTRA_LOCAL_INFERENCE, true) ?: true
        startForeground(NOTIFICATION_ID, buildRunningNotification(localInference))
        if (localInference) acquireWakeLock() else releaseWakeLock()
        return START_NOT_STICKY
    }

    private fun openChatPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CHAT, true)
        }
        return PendingIntent.getActivity(
            this, 1001, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:LocalModelInference"
        ).apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Генерация ответа", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Статус фоновой генерации локальной модели" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildRunningNotification(localInference: Boolean): Notification {
        val stopIntent = Intent(this, GenerationForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1002, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("MOBILE AI AGENT")
            .setContentText(
                if (localInference) "Локальная модель генерирует ответ в фоне"
                else "Облачная модель готовит ответ; телефон не удерживает CPU активным"
            )
            .setContentIntent(openChatPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
            .build()
    }

    private fun showCompletedNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openChatPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.mikhaildezprofi.mobileaiagent.action.START_GENERATION"
        const val ACTION_STOP = "com.mikhaildezprofi.mobileaiagent.action.STOP_GENERATION"
        const val ACTION_COMPLETE = "com.mikhaildezprofi.mobileaiagent.action.COMPLETE_GENERATION"
        const val EXTRA_TITLE = "notification_title"
        const val EXTRA_MESSAGE = "notification_message"
        const val EXTRA_OPEN_CHAT = "open_chat"
        const val EXTRA_LOCAL_INFERENCE = "local_inference"
        private const val CHANNEL_ID = "local_model_generation"
        private const val NOTIFICATION_ID = 4107
    }
}
