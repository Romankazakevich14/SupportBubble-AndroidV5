package com.supportbubble.app.services

import android.content.Context
import android.os.Build
import com.supportbubble.app.models.DeviceInfo
import java.util.UUID

private const val PREFS_NAME = "support_bubble_prefs"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_LAST_APP = "last_app"
private const val KEY_FCM_TOKEN = "fcm_token"

object DeviceInfoService {

    /**
     * Returns the persisted deviceId, generating a new UUID on first launch.
     * The UUID is stable for the lifetime of the app installation.
     */
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { newId ->
                prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            }
    }

    /**
     * Returns full device info. [lastApp] is populated from SharedPreferences
     * (kept up-to-date by AppMonitorService) so even a cold-start registration
     * sends the previously observed foreground app.
     */
    fun getDeviceInfo(context: Context): DeviceInfo = DeviceInfo(
        deviceId = getDeviceId(context),
        phoneModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        androidVersion = Build.VERSION.RELEASE,
        lastApp = getLastApp(context).ifBlank { context.packageName },
    )

    /** Persists the most recently observed foreground package name. */
    fun saveLastApp(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_APP, packageName)
            .apply()
    }

    /** Returns the last persisted foreground package name, or empty string. */
    fun getLastApp(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_APP, "") ?: ""

    // ── FCM token ─────────────────────────────────────────────────────────────

    /**
     * Persists the FCM registration token locally.
     * Called from [SupportFirebaseMessagingService.onNewToken] whenever FCM
     * issues a new or refreshed token.
     */
    fun saveFcmToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()
    }

    /**
     * Returns the last-persisted FCM registration token, or null if not yet
     * available (e.g. first cold-start before FCM has issued a token).
     */
    fun getFcmToken(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FCM_TOKEN, null)
}
