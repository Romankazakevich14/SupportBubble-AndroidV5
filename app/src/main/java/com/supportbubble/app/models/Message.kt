package com.supportbubble.app.models

data class Message(
    val id: String,
    val deviceId: String,
    val text: String,
    val sender: Sender,
    val timestamp: Long,
    val read: Boolean,
    /** true while the message is queued locally and has not yet been delivered to the server. */
    val pending: Boolean = false,
    /**
     * Package of the app this message's conversation thread belongs to.
     * "" = the general / unknown thread (legacy messages or in-app general chat).
     */
    val packageName: String = "",
    /** Human-readable label of [packageName] (e.g. "Telegram"); "" when unknown. */
    val appName: String = "",
) {
    enum class Sender { CLIENT, ADMIN }

    val isFromClient: Boolean get() = sender == Sender.CLIENT
    val isFromAdmin: Boolean get() = sender == Sender.ADMIN
}
