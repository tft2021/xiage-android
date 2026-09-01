# 小智 Android 客户端混淆规则
# 当前 release 关闭了混淆（isMinifyEnabled = false），此文件为后续开启预留。

# OkHttp / WebSocket 传输层
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson / org.json 反射建模（若后续引入 JSON 数据类再放开）
-dontwarn org.json.**

# Kotlin 协程与序列化
-dontwarn kotlinx.coroutines.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata { public <methods>; }

# 保留协议模型类（服务端消息解析依赖字段名）
-keep class com.xiaozhi.protocol.** { *; }
