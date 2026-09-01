# xiaozhi-android

小智 AI 语音助手的 Android 原生客户端（Kotlin + Compose）。

协议基于 `78/xiaozhi-esp32` 的文档与 `huangjunsen0406/py-xiaozhi` 的成熟实现，
激活链路已对官方线上服务实测验证（2026-08-31），详见
`../probe/官方服务器接入验证报告.md` 与 `../协议对接清单.md`。

## 现状

| 模块 | 状态 |
|---|---|
| OTA 配置拉取 / 激活 | ✅ 已实现并实测验证（`core-protocol/ota/`） |
| WebSocket 协议层 | ✅ 已实现（`core-protocol/ws/`），hello / listen / abort / tts / stt / llm / mcp |
| 二进制帧封装 | ✅ v1 裸 Opus + v2 / v3（`core-protocol/ws/BinaryFrameCodec`） |
| 会话状态机 | ✅ 下沉到 `core-protocol/session/`，依赖全接口注入，JVM 可测 |
| 音频采集 / 播放 | ✅ 已实现（`app/audio/AudioEngine`），VOICE_COMMUNICATION 音源启用系统 AEC |
| 重采样 | ✅ 线性插值（`core-protocol/audio/Resampler`） |
| **Opus 编解码** | ✅ Concentus（纯 Java 移植）已接入，见下文「Opus 编解码」 |
| JVM 单元测试 | ✅ **51 个用例全部通过**（`core-protocol/src/test/`，`bash build-local.sh` 运行） |
| 端到端协议回归 | ✅ 对真实服务器 11/11 通过（`../probe/e2e_regression.py`） |
| UI | ⚠️ 基础骨架（激活码提示 / 表情 / 按住说话 / 打断），表情为纯文本占位 |

## 获取 APK

方式一：直接下载已构建好的安装包
→ https://github.com/tft2021/xiage-android/releases/latest

方式二：GitHub Actions 自动构建（push main / 手动触发 / 打 tag v*）：
- 每次 push 到 main 都会跑 core-protocol 单测并打 release APK，产物在 Actions 页面下载
- 打 `v*` tag 时自动创建 Release 并挂载 APK
- 构建失败时日志自动归档到 `ci-logs` 分支的 `logs/run-<id>.txt`（Actions 日志需登录才能下载）

签名：优先读环境变量 `XIAOZHI_KEYSTORE` / `XIAOZHI_STORE_PASSWORD` / `XIAOZHI_KEY_ALIAS` /
`XIAOZHI_KEY_PASSWORD`；未注入时回落到仓库内测试 keystore（`keystore/release-test.jks`），
保证 clone 即可构建。**正式对外发布前必须换成私有 keystore**，通过仓库 Secret 注入。

## 编译与测试（无 Android SDK 环境）

本机已搭好纯 JVM 编译环境（Microsoft JDK 17 + kotlin-compiler 2.0.21 + Maven Central jar，
获取方式见 `../.tools/README.md`），可离线编译 core-protocol 并运行其单元测试：

```bash
bash build-local.sh     # [1/3] 编译主源码 [2/3] 编译测试 [3/3] JUnit（需 51 个全绿）
```

对真实服务器的协议回归单独跑（Python，纯标准库）：

```bash
python ../probe/e2e_regression.py   # OTA v2 / UA 校验 / 激活 / WS hello / 二进制帧
```

## 构建 APK（需要 Android SDK）

本工程无法在无 Android SDK 的机器上出 APK，请用 Android Studio 打开：

```bash
# 1. Android Studio -> Open -> 选择本目录
# 2. 等 Gradle Sync 完成
# 3. 连接真机运行（必须真机，模拟器没有麦克风 AEC）
```

依赖：Android Studio Ladybug+ / AGP 8.7 / Kotlin 2.0 / JDK 17。

## Opus 编解码（已接入 Concentus）

`AudioCodecProvider` 生产实现为 `ConcentusCodecProvider`：Concentus 纯 Java Opus
移植（源码内嵌于 `core-protocol/src/main/java/org/concentus/`，含版权声明
`CONCENTUS-LICENSE`），无 NDK 依赖。

- 上行编码：16000 Hz / mono / 60ms（960 采样/帧），VOIP 模式，24kbps
- 下行解码：按服务端 hello 协商采样率创建（16k / 24k 均验证）
- `createDecoder(sampleRate)` 随下行采样率懒创建，协商变化时自动重建
- 若后续低端机 CPU 吃紧，可换 libopus + JNI（接口不变，只换 Provider）

## 服务端

默认连官方 `api.tenclass.net`，**首次使用需绑定账号**：

1. App 内点「连接」→ 显示 6 位激活码
2. 用浏览器打开 xiaozhi.me 登录，输入该激活码
3. App 轮询 `/activate` 直到 200，自动重连

若要完全自主可控，替换 `OtaClient` 构造参数里的 `otaUrl` 指向自建服务端
（`xinnan-tech/xiaozhi-esp32-server` / `joey-zhou/xiaozhi-esp32-server-java` /
`AnimeAIChat/xiaozhi-server-go`），即可跳过激活流程。

## 目录结构

```
xiaozhi-android/
├── core-protocol/                  纯 Kotlin JVM 模块，可脱离 Android 编译与单测
│   ├── src/main/kotlin/com/xiaozhi/protocol/
│   │   ├── ota/
│   │   │   ├── OtaTypes.kt         OtaConfig / ActivateResult / OtaApi 接口（依赖注入点）
│   │   │   ├── OtaClient.kt        OTA 配置拉取 + 激活轮询（实测验证过的实现）
│   │   │   └── DeviceCredentials.kt 设备身份与 HMAC 签名（对应 py-xiaozhi 的 efuse.json）
│   │   ├── ws/
│   │   │   ├── XiaozhiMessages.kt  消息模型与 JSON 解析（含 AudioParams）
│   │   │   ├── XiaozhiTransport.kt 传输层接口 + ConnectionState
│   │   │   ├── XiaozhiWsClient.kt  OkHttp 实现（握手头 / hello 超时 / 二进制帧）
│   │   │   └── BinaryFrameCodec.kt v1/v2/v3 二进制帧编解码
│   │   ├── audio/
│   │   │   ├── AudioIO.kt          音频 IO / 编解码提供者接口（降级用 NoOpCodecProvider）
│   │   │   └── OpusCodec.kt        编解码接口 + 重采样器
│   │   └── session/
│   │       └── XiaozhiSession.kt   状态机：Idle→激活→Connecting→Ready→Listening/Speaking
│   └── src/test/kotlin/            51 个 JUnit 用例（帧/消息/身份/重采样/会话 Fake 全流程）
└── app/                            Android 应用层
    └── src/main/java/com/xiaozhi/app/
        ├── MainActivity.kt         Compose UI（激活码 / 表情 / 按住说话）
        ├── audio/AudioEngine.kt    AudioIO 实现（AudioRecord / AudioTrack）
        ├── service/VoiceService.kt 前台服务占位（保活）
        └── ui/XiaozhiViewModel.kt  身份持久化 + 会话装配（注入 core-protocol）
```

## 关键实现决策（为什么这么写）

1. **身份四元组持久化**：`device_id` / `client_id` / `serial_number` / `hmac_key`
   一起决定服务端认定的设备。换任何一个都会重新注册、丢失绑定。
2. **boardType 沿用 `bread-compact-wifi`**：服务端只认已知板型，这是 py-xiaozhi
   验证过的可行值。UA 必须严格 `{boardType}/{appName}-{appVersion}`。
   实测补充：固件风格 body（无 board 段）时 UA 强校验（错误格式 400）；
   带 `board` 段 + `Serial-Number` 的请求服务端以 body 为准，UA 容错
   （见 `../probe/e2e_regression.py` T2）。
3. **激活用 v2 HMAC 路径**：本地生成密钥（`DeviceCredentials`），
   OTA 时通过 `application.elf_sha256` 字段上报给服务端注册，激活时签名 challenge。
   这是第三方客户端唯一不需要真实 eFuse 硬件的路径。
4. **hello 超时 10 秒**：与固件一致。nginx 101 不代表应用层接受，
   以收到服务端 hello 为准。
5. **音频采集用 `VOICE_COMMUNICATION`**：让系统 AEC / AGC 生效，
   免 NDK 解决回声消除，这是"半双工体感良好"的关键。

## 待办

- [x] 接入 Opus 编解码实现（Concentus，2026-09-01）
- [ ] 唤醒词：sherpa-onnx KWS 或 Porcupine，或先维持按键说话
- [ ] 语音会话迁入 `VoiceService`，处理切后台
- [ ] 表情 / 字幕 UI 精化（Lottie 或自绘）
