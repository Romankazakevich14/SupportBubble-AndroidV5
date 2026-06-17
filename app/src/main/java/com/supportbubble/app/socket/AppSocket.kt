package com.supportbubble.app.socket

import android.content.Context
import android.util.Log
import com.supportbubble.app.BubbleAppSettings
import com.supportbubble.app.BuildConfig
import com.supportbubble.app.DEFAULT_BUBBLE_SETTINGS
import com.supportbubble.app.database.AppDatabase
import com.supportbubble.app.database.MessageDao
import com.supportbubble.app.database.MessageEntity
import com.supportbubble.app.services.DeviceInfoService
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Application-level Socket.io singleton shared by [SocketForegroundService],
 * [AppMonitorService], and the UI layer.
 *
 * ## Real-time events received from the server
 *
 * | Event                    | Payload                                         | Handler                  |
 * |--------------------------|-------------------------------------------------|--------------------------|
 * | `new_message`            | `{ deviceId, message: {...} }`                  | [handleNewMessage]       |
 * | `bubble_settings_updated`| `{ allowedBubbleApps: [...] \| null }`          | [handleBubbleSettingsUpdated] |
 *
 * ## Why this socket persists `new_message` to Room itself
 *
 * This is the ONLY socket that is alive for the whole process lifetime (kept up by
 * [SocketForegroundService]). The per-UI [SocketManager]s only exist while a chat
 * screen / overlay panel is open. To guarantee admin → device messages are saved
 * and shown instantly regardless of which (if any) UI is open, AppSocket writes
 * every incoming `new_message` to the shared Room DB. Room's REPLACE-on-conflict
 * (keyed by the server `_id`) deduplicates against any SocketManager that also
 * persists the same message.
 *
 * ## Independent physical connection (forceNew + multiplex=false)
 *
 * The socket.io Java client caches a Manager by URL and reuses the same Socket for
 * a given namespace. Without `forceNew`/`multiplex=false`, AppSocket and every
 * [SocketManager] would share ONE underlying Socket — so closing a chat screen
 * (`SocketManager.disconnect()`) would tear down AppSocket and strip its event
 * listeners. We force a dedicated connection so AppSocket is fully isolated.
 *
 * Lifecycle:
 *   - Call [init] once (from Application.onCreate or SocketForegroundService.onCreate).
 *   - The socket stays alive until [shutdown] is called.
 *   - socket.io-client handles automatic reconnection; [reconnect] is a safety net.
 */
object AppSocket {

    private const val TAG = "AppSocket"

    // ── Logcat tags (grep-friendly, as requested for diagnostics) ──────────────
    private const val LOG_SOCKET_IN = "LOG_SOCKET_IN"
    private const val LOG_SOCKET_OUT = "LOG_SOCKET_OUT"

    private var socket: Socket? = null
    private var deviceId: String = ""

    // ── Room (for always-on new_message persistence) ──────────────────────────
    private var messageDao: MessageDao? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Connection state ──────────────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()
    val isConnected: Boolean get() = socket?.connected() == true

    // ── Callbacks set by AllowedAppsManager ───────────────────────────────────

    /**
     * Called when the server pushes a `bubble_settings_updated` event.
     * Payload: `Map<String, BubbleAppSettings>?`
     *   null → all packages reset to defaults
     *   map  → per-package settings (absent packages use defaults)
     */
    var onBubbleSettingsUpdated: ((Map<String, BubbleAppSettings>?) -> Unit)? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (socket != null) return
        deviceId = DeviceInfoService.getDeviceId(context)
        messageDao = AppDatabase.getInstance(context).messageDao()
        connect()
    }

    private fun connect() {
        try {
            val opts = IO.Options().apply {
                path = BuildConfig.SOCKET_PATH
                query = "deviceId=$deviceId"
                transports = arrayOf(WebSocket.NAME)
                reconnection = true
                reconnectionDelay = 3_000
                reconnectionDelayMax = 30_000
                reconnectionAttempts = Int.MAX_VALUE
                // Dedicated connection — never share the Manager/Socket with the
                // per-UI SocketManagers (see class KDoc).
                forceNew = true
                multiplex = false
            }

            socket = IO.socket(URI.create(BuildConfig.SERVER_URL), opts).apply {
                on(Socket.EVENT_CONNECT) {
                    _isConnected.value = true
                    Log.d(TAG, "Connected — id: ${id()}")
                    Log.d(LOG_SOCKET_IN, "AppSocket connected (id=${id()}) for device=$deviceId")
                }
                on(Socket.EVENT_DISCONNECT) { args ->
                    _isConnected.value = false
                    Log.d(TAG, "Disconnected: ${args.firstOrNull()}")
                    Log.d(LOG_SOCKET_IN, "AppSocket disconnected: ${args.firstOrNull()}")
                }
                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    _isConnected.value = false
                    Log.w(TAG, "Connect error: ${args.firstOrNull()}")
                    Log.w(LOG_SOCKET_IN, "AppSocket connect error: ${args.firstOrNull()}")
                }
                on("new_message") { args ->
                    handleNewMessage(args)
                }
                on("bubble_settings_updated") { args ->
                    handleBubbleSettingsUpdated(args)
                }
                connect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create socket", e)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun reconnect() {
        val s = socket
        if (s == null) { connect(); return }
        if (!s.connected()) s.connect()
    }

    fun emitAppChange(packageName: String) {
        if (socket?.connected() != true) {
            Log.d(LOG_SOCKET_OUT, "Skipped app_change (socket not connected): $packageName")
            return
        }
        try {
            socket?.emit("app_change", JSONObject().apply {
                put("deviceId", deviceId)
                put("packageName", packageName)
            })
            Log.d(TAG, "emitAppChange → $packageName")
            Log.d(LOG_SOCKET_OUT, "app_change → $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "emitAppChange failed", e)
            Log.e(LOG_SOCKET_OUT, "app_change failed: ${e.message}")
        }
    }

    fun shutdown() {
        socket?.disconnect()
        socket = null
        _isConnected.value = false
        Log.d(TAG, "AppSocket shut down")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Parses an incoming `new_message` payload and writes it to the shared Room DB.
     *
     * ```json
     * { "deviceId": "abc", "message": { "_id": "...", "text": "...",
     *   "sender": "admin", "timestamp": "2024-01-01T12:00:00.000Z", "read": false } }
     * ```
     *
     * REPLACE-on-conflict (keyed by `_id`) means duplicate deliveries — e.g. the
     * same message also persisted by an open [SocketManager] — collapse into one row.
     */
    private fun handleNewMessage(args: Array<Any?>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val msg = data.optJSONObject("message") ?: return
            val msgDeviceId = data.optString("deviceId", deviceId)

            val id = msg.optString("_id", msg.optString("id", System.currentTimeMillis().toString()))
            val text = msg.optString("text", "")
            val sender = msg.optString("sender", "admin")
            val timestamp = parseTimestamp(msg.optString("timestamp"))
            val read = msg.optBoolean("read", false)
            val packageName = msg.optString("packageName", "")
            val appName = msg.optString("appName", "")

            Log.d(LOG_SOCKET_IN, "new_message from $sender (device=$msgDeviceId, pkg=$packageName): $text")

            val dao = messageDao
            if (dao == null) {
                Log.w(LOG_SOCKET_IN, "new_message dropped — Room not initialised")
                return
            }

            ioScope.launch {
                dao.insertMessage(
                    MessageEntity(
                        id = id,
                        deviceId = msgDeviceId,
                        text = text,
                        sender = sender,
                        timestamp = timestamp,
                        read = read,
                        pending = false,   // already delivered by the server
                        packageName = packageName,
                        appName = appName,
                    )
                )
                Log.d(LOG_SOCKET_IN, "new_message persisted to Room (id=$id)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle new_message", e)
            Log.e(LOG_SOCKET_IN, "Failed to handle new_message: ${e.message}")
        }
    }

    /**
     * Parses a `bubble_settings_updated` payload.
     *
     * ```json
     * { "allowedBubbleApps": null }             // all defaults
     * { "allowedBubbleApps": [] }               // no explicit settings
     * { "allowedBubbleApps": [{ "packageName": "com.example", "enabled": false, ... }] }
     * ```
     */
    private fun handleBubbleSettingsUpdated(args: Array<Any?>) {
        try {
            val json = args.firstOrNull() as? JSONObject ?: return

            val map: Map<String, BubbleAppSettings>? = if (json.isNull("allowedBubbleApps")) {
                null
            } else {
                val arr: JSONArray = json.getJSONArray("allowedBubbleApps")
                buildMap {
                    repeat(arr.length()) { i ->
                        val obj: JSONObject = arr.getJSONObject(i)
                        val pkg = obj.getString("packageName")
                        put(pkg, BubbleAppSettings(
                            packageName = pkg,
                            enabled     = obj.optBoolean("enabled", true),
                            bubbleText  = obj.optString("bubbleText",  DEFAULT_BUBBLE_SETTINGS.bubbleText),
                            bubbleIcon  = obj.optString("bubbleIcon",  DEFAULT_BUBBLE_SETTINGS.bubbleIcon),
                            bubbleColor = obj.optString("bubbleColor", DEFAULT_BUBBLE_SETTINGS.bubbleColor),
                            bubbleSize  = obj.optInt("bubbleSize",     DEFAULT_BUBBLE_SETTINGS.bubbleSize),
                        ))
                    }
                }
            }

            Log.d(TAG, "bubble_settings_updated: ${map?.size ?: "null"} entries")
            Log.d(LOG_SOCKET_IN, "bubble_settings_updated: ${map?.size ?: "null"} entries")
            onBubbleSettingsUpdated?.invoke(map)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bubble_settings_updated", e)
            Log.e(LOG_SOCKET_IN, "Failed to parse bubble_settings_updated: ${e.message}")
        }
    }

    /** Parses MongoDB/Node ISO-8601 timestamps; falls back to now on failure. */
    private fun parseTimestamp(raw: String): Long {
        if (raw.isBlank()) return System.currentTimeMillis()
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (fmt in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                return sdf.parse(raw)?.time ?: continue
            } catch (_: Exception) { /* try next */ }
        }
        return System.currentTimeMillis()
    }
}
