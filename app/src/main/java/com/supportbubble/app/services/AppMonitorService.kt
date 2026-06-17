package com.supportbubble.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.supportbubble.app.AppState
import com.supportbubble.app.socket.AppSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AppMonitorService"
private const val LOG_ACCESSIBILITY = "LOG_ACCESSIBILITY"
private const val LOG_CURRENT_APP = "LOG_CURRENT_APP"

/**
 * Debounce window: wait this many ms after the last event before treating
 * the change as a stable foreground app switch. This filters out transient
 * system-overlay windows that flash between apps.
 */
private const val DEBOUNCE_MS = 800L

/**
 * System-owned packages whose window changes we always ignore.
 * Also filtered: packages that start with "android." (framework windows).
 */
private val IGNORED_PACKAGES = setOf(
    "com.android.systemui",
    "com.android.launcher",
    "com.android.launcher2",
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.google.android.apps.taskbar",
    "com.huawei.android.launcher",
    "com.miui.home",
    "com.sec.android.app.launcher",
    "com.oppo.launcher",
    "com.vivo.launcher",
    "com.oneplus.launcher",
    "com.android.inputmethod.latin",
    "com.google.android.inputmethod.latin",
    "com.samsung.android.app.spage",
    "com.android.settings",
)

class AppMonitorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var debounceJob: Job? = null

    /** Last confirmed foreground package (after debounce). */
    private var currentApp: String = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.also { info ->
            // Foreground switches are authoritatively signalled by
            // TYPE_WINDOW_STATE_CHANGED; TYPE_WINDOWS_CHANGED is kept only as a
            // recovery signal for split-screen / PIP transitions.
            //
            // TYPE_WINDOW_CONTENT_CHANGED is deliberately NOT subscribed: it fires
            // many times per second while an app updates its UI, and resolving it
            // via rootInActiveWindow intermittently returned our own overlay's
            // package — flipping currentApp between the real app and SupportBubble
            // and making the bubble blink ~1×/sec (the "мигает" bug).
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            // FLAG_RETRIEVE_INTERACTIVE_WINDOWS + canRetrieveWindowContent (XML) let us
            // read rootInActiveWindow to resolve the genuinely-active package when an
            // event's own packageName is unreliable.
            info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        // Initialise the shared socket so this service can emit events
        AppSocket.init(applicationContext)

        Log.d(TAG, "AppMonitorService connected")
        Log.d(LOG_ACCESSIBILITY, "AccessibilityService connected — monitoring foreground apps")
    }

    override fun onInterrupt() {
        Log.d(TAG, "AppMonitorService interrupted")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "AppMonitorService destroyed")
    }

    // ── Event handling ────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val pkg = resolveForegroundPackage(event) ?: return
        if (pkg.isBlank()) return
        if (pkg == currentApp) return

        // Own app in foreground: flip immediately (no debounce) so the bubble hides
        // while the user is inside SupportBubble itself (Bug 6). We deliberately do
        // NOT persist or emit our own package as a "tracked" foreground app.
        //
        // Only a genuine WINDOW_STATE_CHANGED with our own package counts as
        // "SupportBubble is foreground". rootInActiveWindow (used by the recovery
        // path for WINDOWS_CHANGED) can transiently report our overlay window's
        // package even while the user is in another app — flipping here on that
        // signal is exactly what caused the bubble to blink, so we ignore it.
        if (pkg == applicationContext.packageName) {
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            debounceJob?.cancel()
            currentApp = pkg
            AppState.updateCurrentApp(pkg)
            Log.i(LOG_CURRENT_APP, "Foreground is SupportBubble itself — bubble suppressed")
            return
        }

        if (shouldIgnore(pkg)) return

        // Debounce: cancel any pending job and schedule a new one
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            handleAppChange(pkg)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the package that is genuinely in the foreground for this event.
     *
     * For TYPE_WINDOW_STATE_CHANGED the event's own packageName is the most
     * reliable signal. For the other (recovery) event types the event package can
     * be a transient/overlay window, so we fall back to the active window's root
     * package — this is what keeps currentApp fresh after a missed state change.
     */
    private fun resolveForegroundPackage(event: AccessibilityEvent): String? {
        val eventPkg = event.packageName?.toString()?.trim()
        val own = applicationContext.packageName

        // WINDOW_STATE_CHANGED: the event's own packageName is authoritative — it is
        // the only signal we trust to report that SupportBubble itself is foreground.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !eventPkg.isNullOrBlank()
        ) {
            return eventPkg
        }

        // Recovery path (WINDOWS_CHANGED): use the active window's root package, but
        // NEVER report our own overlay window as the foreground app — that false
        // positive is what made the bubble blink. Fall back to the event package
        // only when it is some other real app.
        val activePkg = activeWindowPackage()
        if (!activePkg.isNullOrBlank() && activePkg != own) return activePkg
        if (!eventPkg.isNullOrBlank() && eventPkg != own) return eventPkg

        return null
    }

    /** Reads the package of the currently-active accessibility window, if any. */
    private fun activeWindowPackage(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()?.trim()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read active window package", e)
            null
        }
    }

    private fun shouldIgnore(pkg: String): Boolean {
        if (pkg == applicationContext.packageName) return true
        if (pkg.startsWith("android.")) return true
        return IGNORED_PACKAGES.any { pkg.startsWith(it) }
    }

    private fun handleAppChange(packageName: String) {
        currentApp = packageName

        // 1. Notify OverlayService (updates the bubble icon in real time)
        AppState.updateCurrentApp(packageName)

        // 2. Persist locally so DeviceInfo carries the latest value on next launch
        DeviceInfoService.saveLastApp(applicationContext, packageName)

        // 3. Emit to server via Socket.io
        AppSocket.emitAppChange(packageName)

        Log.i(TAG, "Foreground app → $packageName")
        Log.i(LOG_ACCESSIBILITY, "Foreground app → $packageName")
        Log.i(LOG_CURRENT_APP, "currentApp updated → $packageName")
    }
}
