package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Manages the Android high-priority notification channel for wallet funding
 * and utility transaction alerts, plus FCM device token retrieval.
 */
object FintechNotificationManager {
    const val CHANNEL_ID = "msageer_transactions"
    private const val CHANNEL_NAME = "Wallet & Transaction Alerts"
    private const val CHANNEL_DESCRIPTION =
        "Instant alerts for Flutterwave deposits, utility purchases, and refunds."
    private const val TAG = "FintechNotify"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DESCRIPTION
                    enableVibration(true)
                    enableLights(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showTransactionNotification(
        context: Context,
        title: String,
        body: String,
        notificationKey: String = System.currentTimeMillis().toString()
    ) {
        ensureChannel(context)
        if (!hasNotificationPermission(context)) {
            Log.d(TAG, "POST_NOTIFICATIONS not granted yet; skipping system tray notification.")
            return
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationKey.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.ifBlank { "M-Sageer Pay" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            NotificationManagerCompat.from(context).notify(
                notificationKey.hashCode(),
                builder.build()
            )
        } catch (se: SecurityException) {
            Log.w(TAG, "Notification permission denied when posting alert", se)
        }
    }

    suspend fun fetchCurrentFcmToken(): String? {
        return try {
            FirebaseMessaging.getInstance().token.await()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to fetch FCM token", e)
            null
        }
    }
}
