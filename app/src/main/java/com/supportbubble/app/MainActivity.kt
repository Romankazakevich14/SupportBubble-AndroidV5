package com.supportbubble.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.provider.Settings.SettingNotFoundException
import android.text.TextUtils
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.supportbubble.app.services.AppMonitorService
import com.supportbubble.app.services.OverlayService
import com.supportbubble.app.services.SocketForegroundService
import com.supportbubble.app.ui.ChatListScreen
import com.supportbubble.app.ui.ChatScreen
import com.supportbubble.app.ui.ChatViewModel
import com.supportbubble.app.ui.theme.SupportBubbleTheme

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    // ── POST_NOTIFICATIONS runtime permission (Android 13+) ───────────────────
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Start the socket service regardless of whether the user granted
            // the notification permission — the foreground service still runs,
            // just without a visible notification.
            SocketForegroundService.start(this)
            // Request overlay permission next
            ensureOverlayPermission()
        }

    // ── ActivityResult for overlay Settings screen ────────────────────────────
    private val overlayPermissionResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User returned from Settings — start overlay service if granted
            if (Settings.canDrawOverlays(this)) {
                OverlayService.start(this)
                Log.d(TAG, "Overlay permission granted")
            } else {
                Log.w(TAG, "Overlay permission denied — bubble will not be shown")
            }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SupportBubbleTheme {
                val selectedPackage by viewModel.selectedPackage.collectAsStateWithLifecycle()
                if (selectedPackage == null) {
                    ChatListScreen(
                        viewModel = viewModel,
                        onOpenSession = { viewModel.selectSession(it) },
                    )
                } else {
                    BackHandler { viewModel.clearSelection() }
                    ChatScreen(
                        viewModel = viewModel,
                        onBack = { viewModel.clearSelection() },
                    )
                }
            }
        }

        ensureSocketServiceRunning()
        promptAccessibilityIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // If the user previously granted overlay permission and returns to the app,
        // make sure the service is running (e.g. after a process restart).
        if (Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }
    }

    // ── Socket service ────────────────────────────────────────────────────────

    /**
     * Requests POST_NOTIFICATIONS (Android 13+) then starts [SocketForegroundService].
     * On older OS versions starts the service directly.
     */
    private fun ensureSocketServiceRunning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                SocketForegroundService.start(this)
                ensureOverlayPermission()
            } else {
                // Permission request result → ensureOverlayPermission() in callback
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            SocketForegroundService.start(this)
            ensureOverlayPermission()
        }
    }

    // ── Overlay permission ────────────────────────────────────────────────────

    /**
     * Checks SYSTEM_ALERT_WINDOW permission.
     *   • Granted  → start [OverlayService] immediately.
     *   • Denied   → open the system Settings page so the user can grant it.
     *
     * The result is handled in [overlayPermissionResult] (launched when not granted)
     * and in [onResume] for subsequent app-opens where the user already granted.
     */
    private fun ensureOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        } else {
            Log.d(TAG, "Requesting SYSTEM_ALERT_WINDOW — opening Settings")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            overlayPermissionResult.launch(intent)
        }
    }

    // ── Accessibility permission ──────────────────────────────────────────────

    /**
     * The bubble's per-app appearance and the admin "Last App" column both depend
     * on [AppMonitorService] (an AccessibilityService). It can only be enabled by
     * the user from system Settings — nothing in the app prompted for it before, so
     * the foreground-app feed never started. If it isn't enabled yet, open the
     * Accessibility settings screen so the user can turn it on.
     *
     * Called from [onCreate] only (not [onResume]) to avoid re-launching Settings
     * on every return to the app. Once enabled, this no-ops.
     */
    private fun promptAccessibilityIfNeeded() {
        if (isAccessibilityServiceEnabled()) {
            Log.d(TAG, "AppMonitorService accessibility already enabled")
            return
        }
        Log.d(TAG, "AppMonitorService accessibility not enabled — opening Settings")
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open Accessibility settings", e)
        }
    }

    /** Returns true if [AppMonitorService] is listed in the enabled accessibility services. */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${AppMonitorService::class.java.name}"

        val accessibilityEnabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: SettingNotFoundException) {
            0
        }
        if (accessibilityEnabled != 1) return false

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
