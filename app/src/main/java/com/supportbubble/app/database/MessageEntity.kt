package com.supportbubble.app.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.supportbubble.app.models.Message

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val text: String,
    val sender: String,
    val timestamp: Long,
    val read: Boolean,
    /**
     * true  → stored locally but not yet delivered to the server (no internet).
     * false → successfully emitted to the server.
     *
     * Room migration: column added in schema version 2 with DEFAULT 0.
     */
    val pending: Boolean = false,
    /**
     * Package of the app this message's thread belongs to ("" = general thread).
     * Room migration: column added in schema version 3 with DEFAULT ''.
     */
    val packageName: String = "",
    /**
     * Human-readable label of [packageName] ("" when unknown).
     * Room migration: column added in schema version 3 with DEFAULT ''.
     */
    val appName: String = "",
) {
    fun toMessage(): Message = Message(
        id = id,
        deviceId = deviceId,
        text = text,
        sender = if (sender == "admin") Message.Sender.ADMIN else Message.Sender.CLIENT,
        timestamp = timestamp,
        read = read,
        pending = pending,
        packageName = packageName,
        appName = appName,
    )

    companion object {
        fun fromMessage(message: Message): MessageEntity = MessageEntity(
            id = message.id,
            deviceId = message.deviceId,
            text = message.text,
            sender = if (message.sender == Message.Sender.ADMIN) "admin" else "client",
            timestamp = message.timestamp,
            read = message.read,
            pending = message.pending,
            packageName = message.packageName,
            appName = message.appName,
        )
    }
}
