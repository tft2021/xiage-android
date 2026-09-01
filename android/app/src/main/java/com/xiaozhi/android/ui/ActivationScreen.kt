package com.xiaozhi.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 激活引导页。
 *
 * 等价于 ESP32 把 6 位激活码显示在 OLED 屏幕上 —— 只是换成了手机界面。
 * 用户去 xiaozhi.me 登录并输入该码后，客户端轮询 /activate 直到返回 200。
 */
@Composable
fun ActivationScreen(
    code: String,
    message: String?,
    attempt: Int,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "设备未激活",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "请用浏览器访问 xiaozhi.me，登录后输入下面的激活码完成绑定。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 40.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = code,
                    fontSize = 40.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 8.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (!message.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message.replace("\n", " · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        Button(onClick = onOpenBrowser) {
            Text("打开 xiaozhi.me")
        }

        if (attempt > 0) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "等待绑定中… 第 $attempt 次轮询",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
