package com.xiaozhi.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhi.android.ui.ActivationScreen
import com.xiaozhi.android.ui.ChatScreen
import com.xiaozhi.android.ui.MainViewModel
import com.xiaozhi.android.ui.StatusScreen
import com.xiaozhi.android.ui.theme.XiaozhiTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // 权限拒绝时仍渲染 UI，由屏幕提示用户
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            android.util.Log.w(TAG, "被拒绝的权限: $denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        setContent {
            XiaozhiTheme {
                val phase by viewModel.phase.collectAsStateWithLifecycle()
                val conversation by viewModel.conversation.collectAsStateWithLifecycle()

                when (val p = phase) {
                    is XiaozhiSession.Phase.Idle -> StatusScreen("正在初始化…")

                    is XiaozhiSession.Phase.FetchingConfig -> StatusScreen("正在拉取配置…")

                    is XiaozhiSession.Phase.NeedActivation -> ActivationScreen(
                        code = p.code,
                        message = p.message,
                        attempt = 0,
                        onOpenBrowser = ::openXiaozhiSite,
                    )

                    is XiaozhiSession.Phase.Activating -> ActivationScreen(
                        code = p.code,
                        message = null,
                        attempt = p.attempt,
                        onOpenBrowser = ::openXiaozhiSite,
                    )

                    is XiaozhiSession.Phase.Connecting -> StatusScreen("正在连接小智服务器…")

                    is XiaozhiSession.Phase.Ready -> ChatScreen(
                        state = conversation,
                        onPressStart = {
                            // 说话期间按下即打断，否则进入聆听
                            if (conversation.deviceState == com.xiaozhi.android.protocol.DeviceState.SPEAKING) {
                                viewModel.abort()
                            } else {
                                viewModel.startListening()
                            }
                        },
                        onPressEnd = { viewModel.stopListening() },
                    )

                    is XiaozhiSession.Phase.Error -> StatusScreen(p.message, isError = true)
                }
            }
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        // Android 13+ 没有通知权限，前台服务通知不会显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestRequiredPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun openXiaozhiSite() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://xiaozhi.me")))
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
