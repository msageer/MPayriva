package com.example.notifications

import android.util.Log
import com.example.BuildConfig
import com.example.data.remote.TrustedBackendClient
import com.example.data.repository.FintechRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Industry-standard Firebase Cloud Messaging service for M-Sageer Pay.
 * - Syncs rotated/refreshed FCM tokens to both the multi-device backend registry and Firestore.
 * - Routes foreground & data-only pushes to the right Android NotificationChannel with deep-link extras.
 * - Cleans up its coroutine scope in onDestroy().
 */
class MSageerMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) return
        if (Firebase.auth.currentUser == null) return

        val deviceId = FintechNotificationManager.getOrCreateInstallDeviceId(applicationContext)
        val deviceName = FintechNotificationManager.buildDeviceDisplayName()
        val appVersion = BuildConfig.VERSION_NAME

        serviceScope.launch {
            try {
                FintechRepository(applicationContext).registerDeviceTokenFirestore(
                    deviceId = deviceId,
                    token = cleanToken,
                    appVersion = appVersion,
                    deviceName = deviceName
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync refreshed FCM token to Firestore", e)
            }
            try {
                val client = TrustedBackendClient()
                if (client.isConfigured) {
                    client.registerPushToken(
                        token = cleanToken,
                        deviceId = deviceId,
                        appVersion = appVersion,
                        deviceName = deviceName
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync refreshed FCM token to backend", e)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val title = message.notification?.title
            ?: data["title"]
            ?: "M-Sageer Pay"
        val body = message.notification?.body
            ?: data["body"]
            ?: return

        val type = data["type"]?.takeIf { it.isNotBlank() } ?: "ANNOUNCEMENT"
        val channelId = message.notification?.channelId
            ?: data["channelId"]?.takeIf { it.isNotBlank() }
        val route = data["route"]?.takeIf { it.isNotBlank() }
        val reference = data["reference"]?.takeIf { it.isNotBlank() }
        val notificationId = data["notificationId"]?.takeIf { it.isNotBlank() }
            ?: message.messageId

        FintechNotificationManager.showNotification(
            context = applicationContext,
            title = title,
            body = body,
            type = type,
            channelIdOverride = channelId,
            routeOverride = route,
            reference = reference,
            notificationId = notificationId
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private companion object {
        const val TAG = "MSageerFCM"
    }
}
