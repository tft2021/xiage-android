package com.xiaozhi.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaozhi.app.ui.XiaozhiViewModel
import com.xiaozhi.protocol.session.XiaozhiSession

class MainActivity : ComponentActivity() {

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 浅色背景配深色状态栏图标
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        window.statusBarColor = BgTop.toArgb()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = BrandBlue)) {
                XiaozhiScreen()
            }
        }
    }
}

// ------------------------------------------------------------------ 设计令牌

private val BgTop = Color(0xFFF8FAFF)
private val BgBottom = Color(0xFFE6EDFF)
private val BrandBlue = Color(0xFF4A7BF7)
private val InkPrimary = Color(0xFF1B2340)
private val InkSecondary = Color(0xFF6B7794)
private val Disabled = Color(0xFFD5DBE8)

/** 每个阶段对应的视觉表现：表情、文案、主色、是否处于"忙碌"态（呼吸动画更快） */
private data class PhaseStyle(
    val emoji: String,
    val label: String,
    val color: Color,
    val busy: Boolean,
)

private fun styleFor(phase: XiaozhiSession.Phase, emotion: String): PhaseStyle = when (phase) {
    is XiaozhiSession.Phase.Idle -> PhaseStyle("💤", "未连接", Color(0xFF9AA3B2), false)
    is XiaozhiSession.Phase.FetchingConfig -> PhaseStyle("🔄", "获取配置…", BrandBlue, true)
    is XiaozhiSession.Phase.NeedActivation -> PhaseStyle("🔑", "等待绑定确认…", Color(0xFFF5A524), true)
    is XiaozhiSession.Phase.FetchingCredentials ->
        PhaseStyle("✅", "绑定成功，正在获取凭据…", Color(0xFF22C55E), true)
    is XiaozhiSession.Phase.Connecting -> PhaseStyle("🔗", "连接中…", BrandBlue, true)
    is XiaozhiSession.Phase.Ready -> PhaseStyle("🙂", "就绪，按住说话", Color(0xFF22C55E), false)
    is XiaozhiSession.Phase.Listening -> PhaseStyle("🎤", "聆听中…", Color(0xFFE24B4A), true)
    is XiaozhiSession.Phase.Speaking ->
        PhaseStyle(emotionEmoji(emotion), "回复中…", Color(0xFF8B5CF6), true)
    is XiaozhiSession.Phase.Error -> PhaseStyle("⚠️", "出错了", Color(0xFFE24B4A), false)
}

/** 服务端下发的 emotion 字段映射成表情；未识别时回落中性 */
private fun emotionEmoji(emotion: String): String = when (emotion.lowercase()) {
    "happy", "laughing", "excited", "joyful" -> "😄"
    "sad", "crying", "unhappy" -> "😢"
    "angry", "mad" -> "😠"
    "surprised", "shocked" -> "😲"
    "thinking", "pondering" -> "🤔"
    "sleepy", "tired" -> "😴"
    "love", "shy", "like" -> "😍"
    "confused", "puzzled" -> "😕"
    "fear", "scared" -> "😨"
    else -> "🙂"
}

// ------------------------------------------------------------------ 主界面

@Composable
fun XiaozhiScreen(vm: XiaozhiViewModel = viewModel()) {
    val phase by vm.session.phase.collectAsState()
    val subtitle by vm.session.subtitle.collectAsState()
    val emotion by vm.session.emotion.collectAsState()
    val deviceId by vm.deviceId.collectAsState()
    val context = LocalContext.current
    var activationCode by remember { mutableStateOf<String?>(null) }

    // 只有处于"需要激活"阶段才展示激活码卡片。连接中 / 已就绪 / 出错（含激活超时）
    // 时旧码已失效，必须清掉，否则用户会拿一个服务端已经作废的码去 xiaozhi.me 输入。
    LaunchedEffect(phase) {
        if (phase !is XiaozhiSession.Phase.NeedActivation) activationCode = null
    }

    val style = styleFor(phase, emotion)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text("小智", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = InkPrimary)
            Text("Android 语音助手", fontSize = 12.sp, color = InkSecondary)

            Spacer(Modifier.height(18.dp))
            StatusPill(style)

            Spacer(Modifier.weight(0.5f))
            EmotionOrb(style)
            Spacer(Modifier.height(22.dp))

            MessagePanel(phase, subtitle)

            Spacer(Modifier.height(14.dp))
            val code = activationCode
            if (code != null) {
                ActivationCard(
                    code = code,
                    deviceId = deviceId,
                    onCopy = { copyToClipboard(context, code) },
                    onCheck = { vm.nudgeActivation() },
                    onResetIdentity = {
                        activationCode = null
                        vm.resetIdentity()
                        Toast.makeText(
                            context, "设备身份已重置，请点连接获取新的激活码", Toast.LENGTH_LONG
                        ).show()
                    },
                )
            }

            Spacer(Modifier.weight(1f))
            ControlBar(
                phase = phase,
                style = style,
                onTalk = {
                    when (phase) {
                        is XiaozhiSession.Phase.Listening -> vm.stopListening()
                        // 说话中直接按下 = 先打断上一轮再开始听，省一次点击
                        is XiaozhiSession.Phase.Speaking -> {
                            vm.abort()
                            vm.startListening()
                        }
                        else -> vm.startListening()
                    }
                },
                onConnect = {
                    activationCode = null
                    vm.start { activationCode = it }
                },
                // 激活等待中点"重新获取"= 先断开旧的激活循环再重新拉码。
                // Session 侧已加防重入：不先 stop 直接 start 会被拒绝
                onReconnect = {
                    activationCode = null
                    vm.disconnect()
                    vm.start { activationCode = it }
                },
                onDisconnect = vm::disconnect,
                onAbort = vm::abort,
            )
            Spacer(Modifier.height(36.dp))
        }
    }
}

// ------------------------------------------------------------------ 组件

@Composable
private fun StatusPill(style: PhaseStyle) {
    Surface(shape = RoundedCornerShape(50), color = style.color.copy(alpha = 0.12f)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(style.color),
            )
            Text(
                style.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = style.color,
            )
        }
    }
}

@Composable
private fun EmotionOrb(style: PhaseStyle) {
    val transition = rememberInfiniteTransition(label = "orb")
    val duration = if (style.busy) 700 else 2200
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (style.busy) 1.07f else 1.03f,
        animationSpec = infiniteRepeatable(tween(duration), RepeatMode.Reverse),
        label = "orbScale",
    )
    val glow by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = if (style.busy) 0.5f else 0.32f,
        animationSpec = infiniteRepeatable(tween(duration), RepeatMode.Reverse),
        label = "orbGlow",
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(168.dp)) {
        // 外圈光晕
        Box(
            Modifier
                .size(168.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(style.color.copy(alpha = glow * 0.30f)),
        )
        // 球体
        Box(
            Modifier
                .size(116.dp)
                .scale(scale)
                .shadow(14.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text(style.emoji, fontSize = 54.sp)
        }
    }
}

@Composable
private fun MessagePanel(phase: XiaozhiSession.Phase, subtitle: String) {
    val isError = phase is XiaozhiSession.Phase.Error
    val text = (phase as? XiaozhiSession.Phase.Error)?.message ?: subtitle

    if (text.isBlank()) {
        Spacer(Modifier.height(64.dp))
    } else {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (isError) Color(0xFFFFF1F0) else Color.White,
            shadowElevation = if (isError) 0.dp else 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
                color = if (isError) Color(0xFFB42318) else InkPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .heightIn(min = 56.dp),
            )
        }
    }
}

@Composable
private fun ActivationCard(
    code: String,
    deviceId: String,
    onCopy: () -> Unit,
    onCheck: () -> Unit,
    onResetIdentity: () -> Unit,
) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("请在 xiaozhi.me 登录并输入激活码", fontSize = 13.sp, color = InkSecondary)
            Spacer(Modifier.height(8.dp))
            Text(
                code,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
                color = InkPrimary,
            )
            Spacer(Modifier.height(6.dp))
            // 用户输码后最困惑的就是"界面没反应"：给一个主动触发检测的按钮，
            // 并说明自动检测的存在，避免干等
            TextButton(onClick = onCheck) {
                Text("我已输码，立即检测", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                "输码成功后通常几秒内自动连接；切到后台输码回来后若长时间无反应，点上方按钮立即检测",
                fontSize = 11.sp,
                color = InkSecondary,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onCopy) { Text("复制激活码", fontSize = 12.sp) }
            // 设备号用于与 xiaozhi.me 的设备列表核对：
            // 多次重装/重置会产生多个设备条目，绑错条目时手机永远"没反应"。
            // 若每次连接设备号都在变，说明身份被反复重置——先删掉 xiaozhi.me 上的
            // 旧条目再用当前码重绑；确实绑错时可用"重置设备身份"换一个新身份。
            Text(
                "设备号 $deviceId",
                fontSize = 10.sp,
                color = InkSecondary.copy(alpha = 0.7f),
            )
            TextButton(onClick = onResetIdentity) {
                Text("重置设备身份", fontSize = 11.sp, color = Color(0xFFB42318))
            }
        }
    }
}

@Composable
private fun ControlBar(
    phase: XiaozhiSession.Phase,
    style: PhaseStyle,
    onTalk: () -> Unit,
    onConnect: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAbort: () -> Unit,
) {
    val listening = phase is XiaozhiSession.Phase.Listening
    val speaking = phase is XiaozhiSession.Phase.Speaking
    // 说话中也允许按下：onTalk 会先打断再开始听
    val canTalk = phase is XiaozhiSession.Phase.Ready || listening || speaking
    val needActivation = phase is XiaozhiSession.Phase.NeedActivation
    val idle = phase is XiaozhiSession.Phase.Idle || phase is XiaozhiSession.Phase.Error
    val busyConnecting = phase is XiaozhiSession.Phase.FetchingConfig ||
        phase is XiaozhiSession.Phase.Connecting

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SecondaryButton(
            text = when {
                needActivation -> "重新获取"
                idle -> "连接"
                else -> "断开"
            },
            enabled = !busyConnecting,
            onClick = when {
                needActivation -> onReconnect
                idle -> onConnect
                else -> onDisconnect
            },
        )

        TalkButton(enabled = canTalk, listening = listening, style = style, onClick = onTalk)

        // 固定宽度占位，避免"打断"按钮出现/消失时主按钮左右跳动
        Box(Modifier.width(80.dp), contentAlignment = Alignment.Center) {
            if (speaking) SecondaryButton(text = "打断", onClick = onAbort)
        }
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TalkButton(
    enabled: Boolean,
    listening: Boolean,
    style: PhaseStyle,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (listening) 1.06f else 1f,
        label = "talkScale",
    )
    Box(contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                Modifier
                    .size(104.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(style.color.copy(alpha = 0.18f)),
            )
        }
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier
                .size(84.dp)
                .scale(scale),
            shape = CircleShape,
            containerColor = if (enabled) style.color else Disabled,
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(6.dp),
        ) {
            Text(
                if (listening) "停止" else "说话",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ------------------------------------------------------------------ 工具

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("activation_code", text))
    Toast.makeText(context, "激活码已复制", Toast.LENGTH_SHORT).show()
}
