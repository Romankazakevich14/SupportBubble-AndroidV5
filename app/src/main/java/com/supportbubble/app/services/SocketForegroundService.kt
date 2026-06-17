package com.supportbubble.app.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.supportbubble.app.MainActivity
import com.supportbubble.app.R
import com.supportbubble.app.SupportBubbleApp
import com.supportbubble.app.socket.AppSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Socket.io connection alive independently
 * of the UI lifecycle.
 *
 *  • Returns START_STICKY — Android restarts it automatically if the process is killed.
 *  • Shows a persistent low-priority notification (required by Android for foreground services).
 *  • Monitors [AppSocket.isConnectedFlow] and updates the notification text in real time.
 *  • Runs a watchdog loop every [WATCHDOG_INTERVAL_MS] as a safety net on top of
 *    socket.io's built-in reconnection logic.
 *
 * Start: call [SocketForegroundService.start] from MainActivity.onCreate().
 */
class SocketForegroundService : Service() {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SocketFgService"

        /** Notification channel ID — must match the channel created in [SupportBubbleApp]. */
        const val CHANNEL_ID = "sb_socket_service_channel"

        /** Stable notification ID (must not be 0). */
        private const val NOTIFICATION_ID = 1001

        /** How often the watchdog checks the connection, in ms. */
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        // ── Static helpers ────────────────────────────────────────────────────

        fun start(context: Context) {
            val intent = Intent(context, SocketForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SocketForegroundService::class.java))
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        // Must call startForeground() within 5 seconds of onCreate().
        startForeground(NOTIFICATION_ID, buildNotification(connected = false))

        // Ensure the shared socket is initialised (idempotent).
        AppSocket.init(applicationContext)

        observeConnectionState()
        startWatchdog()

        Log.d(TAG, "SocketForegroundService started")
    }

    /**
     * START_STICKY: if the service is killed, Android will restart it with a null Intent.
     * We do NOT need to handle any specific Intent actions here — just ensure the socket
     * and watchdog are running (both are set up in onCreate, which fires on restart too).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand — socket connected: ${AppSocket.isConnected}")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        Log.d(TAG, "SocketForegroundService destroyed")
        super.onDestroy()
    }

    // ── Connection monitoring ─────────────────────────────────────────────────

    /**
     * Collects [AppSocket.isConnectedFlow] and updates the notification text
     * each time the connection state changes.
     */
    private fun observeConnectionState() {
        serviceScope.launch {
            AppSocket.isConnectedFlow.collect { connected ->
                Log.d(TAG, "Connection state changed → connected=$connected")
                notificationManager.notify(NOTIFICATION_ID, buildNotification(connected))
            }
        }
    }

    /**
     * Safety-net watchdog: every [WATCHDOG_INTERVAL_MS] ms it checks whether the
     * socket is truly connected and forces a reconnect if not.
     * socket.io has its own reconnection loop, but this guards against edge cases
     * (e.g. the socket.io loop stalling after a long network outage).
     */
    private fun startWatchdog() {
        serviceScope.launch(Dispatchers.IO) {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!AppSocket.isConnected) {
                    Log.d(TAG, "Watchdog: socket disconnected — forcing reconnect")
                    AppSocket.reconnect()
                } else {
                    Log.v(TAG, "Watchdog: socket OK")
                }
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(connected: Boolean): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Support Bubble")
            .setContentText(if (connected) "Connected" else "Reconnecting…")
            .setOngoing(true)           // cannot be swiped away by the user
            .setSilent(true)            // no sound / vibration
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
