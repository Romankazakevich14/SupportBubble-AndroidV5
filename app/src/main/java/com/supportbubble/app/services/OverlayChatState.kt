package com.supportbubble.app.services

import android.content.Context
import android.util.Log
import com.supportbubble.app.database.AppDatabase
import com.supportbubble.app.models.Message
import com.supportbubble.app.socket.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "OverlayChatState"

/**
 * State holder for the overlay chat panel.
 *
 * Mirrors the patterns used in [com.supportbubble.app.ui.ChatViewModel] but runs
 * outside of any Android ViewModel — it is owned directly by [OverlayService]
 * and lives for the duration of that service.
 *
 * ## Offline queue
 * Handled entirely by [ChatRepository]. When the device is offline, messages are
 * stored in Room with `pending = true` and flushed automatically on reconnect.
 *
 * ## Shared Room DB
 * The Room database and its message tables are shared with the main chat screen,
 * so the message history is always consistent between the two entry points.
 */
class OverlayChatState(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val deviceId: String = DeviceInfoService.getDeviceId(context)

    private val db = AppDatabase.getInstance(context)
    private val messageDao = db.messageDao()

    // ── Connection state ──────────────────────────────────────────────────────

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // ── Admin typing ──────────────────────────────────────────────────────────

    private val _adminTyping = MutableStateFlow(false)
    val adminTyping: StateFlow<Boolean> = _adminTyping.asStateFlow()

    // ── Input ─────────────────────────────────────────────────────────────────

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // ── Socket ────────────────────────────────────────────────────────────────

    /**
     * [SocketManager.onConnected] is set here; [ChatRepository] intercepts it
     * (via callback chaining in its `init` block) to add the offline-flush +
     * history-sync on every reconnect.
     */
    private val socketManager = SocketManager(deviceId).apply {
        onConnected = {
            _connected.value = true
            Log.d(TAG, "Overlay socket connected")
        }
        onDisconnected = {
            _connected.value = false
            Log.d(TAG, "Overlay socket disconnected")
        }
        onError = { err ->
            _connected.value = false
            Log.w(TAG, "Overlay socket error: $err")
        }
        onAdminTyping = { isTyping ->
            _adminTyping.value = isTyping
        }
    }

    // ── Repository ────────────────────────────────────────────────────────────

    private val repository = ChatRepository(
        deviceId = deviceId,
        messageDao = messageDao,
        socketManager = socketManager,
        scope = scope,
    )

    // ── Active per-app thread (Bug 5) ─────────────────────────────────────────

    /**
     * The package whose thread this overlay panel currently shows. Set by
     * [OverlayService] (via [setActiveApp]) to the foreground app the bubble was
     * tapped in, so the overlay only ever shows that app's conversation.
     */
    private val _activePackage = MutableStateFlow("")
    val activePackage: StateFlow<String> = _activePackage.asStateFlow()
    private var activeAppName: String = ""

    // ── Messages (filtered to the active thread) ──────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> = _activePackage
        .flatMapLatest { pkg -> repository.messagesForPackage(pkg) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Binds the panel to a specific app thread. Called before the panel is shown.
     */
    fun setActiveApp(packageName: String, appName: String) {
        _activePackage.value = packageName
        activeAppName = appName
        Log.d(TAG, "Overlay thread → pkg='$packageName' app='$appName'")
    }

    // ── Typing debounce ───────────────────────────────────────────────────────

    private var typingJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        socketManager.connect()
        // Device registration (idempotent — updates lastSeen / lastApp on server)
        scope.launch(Dispatchers.IO) {
            repository.registerDevice(DeviceInfoService.getDeviceInfo(context))
        }
        // History is fetched by ChatRepository on every successful connect —
        // no need to call it here separately.
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun onInputChanged(text: String) {
        _inputText.value = text
        typingJob?.cancel()
        if (text.isNotBlank()) {
            repository.emitTyping(true)
            typingJob = scope.launch {
                delay(1_500L)
                repository.emitTyping(false)
            }
        } else {
            repository.emitTyping(false)
        }
    }

    /**
     * Delegates fully to [ChatRepository.sendMessage].
     * The repository handles the Room insert and socket emit / offline queuing.
     */
    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return
        _inputText.value = ""
        typingJob?.cancel()
        repository.emitTyping(false)
        repository.sendMessage(text, _activePackage.value, activeAppName)
    }

    /** Mark admin messages as read — call when the chat panel becomes visible. */
    fun onChatVisible() {
        repository.markAsRead(_activePackage.value)
    }

    /** Disconnect the socket and cancel pending work. Call from [OverlayService.onDestroy]. */
    fun destroy() {
        typingJob?.cancel()
        socketManager.disconnect()
        Log.d(TAG, "OverlayChatState destroyed")
    }
}
