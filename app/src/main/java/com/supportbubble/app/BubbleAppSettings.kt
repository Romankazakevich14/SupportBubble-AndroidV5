package com.supportbubble.app

/**
 * Per-app bubble display settings — mirrors the backend [IBubbleAppSettings] interface.
 *
 * ## Defaults
 * All fields have defaults matching the server defaults so that apps not
 * explicitly configured behave identically to the pre-customisation era.
 *
 * ## [bubbleIcon] values
 * - `"app_icon"`    → use [android.content.pm.PackageManager] icon (default)
 * - `"support"` | `"chat"` | `"help"` | `"telegram"` | `"whatsapp"` |
 *   `"instagram"` | `"facebook"` → built-in named icon
 * - `"data:image/png;base64,..."` → custom PNG uploaded by the admin
 */
data class BubbleAppSettings(
    val packageName: String = "",
    val enabled: Boolean = true,
    val bubbleText: String = "Support",
    val bubbleIcon: String = "app_icon",
    val bubbleColor: String = "#5C6BC0",
    val bubbleSize: Int = 80,          // dp, clamped to [60, 120] in OverlayService
)

/** Shared defaults — use when a package has no explicit entry in the settings map. */
val DEFAULT_BUBBLE_SETTINGS = BubbleAppSettings()
