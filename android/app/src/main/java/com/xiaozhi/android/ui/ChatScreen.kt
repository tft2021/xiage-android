package com.xiaozhi.android.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaozhi.android.protocol.DeviceState
import com.xiaozhi.android.XiaozhiSession

@Composable
fun ChatScreen(
    state: XiaozhiSession.ConversationState,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when (state.deviceState) {
                DeviceState.LISTENING -> "正在聆听…"
                DeviceState.SPEAKING -> "小智正在说话"
                DeviceState.IDLE -> "按住说话"
            },
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(Modifier.height(20.dp))

        // 情绪 / 表情位
        Card(
            modifier = Modifier.size(96.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = state.emotion.ifBlank { "🙂" },
                    fontSize = 40.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        if (state.userText.isNotBlank()) {
            Bubble(
                text = state.userText,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }

        if (state.assistantText.isNotBlank()) {
            Bubble(
                text = state.assistantText,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Spacer(Modifier.weight(1f))

        // 按住说话。说话期间按下即打断
        Surface(
            modifier = Modifier
                .size(120.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onPressStart()
                            try {
                                awaitRelease()
                            } finally {
                                onPressEnd()
                            }
                        },
                    )
                },
            shape = CircleShape,
            color = if (state.deviceState == DeviceState.LISTENING) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.deviceState == DeviceState.LISTENING) "松开" else "按住",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 音量条，用于确认麦克风确实在工作
        val normalized = ((state.levelDb + 60f) / 60f).coerceIn(0f, 1f)
        androidx.compose.material3.LinearProgressIndicator(
            progress = { normalized },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Bubble(
    text: String,
    container: Color,
    content: Color,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = content,
            textAlign = TextAlign.Start,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
fun StatusScreen(
    text: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
