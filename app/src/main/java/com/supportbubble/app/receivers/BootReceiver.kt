package com.supportbubble.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.supportbubble.app.services.SocketForegroundService
import com.supportbubble.app.socket.AppSocket

private const val TAG = "BootReceiver"

/**
 * BroadcastReceiver that auto-starts [SocketForegroundService] after device boot.
 *
 * ## Handled intents
 *
 * | Intent action                          | When fired                                        |
 * |----------------------------------------|---------------------------------------------------|
 * | `ACTION_BOOT_COMPLETED`                | Normal boot after full disk decryption (API 26+) |
 * | `ACTION_LOCKED_BOOT_COMPLETED`         | Direct-boot phase (before user unlocks device)   |
 * | `QUICKBOOT_POWERON`                    | Fast-boot on some HTC / Xiaomi devices            |
 * | `ACTION_MY_PACKAGE_REPLACED`           | App updated via Play Store / sideload             |
 *
 * `ACTION_BOOT_COMPLETED` fires only after the user unlocks the device at least
 * once (credential-encrypted storage is available), so [AppSocket] and Room are
 * fully accessible when this receiver runs.
 *
 * ## Android version notes
 *
 * - Android 8.0+ (API 26): `startForegroundService()` required; the service
 *   must call `startForeground()` within 5 seconds (already done in onCreate).
 * - Android 10+ (API 29): Starting foreground services from the background is
 *   restricted, but `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are explicit
 *   exemptions and are always allowed.
 * - Android 12+ (API 31): Same exemptions apply.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Received: ${intent.action} — starting SocketForegroundService")

                // Initialise the shared socket singleton before the service starts.
                // SocketForegroundService.onCreate() also calls this (idempotent)
                // but initialising here ensures AppSocket is ready before the
                // service's own onCreate fires.
                AppSocket.init(context.applicationContext)

                // startForegroundService() is safe to call from a BroadcastReceiver;
                // the service will call startForeground() within the required 5-second window.
                SocketForegroundService.start(context)
            }
            else -> {
                Log.d(TAG, "Ignoring unexpected action: ${intent.action}")
            }
        }
    }
}
