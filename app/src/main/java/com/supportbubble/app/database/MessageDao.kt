package com.supportbubble.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    // ── Observation ───────────────────────────────────────────────────────────

    @Query("SELECT * FROM messages WHERE deviceId = :deviceId ORDER BY timestamp ASC")
    fun observeMessages(deviceId: String): Flow<List<MessageEntity>>

    /**
     * Observes the messages for a single per-app thread (Bug 5 — per-app chat
     * separation). [packageName] == "" selects the general / unknown thread.
     */
    @Query(
        "SELECT * FROM messages WHERE deviceId = :deviceId AND packageName = :packageName " +
            "ORDER BY timestamp ASC"
    )
    fun observeMessagesForPackage(deviceId: String, packageName: String): Flow<List<MessageEntity>>

    // ── Inserts ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    // ── Pending / offline queue ───────────────────────────────────────────────

    /**
     * Returns all locally-queued messages for [deviceId] sorted oldest-first.
     * Called during reconnect to flush the offline queue.
     */
    @Query("SELECT * FROM messages WHERE deviceId = :deviceId AND pending = 1 ORDER BY timestamp ASC")
    suspend fun getPendingMessages(deviceId: String): List<MessageEntity>

    /**
     * Marks a single message as delivered ([pending] = false) after the socket emits it.
     */
    @Query("UPDATE messages SET pending = :pending WHERE id = :id")
    suspend fun setPendingStatus(id: String, pending: Boolean)

    /**
     * Hard-deletes a message by id.
     * Used when replacing a pending local-UUID entry with a server-confirmed entry.
     */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    // ── Read / unread ─────────────────────────────────────────────────────────

    @Query("UPDATE messages SET read = 1 WHERE deviceId = :deviceId AND sender = 'admin'")
    suspend fun markAdminMessagesRead(deviceId: String)

    /** Marks admin messages read for a single per-app thread. */
    @Query(
        "UPDATE messages SET read = 1 WHERE deviceId = :deviceId " +
            "AND packageName = :packageName AND sender = 'admin'"
    )
    suspend fun markAdminMessagesReadForPackage(deviceId: String, packageName: String)

    @Query("SELECT COUNT(*) FROM messages WHERE deviceId = :deviceId AND read = 0 AND sender = 'admin'")
    suspend fun unreadAdminCount(deviceId: String): Int

    // ── Housekeeping ──────────────────────────────────────────────────────────

    @Query("DELETE FROM messages WHERE deviceId = :deviceId")
    suspend fun clearMessages(deviceId: String)
}
