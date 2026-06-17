package com.supportbubble.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.supportbubble.app.database.AppDatabase
import com.supportbubble.app.models.ChatSession
import com.supportbubble.app.models.Message
import com.supportbubble.app.services.ChatRepository
import com.supportbubble.app.services.DeviceInfoService
import com.supportbubble.app.socket.SocketManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "ChatViewModel"

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    val deviceId: String = DeviceInfoService.getDeviceId(context)
    val deviceInfo = DeviceInfoService.getDeviceInfo(context)

    private val db = AppDatabase.getInstance(context)
    private val messageDao = db.messageDao()

    // ── Socket ────────────────────────────────────────────────────────────────

    private val socketManager = SocketManager(deviceId).apply {
        // onConnected is intercepted by ChatRepository.init to also trigger
        // the offline queue flush + history sync on every reconnect.
        onConnected = {
            _connectionState.value = ConnectionState.CONNECTED
            Log.d(TAG, "Socket connected")
        }
        onDisconnected = {
            _connectionState.value = ConnectionState.DISCONNECTED
            Log.d(TAG, "Socket disconnected")
        }
        onAdminTyping = { isTyping ->
            _adminTyping.value = isTyping
        }
        onMessagesRead = {
            // Admin acknowledged our messages — no UI action needed for now
        }
        onError = { error ->
            Log.e(TAG, "Socket error: $error")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    // ── Repository ────────────────────────────────────────────────────────────

    /**
     * ChatRepository intercepts [SocketManager.onConnected] at construction time
     * to chain the offline-queue flush and history re-fetch onto every reconnect.
     * Device registration is done once here since this ViewModel persists for the
     * lifetime of MainActivity.
     */
    private val repository = ChatRepository(
        deviceId = deviceId,
        messageDao = messageDao,
        socketManager = socketManager,
        scope = viewModelScope,
    )

    // ── Observable state ──────────────────────────────────────────────────────

    /** All messages for this device (used to derive the per-app session list). */
    val messages: StateFlow<List<Message>> = repository.messages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Per-app sessions (Bug 5) ──────────────────────────────────────────────

    /**
     * The list of per-app conversation threads, newest activity first. A synthetic
     * "General" thread (packageName == "") is always present so the in-app chat has
     * an entry point even before any app-specific messages exist.
     */
    val sessions: StateFlow<List<ChatSession>> = repository.messages
        .map { buildSessions(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            listOf(generalSession(emptyList())),
        )

    private val _selectedPackage = MutableStateFlow<String?>(null)
    val selectedPackage: StateFlow<String?> = _selectedPackage.asStateFlow()
    private var selectedAppName: String = ""

    /** Messages for the currently-open thread; empty when no thread is selected. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val threadMessages: StateFlow<List<Message>> = _selectedPackage
        .flatMapLatest { pkg ->
            if (pkg == null) flowOf(emptyList()) else repository.messagesForPackage(pkg)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _adminTyping = MutableStateFlow(false)
    val adminTyping: StateFlow<Boolean> = _adminTyping.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private var typingJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        socketManager.connect()
        // Register device info once per ViewModel lifetime.
        // History is fetched by ChatRepository on every successful connect.
        viewModelScope.launch(Dispatchers.IO) {
            repository.registerDevice(deviceInfo)
        }
    }

    // ── User actions ──────────────────────────────────────────────────────────

    fun onInputChanged(text: String) {
        _inputText.value = text
        typingJob?.cancel()
        if (text.isNotBlank()) {
            repository.emitTyping(true)
            typingJob = viewModelScope.launch {
                delay(1_500L)
                repository.emitTyping(false)
            }
        } else {
            repository.emitTyping(false)
        }
    }

    /**
     * Delegates fully to [ChatRepository.sendMessage].
     *
     * The repository handles the Room insert (optimistic) and either emits
     * the socket message immediately (online) or queues it with `pending = true`
     * (offline) for delivery on the next reconnect.
     */
    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return
        _inputText.value = ""
        typingJob?.cancel()
        repository.emitTyping(false)
        repository.sendMessage(text, _selectedPackage.value ?: "", selectedAppName)
    }

    /** Marks the currently-open thread's admin messages as read. */
    fun onChatVisible() {
        repository.markAsRead(_selectedPackage.value ?: "")
    }

    // ── Navigation (Bug 5) ────────────────────────────────────────────────────

    /** Opens a per-app thread. */
    fun selectSession(session: ChatSession) {
        _selectedPackage.value = session.packageName
        selectedAppName = session.appName
    }

    /** Returns from a thread to the conversation list. */
    fun clearSelection() {
        _selectedPackage.value = null
        selectedAppName = ""
    }

    /** Title shown above the open thread. */
    fun selectedTitle(): String = when {
        selectedAppName.isNotBlank() -> selectedAppName
        _selectedPackage.value.isNullOrBlank() -> "General"
        else -> _selectedPackage.value!!
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        socketManager.disconnect()
    }

    // ── Session aggregation ───────────────────────────────────────────────────

    private fun buildSessions(all: List<Message>): List<ChatSession> {
        val grouped = all.groupBy { it.packageName }
            .map { (pkg, msgs) ->
                val sorted = msgs.sortedBy { it.timestamp }
                val last = sorted.last()
                val appName = sorted.lastOrNull { it.appName.isNotBlank() }?.appName ?: ""
                ChatSession(
                    packageName = pkg,
                    appName = appName,
                    lastMessage = last.text,
                    lastTimestamp = last.timestamp,
                    unreadCount = msgs.count { it.isFromAdmin && !it.read },
                )
            }

        // Always keep a General thread available, even with no messages.
        val withGeneral = if (grouped.any { it.packageName.isEmpty() }) {
            grouped
        } else {
            grouped + generalSession(emptyList())
        }
        return withGeneral.sortedByDescending { it.lastTimestamp }
    }

    private fun generalSession(general: List<Message>): ChatSession {
        val last = general.maxByOrNull { it.timestamp }
        return ChatSession(
            packageName = "",
            appName = "General",
            lastMessage = last?.text ?: "",
            lastTimestamp = last?.timestamp ?: 0L,
            unreadCount = general.count { it.isFromAdmin && !it.read },
        )
    }
}
