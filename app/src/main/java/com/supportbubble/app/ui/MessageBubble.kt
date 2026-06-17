package com.supportbubble.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.supportbubble.app.models.Message
import com.supportbubble.app.ui.theme.ChatBubbleAdmin
import com.supportbubble.app.ui.theme.ChatBubbleAdminBorder
import com.supportbubble.app.ui.theme.ChatBubbleAdminText
import com.supportbubble.app.ui.theme.ChatBubbleClient
import com.supportbubble.app.ui.theme.ChatBubbleClientText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Renders a single chat message bubble.
 *
 * Client messages align to the right with a solid background.
 * Admin messages align to the left with a border.
 *
 * Pending messages (queued while offline) show "Sending…" instead of the
 * timestamp so the user knows delivery hasn't completed yet.
 */
@Composable
fun MessageBubble(message: Message) {
    val isClient = message.isFromClient

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (isClient) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isClient) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            if (!isClient) {
                Text(
                    text = "Support",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }

            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isClient) 16.dp else 4.dp,
                    topEnd = if (isClient) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
                ),
                color = if (isClient) {
                    if (message.pending) ChatBubbleClient.copy(alpha = 0.55f)
                    else ChatBubbleClient
                } else ChatBubbleAdmin,
                shadowElevation = if (isClient) 0.dp else 1.dp,
                border = if (!isClient) BorderStroke(1.dp, ChatBubbleAdminBorder) else null,
            ) {
                Text(
                    text = message.text,
                    color = if (isClient) ChatBubbleClientText else ChatBubbleAdminText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }

            // Timestamp row — shows "Sending…" while the message is queued offline
            Text(
                text = when {
                    message.pending -> "Sending…"
                    else -> timeFormat.format(Date(message.timestamp))
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    message.pending -> Color(0xFFBDBDBD)   // muted — indicates in-progress
                    else -> Color(0xFF9E9E9E)
                },
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}
