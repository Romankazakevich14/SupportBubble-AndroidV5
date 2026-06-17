package com.supportbubble.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.supportbubble.app.services.DeviceInfoService
import com.supportbubble.app.services.InstalledAppsService
import com.supportbubble.app.services.SocketForegroundService
import com.supportbubble.app.services.SupportFirebaseMessagingService
import com.supportbubble.app.socket.AppSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SupportBubbleApp"
private const val ALLOWED_APPS_POLL_INTERVAL_MS = 5 * 60 * 1_000L   // 5 minutes
private const val INSTALLED_APPS_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1_000L  // 24 hours

class SupportBubbleApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()
        AppSocket.init(applicationContext)

        val deviceId = DeviceInfoService.getDeviceId(applicationContext)

        // ── AllowedAppsManager: load cache + wire socket callback ─────────────
        AllowedAppsManager.init(applicationContext)

        // ── Background tasks ──────────────────────────────────────────────────
        appScope.launch {
            // 1. Fetch allowed apps immediately on startup, then poll every 5 min
            AllowedAppsManager.fetchFromServer(deviceId, applicationContext)
            while (true) {
                delay(ALLOWED_APPS_POLL_INTERVAL_MS)
                AllowedAppsManager.fetchFromServer(deviceId, applicationContext)
            }
        }

        appScope.launch {
            // 2. Sync installed apps on startup, then every 24 h
            syncInstalledApps(deviceId)
            while (true) {
                delay(INSTALLED_APPS_SYNC_INTERVAL_MS)
                syncInstalledApps(deviceId)
            }
        }

        // 3. FCM token: fetch and register with backend
        refreshFcmToken()

        Log.d(TAG, "Application started — deviceId: $deviceId")
    }

    override fun onTerminate() {
        AppSocket.shutdown()
        super.onTerminate()
    }

    // ── Notification channels ─────────────────────────────────────────────────

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                SocketForegroundService.CHANNEL_ID,
                "Background Connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Support Bubble connected to the server in the background"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                SupportFirebaseMessagingService.CHANNEL_ID,
                "New Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Push notifications for new support messages"
                enableVibration(true)
                enableLights(true)
            }
        )
    }

    // ── Installed apps sync ───────────────────────────────────────────────────

    private suspend fun syncInstalledApps(deviceId: String) {
        val apps = InstalledAppsService.getInstalledApps(applicationContext)
        Log.d(TAG, "Syncing ${apps.size} installed apps to server")
        InstalledAppsService.syncToServer(deviceId, apps, applicationContext)
    }

    // ── FCM token ─────────────────────────────────────────────────────────────

    private fun refreshFcmToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (token.isNullOrBlank()) return@addOnSuccessListener
            DeviceInfoService.saveFcmToken(applicationContext, token)
            val deviceId = DeviceInfoService.getDeviceId(applicationContext)
            appScope.launch {
                SupportFirebaseMessagingService.registerTokenWithBackend(
                    context = applicationContext,
                    deviceId = deviceId,
                    token = token,
                )
            }
        }.addOnFailureListener { err ->
            Log.w(TAG, "Failed to fetch FCM token on startup", err)
        }
    }
}
