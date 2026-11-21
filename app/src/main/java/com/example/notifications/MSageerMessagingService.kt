package com.example.notifications

import android.util.Log
import com.example.data.repository.FintechRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Firebase Cloud Messaging service that syncs refreshed FCM device tokens
 * into users/{uid}.fcmToken and displays high-priority Android status-bar alerts
 * whenever a Flutterwave deposit or utility transaction settles.
 */
class MSageerMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) return
        if (Firebase.auth.currentUser == null) return

        serviceScope.launch {
            try {
                FintechRepository(applicationContext).saveFcmToken(cleanToken)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync refreshed FCM token", e)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "M-Sageer Pay"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return
        val key = message.data["reference"]
            ?: message.messageId
            ?: System.currentTimeMillis().toString()

        FintechNotificationManager.showTransactionNotification(
            context = applicationContext,
            title = title,
            body = body,
            notificationKey = key
        )
    }

    private companion object {
        const val TAG = "MSageerFCM"
    }
}
