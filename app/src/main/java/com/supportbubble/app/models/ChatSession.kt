package com.supportbubble.app.models

/**
 * A single per-app conversation thread shown in the chat list (Bug 5 — per-app
 * chat separation). One session aggregates every [Message] that shares the same
 * [packageName].
 *
 * [packageName] == "" is the general / unknown thread (legacy messages or chats
 * started from inside SupportBubble itself rather than from an app's bubble).
 */
data class ChatSession(
    val packageName: String,
    val appName: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unreadCount: Int,
) {
    /** Title shown in the list — falls back to the package or a generic label. */
    val displayName: String
        get() = when {
            appName.isNotBlank() -> appName
            packageName.isNotBlank() -> packageName
            else -> "General"
        }
}
