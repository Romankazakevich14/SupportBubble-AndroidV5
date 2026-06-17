package com.supportbubble.app.services

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.supportbubble.app.BuildConfig
import com.supportbubble.app.database.MessageDao
import com.supportbubble.app.database.MessageEntity
import com.supportbubble.app.models.DeviceInfo
import com.supportbubble.app.models.Message
import com.supportbubble.app.socket.IncomingMessage
import com.supportbubble.app.socket.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "ChatRepository"
private const val LOG_CHAT_SESSION = "LOG_CHAT_SESSION"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * Single source of truth for chat data.
 *
 * ## Offline queue
 *
 * When the socket is disconnected, [sendMessage] inserts the message into Room
 * with `pending = true`.  When the socket reconnects, [handleConnected] is
 * triggered automatically via the [SocketManager.onConnected] callback chain:
 * it first flushes all pending messages in chronological order, then re-fetches
 * the message history to catch any incoming messages that arrived while offline.
 *
 * ## Callback chaining
 *
 * The caller (ChatViewModel / OverlayChatState) sets [SocketManager.onConnected]
 * before passing the manager to this repository.  During [init], this repository
 * captures that upstream callback and wraps it so both the caller's state update
 * AND the repository's reconnect logic run on every connect event.
 */
class ChatRepository(
    private val deviceId: String,
    private val messageDao: MessageDao,
    private val socketManager: SocketManager,
    private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /** Live stream of all messages for this device, sorted oldest → newest. */
    val messages: Flow<List<Message>> = messageDao
        .observeMessages(deviceId)
        .map { entities -> entities.map { it.toMessage() } }

    /**
     * Live stream for a single per-app thread (Bug 5). [packageName] == "" is the
     * general thread. Sorted oldest → newest.
     */
    fun messagesForPackage(packageName: String): Flow<List<Message>> = messageDao
        .observeMessagesForPackage(deviceId, packageName)
        .map { entities -> entities.map { it.toMessage() } }

    // ── Init: wire socket callbacks ───────────────────────────────────────────

    init {
        // Persist every admin message that arrives via socket
        socketManager.onNewMessage = { incoming -> persistIncoming(incoming) }

        // Wrap the upstream onConnected callback so BOTH the caller's state update
        // and the repository's offline-sync logic run on every successful connect.
        val upstreamOnConnected = socketManager.onConnected
        socketManager.onConnected = {
            upstreamOnConnected?.invoke()
            handleConnected()
        }
    }

    // ── Reconnect handler ─────────────────────────────────────────────────────

    /**
     * Called on every successful socket (re)connect.
     *
     * 1. Flush pending outgoing messages (offline queue).
     * 2. Fetch the full server history to pull in any messages that arrived
     *    while the device was offline.
     */
    private fun handleConnected() {
        scope.launch(Dispatchers.IO) {
            flushPendingMessages()
            fetchHistory()
        }
    }

    // ── Outgoing messages ─────────────────────────────────────────────────────

    /**
     * Sends a message.
     *
     * Always inserts the message into Room immediately so the UI updates
     * without waiting for the network.  If the socket is not connected,
     * the message is marked `pending = true` and will be delivered by
     * [flushPendingMessages] on the next successful connection.
     */
    fun sendMessage(text: String, packageName: String = "", appName: String = "") {
        val isOnline = socketManager.isConnected
        val entity = MessageEntity(
            id = "local-${UUID.randomUUID()}",
            deviceId = deviceId,
            text = text,
            sender = "client",
            timestamp = System.currentTimeMillis(),
            read = false,
            pending = !isOnline,
            packageName = packageName,
            appName = appName,
        )
        scope.launch(Dispatchers.IO) {
            messageDao.insertMessage(entity)
        }
        if (isOnline) {
            socketManager.sendMessage(text, packageName, appName)
            Log.d(TAG, "Sent immediately: $text")
            Log.d(LOG_CHAT_SESSION, "send → pkg='$packageName' app='$appName': $text")
        } else {
            Log.d(TAG, "Offline — queued: $text")
            Log.d(LOG_CHAT_SESSION, "queued (offline) → pkg='$packageName' app='$appName': $text")
        }
    }

    fun emitTyping(isTyping: Boolean) {
        socketManager.emitTyping(isTyping)
    }

    fun markAsRead() {
        socketManager.emitRead()
        scope.launch(Dispatchers.IO) {
            messageDao.markAdminMessagesRead(deviceId)
        }
    }

    /** Marks admin messages read for a single per-app thread (Bug 5). */
    fun markAsRead(packageName: String) {
        socketManager.emitRead(packageName)
        scope.launch(Dispatchers.IO) {
            messageDao.markAdminMessagesReadForPackage(deviceId, packageName)
        }
    }

    // ── Offline queue ─────────────────────────────────────────────────────────

    /**
     * Sends all messages that were stored while offline, in chronological order.
     *
     * Each message is emitted to the server and immediately marked delivered
     * (`pending = false`) in Room so the UI removes the "Sending…" indicator.
     * A short delay between messages prevents flooding the server.
     */
    private suspend fun flushPendingMessages() {
        val pending = messageDao.getPendingMessages(deviceId)
        if (pending.isEmpty()) return

        Log.d(TAG, "Flushing ${pending.size} pending message(s)")
        pending.forEach { msg ->
            socketManager.sendMessage(msg.text, msg.packageName, msg.appName)
            messageDao.setPendingStatus(msg.id, false)
            Log.d(LOG_CHAT_SESSION, "flush → pkg='${msg.packageName}': ${msg.text}")
            delay(80L)          // modest back-pressure
        }
        Log.d(TAG, "Flush complete")
    }

    // ── Device registration ───────────────────────────────────────────────────

    /**
     * Register / update device info on the server (unauthenticated REST endpoint).
     * Idempotent — safe to call on every app launch.
     */
    suspend fun registerDevice(info: DeviceInfo) {
        val body = gson.toJson(
            mapOf(
                "deviceId" to info.deviceId,
                "phoneModel" to info.phoneModel,
                "androidVersion" to info.androidVersion,
                "lastApp" to info.lastApp,
            )
        ).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("${BuildConfig.SERVER_URL}/api/users")
            .post(body)
            .build()

        try {
            http.newCall(request).execute().use { response ->
                Log.d(TAG, "registerDevice: HTTP ${response.code}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "registerDevice failed (will retry on next launch)", e)
        }
    }

    // ── History sync ──────────────────────────────────────────────────────────

    /**
     * Fetches the full message history from the server and upserts it into Room.
     *
     * Called on every successful (re)connect so messages that arrived while the
     * device was offline are populated into the local database automatically.
     *
     * Room's [OnConflictStrategy.REPLACE] ensures existing messages are updated
     * rather than duplicated.
     */
    suspend fun fetchHistory() {
        val request = Request.Builder()
            .url("${BuildConfig.SERVER_URL}/api/chats/$deviceId")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body?.string() ?: return
                val json = gson.fromJson(body, JsonObject::class.java)

                if (json.get("success")?.asBoolean != true) return

                val data = json.getAsJsonObject("data") ?: return
                val messagesArr = data.getAsJsonArray("messages") ?: return

                val entities = messagesArr.mapNotNull { el ->
                    try {
                        val obj = el.asJsonObject
                        MessageEntity(
                            id = obj.get("_id")?.asString ?: return@mapNotNull null,
                            deviceId = deviceId,
                            text = obj.get("text")?.asString ?: return@mapNotNull null,
                            sender = obj.get("sender")?.asString ?: "client",
                            timestamp = parseIsoTimestamp(obj.get("timestamp")?.asString),
                            read = obj.get("read")?.asBoolean ?: false,
                            pending = false,    // server messages are always delivered
                            packageName = obj.get("packageName")?.takeUnless { it.isJsonNull }?.asString ?: "",
                            appName = obj.get("appName")?.takeUnless { it.isJsonNull }?.asString ?: "",
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse message from history", e)
                        null
                    }
                }

                if (entities.isNotEmpty()) {
                    messageDao.insertMessages(entities)
                    Log.d(TAG, "fetchHistory: upserted ${entities.size} message(s)")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchHistory failed — will retry on next reconnect", e)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun persistIncoming(incoming: IncomingMessage) {
        scope.launch(Dispatchers.IO) {
            val entity = MessageEntity(
                id = incoming.id,
                deviceId = incoming.deviceId,
                text = incoming.text,
                sender = incoming.sender,
                timestamp = incoming.timestamp,
                read = incoming.read,
                pending = false,   // messages from the server are already delivered
                packageName = incoming.packageName,
                appName = incoming.appName,
            )
            messageDao.insertMessage(entity)
            Log.d(LOG_CHAT_SESSION, "recv → pkg='${incoming.packageName}' from ${incoming.sender}: ${incoming.text}")
        }
    }

    private fun parseIsoTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return System.currentTimeMillis()
        // Try formats in decreasing specificity. MongoDB returns `.000Z` most of
        // the time, but a bare-second format `2024-01-01T12:00:00Z` is common
        // from other serialisers. The literal 'Z' in SimpleDateFormat is NOT a
        // timezone specifier — it matches the character 'Z' literally, which is
        // correct for UTC ISO-8601 strings emitted by MongoDB/Node.js.
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
