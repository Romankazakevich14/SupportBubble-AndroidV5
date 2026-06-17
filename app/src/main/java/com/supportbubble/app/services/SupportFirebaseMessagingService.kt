package com.supportbubble.app.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.supportbubble.app.BuildConfig
import com.supportbubble.app.MainActivity
import com.supportbubble.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "FCMService"

/**
 * Firebase Cloud Messaging service for Support Bubble.
 *
 * ## Responsibilities
 * 1. [onNewToken]         — Whenever FCM issues a new/refreshed registration
 *                           token, save it locally and register it with the
 *                           backend so the server can send push notifications.
 * 2. [onMessageReceived]  — When a push message arrives and the app is in the
 *                           foreground (or the message is data-only), build and
 *                           display the notification manually.
 *
 * ## Background behaviour
 * When the app is in the background and the FCM payload contains a
 * `notification` object, the Android system renders the notification
 * automatically — [onMessageReceived] is NOT called.  The system uses the
 * `android.notification.channel_id` field from the backend payload to route
 * the notification to the correct channel (set to [CHANNEL_ID] by the server).
 *
 * ## Tapping the notification
 * The notification's content intent launches [MainActivity].  Since
 * `MainActivity` is the sole chat entry point, the user lands directly in the
 * chat regardless of whether the app was in the foreground or background.
 */
class SupportFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        /** Notification channel id — must match [SupportBubbleApp] and the backend payload. */
        const val CHANNEL_ID = "chat_messages"

        /** Notification id for incoming-message alerts.
         *  Must differ from SocketForegroundService.NOTIFICATION_ID (1001) so that
         *  a push notification does not overwrite the persistent foreground-service
         *  notification, which would make the service invisible and risk it being
         *  stopped by the OS on API 34+. */
        private const val NOTIFICATION_ID = 2001

        private val http by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Sends the FCM registration token to the backend.
         *
         * Called from two places:
         * - [onNewToken]               — when FCM issues a new/refreshed token
         * - [SupportBubbleApp.onCreate] — on every app launch to handle the case
         *                                where the backend was unreachable at the
         *                                time of the original token issue
         *
         * The call is idempotent (backend performs an upsert) so calling it
         * multiple times is safe.
         */
        fun registerTokenWithBackend(context: Context, deviceId: String, token: String) {
            val json = "{\"fcmToken\":\"$token\"}"
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.SERVER_URL}/api/users/$deviceId/fcm-token")
                .put(body)
                .build()

            try {
                http.newCall(request).execute().use { response ->
                    Log.d(TAG, "registerToken: HTTP ${response.code} for deviceId=$deviceId")
                }
            } catch (e: IOException) {
                Log.w(TAG, "registerToken failed (will retry on next launch)", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── onNewToken ────────────────────────────────────────────────────────────

    /**
     * Called when FCM issues a new registration token or rotates an existing one.
     *
     * Always save the token locally first so [SupportBubbleApp.refreshFcmToken]
     * can re-register it on the next launch if the network call below fails.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received (first 20 chars): ${token.take(20)}…")

        DeviceInfoService.saveFcmToken(applicationContext, token)

        val deviceId = DeviceInfoService.getDeviceId(applicationContext)
        scope.launch {
            registerTokenWithBackend(applicationContext, deviceId, token)
        }
    }

    // ── onMessageReceived ─────────────────────────────────────────────────────

    /**
     * Called when a push message arrives while the app is in the foreground,
     * or for any data-only message regardless of foreground state.
     *
     * Builds and posts a [NotificationCompat] notification that, when tapped,
     * opens [MainActivity] (the chat screen).
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Message received from: ${remoteMessage.from}")

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "Support"

        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["text"]
            ?: "New message"

        showNotification(title, body)
    }

    // ── Notification builder ──────────────────────────────────────────────────

    private fun showNotification(title: String, body: String) {
        // PendingIntent: tap the notification → open MainActivity (chat screen)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            // ic_notification is a monochrome vector drawable — required for the
            // Android status bar (mipmap icons render as white squares on API 21+).
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
