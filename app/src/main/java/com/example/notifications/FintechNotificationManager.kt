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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Parsed destination when a user taps a push notification (foreground or background).
 */
data class NotificationDeepLink(
    val route: String,
    val reference: String? = null,
    val notificationId: String? = null
)

/**
 * Industry-standard Android notification manager for M-Sageer Pay.
 * - Registers categorized NotificationChannels matching backend FCM v1 channel IDs.
 * - Uses monochrome status-bar icon + brand accent color + BigTextStyle.
 * - Deduplicates foreground FCM + Firestore realtime alerts to prevent double alerts.
 * - Builds deep-link PendingIntents and parses both foreground and FCM background tap extras.
 * - Generates a Play-policy-compliant installation-scoped device ID (no hardware identifiers).
 */
object FintechNotificationManager {
    const val CHANNEL_TRANSACTIONS = "msageer_transactions"
    const val CHANNEL_SECURITY = "msageer_security"
    const val CHANNEL_SUPPORT = "msageer_support"
    const val CHANNEL_ANNOUNCEMENTS = "msageer_announcements"

    // Backward-compatible alias
    const val CHANNEL_ID = CHANNEL_TRANSACTIONS

    const val ACTION_NOTIFICATION_CLICK = "MSAGEER_NOTIFICATION_CLICK"
    const val EXTRA_NAV_ROUTE = "extra_nav_route"
    const val EXTRA_TX_REF = "extra_tx_ref"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    const val EXTRA_NOTIFICATION_TYPE = "extra_notification_type"

    private const val PREFS_NAME = "msageer_push_prefs"
    private const val KEY_INSTALL_DEVICE_ID = "install_device_id"
    private const val DEDUP_WINDOW_MS = 90_000L
    private const val TAG = "FintechNotify"

    private val recentlyShown = ConcurrentHashMap<String, Long>()

    private data class ChannelSpec(
        val id: String,
        val name: String,
        val description: String,
        val importance: Int
    )

    private val channelSpecs = listOf(
        ChannelSpec(
            id = CHANNEL_TRANSACTIONS,
            name = "Wallet & Transactions",
            description = "Instant alerts for wallet deposits, utility purchases, e-PINs, and refunds.",
            importance = NotificationManager.IMPORTANCE_HIGH
        ),
        ChannelSpec(
            id = CHANNEL_SECURITY,
            name = "Security & Account Alerts",
            description = "Critical account status updates, sign-in alerts, and security notices.",
            importance = NotificationManager.IMPORTANCE_HIGH
        ),
        ChannelSpec(
            id = CHANNEL_SUPPORT,
            name = "Support Updates",
            description = "Replies and status updates on your customer support tickets.",
            importance = NotificationManager.IMPORTANCE_DEFAULT
        ),
        ChannelSpec(
            id = CHANNEL_ANNOUNCEMENTS,
            name = "Announcements & Updates",
            description = "Service updates, announcements, and platform notices.",
            importance = NotificationManager.IMPORTANCE_DEFAULT
        )
    )

    fun ensureAllChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        channelSpecs.forEach { spec ->
            if (manager.getNotificationChannel(spec.id) == null) {
                val channel = NotificationChannel(spec.id, spec.name, spec.importance).apply {
                    description = spec.description
                    enableVibration(spec.importance >= NotificationManager.IMPORTANCE_DEFAULT)
                    enableLights(spec.importance >= NotificationManager.IMPORTANCE_HIGH)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun ensureChannel(context: Context) = ensureAllChannels(context)

    fun getOrCreateInstallDeviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALL_DEVICE_ID, null)?.trim()
        if (!existing.isNullOrBlank()) return existing
        val generated = "and_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        prefs.edit().putString(KEY_INSTALL_DEVICE_ID, generated).apply()
        return generated
    }

    fun buildDeviceDisplayName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val combined = when {
            manufacturer.isBlank() && model.isBlank() -> "Android Device"
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model".trim()
        }
        return "$combined (Android ${Build.VERSION.RELEASE ?: ""})".trim().take(80)
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

    fun resolveChannelForType(type: String?): String = when (type?.trim()?.uppercase()) {
        "WALLET_FUNDING",
        "PURCHASE_SUCCESS",
        "PURCHASE_FAILED",
        "TRANSACTION_PENDING",
        "REFUND" -> CHANNEL_TRANSACTIONS
        "SECURITY_ALERT",
        "ACCOUNT_STATUS" -> CHANNEL_SECURITY
        "SUPPORT_REPLY" -> CHANNEL_SUPPORT
        else -> CHANNEL_ANNOUNCEMENTS
    }

    fun resolveRouteForType(type: String?, reference: String?): String = when (type?.trim()?.uppercase()) {
        "WALLET_FUNDING",
        "PURCHASE_SUCCESS",
        "PURCHASE_FAILED",
        "TRANSACTION_PENDING",
        "REFUND" -> if (!reference.isNullOrBlank()) "transaction_receipt" else "history"
        "SUPPORT_REPLY" -> "support"
        "SECURITY_ALERT",
        "ACCOUNT_STATUS" -> "profile"
        else -> "notifications"
    }

    fun parseDeepLink(intent: Intent?): NotificationDeepLink? {
        if (intent == null) return null
        val route = intent.getStringExtra(EXTRA_NAV_ROUTE)
            ?: intent.getStringExtra("route")
        val reference = intent.getStringExtra(EXTRA_TX_REF)
            ?: intent.getStringExtra("reference")
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
            ?: intent.getStringExtra("notificationId")
        val type = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE)
            ?: intent.getStringExtra("type")

        val resolvedRoute = route?.trim()?.takeIf { it.isNotEmpty() }
            ?: type?.trim()?.takeIf { it.isNotEmpty() }?.let { resolveRouteForType(it, reference) }
            ?: return null

        return NotificationDeepLink(
            route = resolvedRoute,
            reference = reference?.trim()?.takeIf { it.isNotEmpty() },
            notificationId = notificationId?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    fun clearDeepLinkExtras(intent: Intent?) {
        intent ?: return
        intent.removeExtra(EXTRA_NAV_ROUTE)
        intent.removeExtra("route")
        intent.removeExtra(EXTRA_TX_REF)
        intent.removeExtra("reference")
        intent.removeExtra(EXTRA_NOTIFICATION_ID)
        intent.removeExtra("notificationId")
        intent.removeExtra(EXTRA_NOTIFICATION_TYPE)
        intent.removeExtra("type")
    }

    fun showNotification(
        context: Context,
        title: String,
        body: String,
        type: String = "ANNOUNCEMENT",
        channelIdOverride: String? = null,
        routeOverride: String? = null,
        reference: String? = null,
        notificationId: String? = null
    ) {
        ensureAllChannels(context)
        if (!hasNotificationPermission(context)) {
            Log.d(TAG, "POST_NOTIFICATIONS not granted; skipping system tray notification.")
            return
        }

        val cleanTitle = title.trim().ifBlank { "M-Sageer Pay" }
        val cleanBody = body.trim()
        if (cleanBody.isEmpty()) return

        val primaryKey = notificationId?.trim()?.takeIf { it.isNotEmpty() }
            ?: reference?.trim()?.takeIf { it.isNotEmpty() }?.let { "ref:$it:${type.uppercase()}" }
            ?: "msg:${cleanTitle.lowercase()}:${cleanBody.take(48).lowercase()}"
        val secondaryKey = reference?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "ref:$it:${cleanTitle.lowercase()}"
        }

        if (shouldSuppressDuplicate(primaryKey, secondaryKey)) {
            Log.d(TAG, "Suppressed duplicate foreground notification: $primaryKey")
            return
        }

        val resolvedChannel = channelIdOverride?.trim()?.takeIf { id ->
            channelSpecs.any { it.id == id }
        } ?: resolveChannelForType(type)

        val resolvedRoute = routeOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolveRouteForType(type, reference)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_NOTIFICATION_CLICK
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_NAV_ROUTE, resolvedRoute)
            putExtra(EXTRA_NOTIFICATION_TYPE, type)
            if (!reference.isNullOrBlank()) putExtra(EXTRA_TX_REF, reference.trim())
            if (!notificationId.isNullOrBlank()) putExtra(EXTRA_NOTIFICATION_ID, notificationId.trim())
        }

        val requestCode = primaryKey.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priority = if (resolvedChannel == CHANNEL_TRANSACTIONS || resolvedChannel == CHANNEL_SECURITY) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }
        val category = when (resolvedChannel) {
            CHANNEL_SECURITY -> NotificationCompat.CATEGORY_ALARM
            CHANNEL_SUPPORT -> NotificationCompat.CATEGORY_MESSAGE
            else -> NotificationCompat.CATEGORY_STATUS
        }

        val builder = NotificationCompat.Builder(context, resolvedChannel)
            .setSmallIcon(R.drawable.ic_stat_msageer_notify)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(cleanTitle)
            .setContentText(cleanBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanBody))
            .setPriority(priority)
            .setCategory(category)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            NotificationManagerCompat.from(context).notify(requestCode, builder.build())
        } catch (se: SecurityException) {
            Log.w(TAG, "Notification permission denied when posting alert", se)
        }
    }

    fun showTransactionNotification(
        context: Context,
        title: String,
        body: String,
        notificationKey: String = System.currentTimeMillis().toString(),
        type: String = "PURCHASE_SUCCESS",
        reference: String? = null
    ) {
        showNotification(
            context = context,
            title = title,
            body = body,
            type = type,
            reference = reference,
            notificationId = notificationKey
        )
    }

    private fun shouldSuppressDuplicate(primaryKey: String, secondaryKey: String?): Boolean {
        val now = System.currentTimeMillis()
        // Prune expired entries occasionally
        if (recentlyShown.size > 64) {
            recentlyShown.entries.removeIf { (_, ts) -> now - ts > DEDUP_WINDOW_MS }
        }
        val existingPrimary = recentlyShown[primaryKey]
        if (existingPrimary != null && now - existingPrimary < DEDUP_WINDOW_MS) {
            return true
        }
        if (secondaryKey != null) {
            val existingSecondary = recentlyShown[secondaryKey]
            if (existingSecondary != null && now - existingSecondary < DEDUP_WINDOW_MS) {
                return true
            }
            recentlyShown[secondaryKey] = now
        }
        recentlyShown[primaryKey] = now
        return false
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
