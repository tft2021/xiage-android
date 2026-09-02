package com.xiaozhi.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaozhi.protocol.session.XiaozhiSession
import com.xiaozhi.app.ui.XiaozhiViewModel

class MainActivity : ComponentActivity() {

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                XiaozhiScreen()
            }
        }
    }
}

@Composable
fun XiaozhiScreen(vm: XiaozhiViewModel = viewModel()) {
    val phase by vm.session.phase.collectAsState()
    val subtitle by vm.session.subtitle.collectAsState()
    val emotion by vm.session.emotion.collectAsState()
    var activationCode by remember { mutableStateOf<String?>(null) }

    // 只有处于"需要激活"阶段才展示激活码卡片。
    // 连接中 / 已就绪 / 出错（含激活超时）时旧码已失效，必须清掉，
    // 否则用户会拿一个服务端已经作废的码去 xiaozhi.me 输入。
    LaunchedEffect(phase) {
        if (phase !is XiaozhiSession.Phase.NeedActivation) activationCode = null
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 表情占位：正式版换成 Lottie / 自绘表情
            Text(emotion, fontSize = 56.sp)

            Spacer(Modifier.height(16.dp))
            Text(
                when (val p = phase) {
                    is XiaozhiSession.Phase.Idle -> "未连接"
                    is XiaozhiSession.Phase.FetchingConfig -> "获取配置..."
                    is XiaozhiSession.Phase.Connecting -> "连接中..."
                    is XiaozhiSession.Phase.Ready -> "就绪，按住说话"
                    is XiaozhiSession.Phase.Listening -> "聆听中..."
                    is XiaozhiSession.Phase.Speaking -> "回复中..."
                    is XiaozhiSession.Phase.NeedActivation -> "需要激活"
                    is XiaozhiSession.Phase.Error -> "错误"
                },
                fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                (phase as? XiaozhiSession.Phase.Error)?.message ?: subtitle,
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(min = 40.dp),
            )

            // 激活码提示
            activationCode?.let { code ->
                Card(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("请到 xiaozhi.me 登录并输入激活码", fontSize = 13.sp)
                        Text(code, fontSize = 36.sp, fontWeight = FontWeight.Bold,
                             modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        if (phase is XiaozhiSession.Phase.Listening) vm.stopListening()
                        else vm.startListening()
                    },
                    enabled = phase is XiaozhiSession.Phase.Ready ||
                        phase is XiaozhiSession.Phase.Listening,
                    modifier = Modifier.size(88.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (phase is XiaozhiSession.Phase.Listening)
                            Color(0xFFE24B4A) else MaterialTheme.colorScheme.primary,
                    ),
                ) { Text(if (phase is XiaozhiSession.Phase.Listening) "停止" else "说话") }

                OutlinedButton(
                    onClick = { activationCode = null; vm.start { activationCode = it } },
                    enabled = phase is XiaozhiSession.Phase.Idle ||
                        phase is XiaozhiSession.Phase.Error ||
                        phase is XiaozhiSession.Phase.NeedActivation,
                ) { Text("连接") }

                if (phase is XiaozhiSession.Phase.Speaking) {
                    OutlinedButton(onClick = vm::abort) { Text("打断") }
                }
            }
        }
    }
}
