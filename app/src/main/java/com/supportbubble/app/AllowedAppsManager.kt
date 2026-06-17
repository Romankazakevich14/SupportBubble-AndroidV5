package com.supportbubble.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.supportbubble.app.BuildConfig
import com.supportbubble.app.socket.AppSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "AllowedAppsManager"
private const val PREFS_NAME = "support_bubble_prefs"
private const val KEY_BUBBLE_SETTINGS = "bubble_settings_v2"  // JSON or "null"

/**
 * Manages per-app bubble display settings for all installed apps.
 *
 * ## State semantics
 * - [settings] == null  → not yet configured; every app uses [DEFAULT_BUBBLE_SETTINGS]
 * - [settings] == empty → no apps explicitly configured; same as null (defaults apply)
 * - [settings] == map   → each entry customises one app; absent apps use defaults
 *
 * An entry with `enabled = false` hides the bubble for that package.
 *
 * ## Real-time updates
 * The server pushes `bubble_settings_updated` via Socket.io whenever an admin
 * changes settings. [AppSocket.onBubbleSettingsUpdated] wires this to [update].
 *
 * ## Persistence
 * Settings are cached in [SharedPreferences] so they survive process death and
 * are available immediately on the next launch before the network fetch completes.
 */
object AllowedAppsManager {

    private val _settings = MutableStateFlow<Map<String, BubbleAppSettings>?>(null)

    /** Observed by [com.supportbubble.app.services.OverlayService]. */
    val settings: StateFlow<Map<String, BubbleAppSettings>?> = _settings.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Returns the effective settings for [packageName].
     * Falls back to [DEFAULT_BUBBLE_SETTINGS] when no explicit entry exists.
     */
    fun getSettings(packageName: String): BubbleAppSettings =
        _settings.value?.get(packageName) ?: DEFAULT_BUBBLE_SETTINGS.copy(packageName = packageName)

    /**
     * True only if the bubble is EXPLICITLY enabled for [packageName].
     *
     * Defaults to FALSE for unconfigured packages and when settings have not
     * loaded yet. This is the core fix for Bug 1/4: the bubble must stay hidden
     * unless an admin has turned it on for the current app. The previous default
     * (true) made the bubble appear everywhere and made the enable checkboxes
     * appear to do nothing.
     */
    fun isBubbleAllowed(packageName: String): Boolean =
        _settings.value?.get(packageName)?.enabled ?: false

    // ── Init ──────────────────────────────────────────────────────────────────

    /**
     * Load cached settings from SharedPreferences and wire the Socket.io callback.
     * Call once from [com.supportbubble.app.SupportBubbleApp.onCreate].
     */
    fun init(context: Context) {
        load(context)
        AppSocket.onBubbleSettingsUpdated = { map ->
            Log.d(TAG, "Real-time bubble_settings_updated: ${map?.size ?: "null"} entries")
            update(context, map)
        }
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /** Update in-memory state and persist to SharedPreferences. */
    fun update(context: Context, map: Map<String, BubbleAppSettings>?) {
        _settings.value = map
        save(context, map)
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun save(context: Context, map: Map<String, BubbleAppSettings>?) {
        val json = if (map == null) "null" else gson.toJson(map.values.toList())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BUBBLE_SETTINGS, json)
            .apply()
    }

    private fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BUBBLE_SETTINGS, "null") ?: "null"

        _settings.value = when (raw) {
            "null" -> null
            else -> {
                try {
                    val type = object : TypeToken<List<BubbleAppSettings>>() {}.type
                    val list: List<BubbleAppSettings> = gson.fromJson(raw, type)
                    list.associateBy { it.packageName }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse saved bubble settings — resetting", e)
                    null
                }
            }
        }
        Log.d(TAG, "Loaded: ${_settings.value?.size ?: "null (defaults)"} entries")
    }

    // ── Network fetch ─────────────────────────────────────────────────────────

    /**
     * Fetches bubble settings from the backend.
     * Called on launch and every [ALLOWED_APPS_POLL_INTERVAL_MS] from [SupportBubbleApp].
     * Silently no-ops on network failure.
     */
    suspend fun fetchFromServer(deviceId: String, context: Context) {
        val request = Request.Builder()
            .url("${BuildConfig.SERVER_URL}/api/bubble/settings/$deviceId")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body?.string() ?: return
                val json = JSONObject(body)
                if (!json.optBoolean("success")) return

                val data = json.optJSONObject("data") ?: return
                val map = parseBubbleSettings(data)

                Log.d(TAG, "Fetched: ${map?.size ?: "null (defaults)"} entries")
                update(context, map)
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchFromServer failed — using cached state", e)
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parseBubbleSettings(data: JSONObject): Map<String, BubbleAppSettings>? {
        if (data.isNull("allowedBubbleApps")) return null
        val arr: JSONArray = data.getJSONArray("allowedBubbleApps")
        return buildMap {
            repeat(arr.length()) { i ->
                val obj: JSONObject = arr.getJSONObject(i)
                val pkg = obj.getString("packageName")
                put(pkg, BubbleAppSettings(
                    packageName = pkg,
                    enabled     = obj.optBoolean("enabled", true),
                    bubbleText  = obj.optString("bubbleText", DEFAULT_BUBBLE_SETTINGS.bubbleText),
                    bubbleIcon  = obj.optString("bubbleIcon", DEFAULT_BUBBLE_SETTINGS.bubbleIcon),
                    bubbleColor = obj.optString("bubbleColor", DEFAULT_BUBBLE_SETTINGS.bubbleColor),
                    bubbleSize  = obj.optInt("bubbleSize", DEFAULT_BUBBLE_SETTINGS.bubbleSize),
                ))
            }
        }
    }
}
