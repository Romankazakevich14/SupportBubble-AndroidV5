package com.supportbubble.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionBanner(state: ConnectionState) {
    val visible = state != ConnectionState.CONNECTED

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        val (bgColor, label) = when (state) {
            ConnectionState.CONNECTING -> Color(0xFFFFA000) to "Connecting…"
            ConnectionState.DISCONNECTED -> Color(0xFFD32F2F) to "Disconnected — retrying…"
            ConnectionState.CONNECTED -> Color.Transparent to ""
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == ConnectionState.CONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 8.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = label,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = if (state == ConnectionState.CONNECTING) 8.dp else 0.dp),
                )
            }
        }
    }
}
