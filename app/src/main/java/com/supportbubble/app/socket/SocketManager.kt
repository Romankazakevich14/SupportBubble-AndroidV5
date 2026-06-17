package com.supportbubble.app.socket

import android.util.Log
import com.supportbubble.app.BuildConfig
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONObject
import java.net.URI

private const val TAG = "SocketManager"
private const val LOG_SOCKET_IN = "LOG_SOCKET_IN"
private const val LOG_SOCKET_OUT = "LOG_SOCKET_OUT"

data class IncomingMessage(
    val id: String,
    val deviceId: String,
    val text: String,
    val sender: String,
    val timestamp: Long,
    val read: Boolean,
    val packageName: String = "",
    val appName: String = "",
)

class SocketManager(private val deviceId: String) {

    private var socket: Socket? = null

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onNewMessage: ((IncomingMessage) -> Unit)? = null
    var onAdminTyping: ((Boolean) -> Unit)? = null
    var onMessagesRead: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isConnected: Boolean
        get() = socket?.connected() == true

    fun connect() {
        if (socket?.connected() == true) return

        try {
            val options = IO.Options().apply {
                path = BuildConfig.SOCKET_PATH
                query = "deviceId=$deviceId"
                transports = arrayOf(WebSocket.NAME)
                reconnection = true
                reconnectionDelay = 2000
                reconnectionDelayMax = 10000
                reconnectionAttempts = Int.MAX_VALUE
                // Dedicated connection per UI instance. Without this, the socket.io
                // Java client reuses one shared Manager/Socket (keyed by URL+nsp),
                // so disconnect() here would tear down AppSocket and the other
                // SocketManager, and onConnected would not fire when the shared
                // socket is already connected (history sync would never run).
                forceNew = true
                multiplex = false
            }

            socket = IO.socket(URI.create(BuildConfig.SERVER_URL), options).apply {
                on(Socket.EVENT_CONNECT, onConnectListener)
                on(Socket.EVENT_DISCONNECT, onDisconnectListener)
                on(Socket.EVENT_CONNECT_ERROR, onConnectErrorListener)
                on("new_message", onNewMessageListener)
                on("typing", onTypingListener)
                on("messages_read", onMessagesReadListener)
                connect()
            }

            Log.d(TAG, "Connecting to ${BuildConfig.SERVER_URL} as device $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create socket", e)
            onError?.invoke("Connection failed: ${e.message}")
        }
    }

    fun disconnect() {
        socket?.apply {
            off(Socket.EVENT_CONNECT, onConnectListener)
            off(Socket.EVENT_DISCONNECT, onDisconnectListener)
            off(Socket.EVENT_CONNECT_ERROR, onConnectErrorListener)
            off("new_message", onNewMessageListener)
            off("typing", onTypingListener)
            off("messages_read", onMessagesReadListener)
            disconnect()
        }
        socket = null
        Log.d(TAG, "Disconnected")
    }

    fun sendMessage(text: String, packageName: String = "", appName: String = "") {
        if (!isConnected) {
            onError?.invoke("Not connected to server")
            return
        }
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("text", text.trim())
            put("packageName", packageName)
            put("appName", appName)
        }
        socket?.emit("client_message", payload)
        Log.d(TAG, "Sent client_message: $text")
        Log.d(LOG_SOCKET_OUT, "client_message (pkg=$packageName) → $text")
    }

    fun emitTyping(isTyping: Boolean) {
        if (!isConnected) return
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("isTyping", isTyping)
        }
        socket?.emit("typing", payload)
    }

    /**
     * Marks messages read on the server. When [packageName] is non-null the read
     * receipt is scoped to that per-app thread; when null the whole device is
     * marked read (legacy behaviour).
     */
    fun emitRead(packageName: String? = null) {
        if (!isConnected) return
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            if (packageName != null) put("packageName", packageName)
        }
        socket?.emit("read", payload)
    }

    private val onConnectListener = Emitter.Listener {
        Log.d(TAG, "Connected — socket id: ${socket?.id()}")
        onConnected?.invoke()
    }

    private val onDisconnectListener = Emitter.Listener { args ->
        val reason = args.firstOrNull()?.toString() ?: "unknown"
        Log.d(TAG, "Disconnected: $reason")
        onDisconnected?.invoke()
    }

    private val onConnectErrorListener = Emitter.Listener { args ->
        val error = args.firstOrNull()?.toString() ?: "unknown error"
        Log.e(TAG, "Connection error: $error")
        onError?.invoke(error)
    }

    private val onNewMessageListener = Emitter.Listener { args ->
        try {
            val data = args[0] as JSONObject
            val msg = data.getJSONObject("message")
            val msgDeviceId = data.optString("deviceId", deviceId)

            val incoming = IncomingMessage(
                id = msg.optString("_id", msg.optString("id", System.currentTimeMillis().toString())),
                deviceId = msgDeviceId,
                text = msg.getString("text"),
                sender = msg.getString("sender"),
                timestamp = parseTimestamp(msg.optString("timestamp")),
                read = msg.optBoolean("read", false),
                packageName = msg.optString("packageName", ""),
                appName = msg.optString("appName", ""),
            )
            Log.d(TAG, "new_message from ${incoming.sender}: ${incoming.text}")
            Log.d(LOG_SOCKET_IN, "new_message from ${incoming.sender}: ${incoming.text}")
            onNewMessage?.invoke(incoming)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse new_message", e)
        }
    }

    private val onTypingListener = Emitter.Listener { args ->
        try {
            val data = args[0] as JSONObject
            val from = data.optString("from", "")
            if (from == "admin") {
                val isTyping = data.optBoolean("isTyping", false)
                onAdminTyping?.invoke(isTyping)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse typing event", e)
        }
    }

    private val onMessagesReadListener = Emitter.Listener {
        Log.d(TAG, "messages_read received")
        onMessagesRead?.invoke()
    }

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
