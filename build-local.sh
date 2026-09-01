#!/usr/bin/env bash
# core-protocol 本地编译 + 测试脚本（无 Gradle / Android SDK 环境用）
# 依赖：.tools/jdk-17*、.tools/downloads/*.jar（获取方式见 .tools/README.md）
set -e
cd "$(dirname "$0")"

JAVA=$(ls .tools/jdk-17*/bin/java.exe | head -1)
JAVAC=$(ls .tools/jdk-17*/bin/javac.exe | head -1)
DL=.tools/downloads

KOTLINC_CP="$DL/kotlin-compiler-2.0.21.jar;$DL/kotlin-stdlib-2.0.21.jar;$DL/kotlin-reflect-2.0.21.jar;$DL/kotlin-script-runtime-2.0.21.jar;$DL/kotlin-daemon-embeddable-2.0.21.jar;$DL/trove4j.jar;$DL/annotations-13.0.jar;$DL/kotlinx-coroutines-core-jvm-1.9.0.jar"
RUNTIME_CP="$DL/kotlin-stdlib-2.0.21.jar;$DL/kotlinx-coroutines-core-jvm-1.9.0.jar;$DL/okhttp-4.12.0.jar;$DL/okio-jvm-3.6.0.jar;$DL/json-20240303.jar"
TEST_CP="$DL/kotlin-test-2.0.21.jar;$DL/kotlin-test-junit5-2.0.21.jar;$DL/junit-jupiter-api-5.11.3.jar;$DL/junit-jupiter-engine-5.11.3.jar;$DL/opentest4j-1.3.0.jar;$DL/junit-platform-commons-1.11.3.jar;$DL/junit-platform-engine-1.11.3.jar;$DL/apiguardian-api-1.1.2.jar"

echo "== [1/4] 编译 Concentus（Java）=="
mkdir -p xiaozhi-android/build/classes-java
"$JAVAC" -encoding UTF-8 -d xiaozhi-android/build/classes-java \
  xiaozhi-android/core-protocol/src/main/java/org/concentus/*.java
echo "OK: xiaozhi-android/build/classes-java"

echo "== [2/4] 编译 core-protocol 主源码（Kotlin）="
"$JAVA" -Xmx1g -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 \
  -cp "$RUNTIME_CP;xiaozhi-android/build/classes-java" \
  -d xiaozhi-android/build/classes xiaozhi-android/core-protocol/src/main/kotlin
echo "OK: xiaozhi-android/build/classes"

if [ -d xiaozhi-android/core-protocol/src/test/kotlin ]; then
  echo "== [3/4] 编译测试源码 =="
  "$JAVA" -Xmx1g -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 \
    -cp "$RUNTIME_CP;$TEST_CP;xiaozhi-android/build/classes;xiaozhi-android/build/classes-java" \
    -d xiaozhi-android/build/test-classes xiaozhi-android/core-protocol/src/test/kotlin
  echo "OK: xiaozhi-android/build/test-classes"

  echo "== [4/4] 运行 JUnit 测试 =="
  "$JAVA" -jar "$DL/junit-platform-console-standalone-1.11.3.jar" execute \
    -cp "$RUNTIME_CP;$TEST_CP;xiaozhi-android/build/classes;xiaozhi-android/build/classes-java;xiaozhi-android/build/test-classes" \
    --scan-class-path --details=tree --fail-if-no-tests
else
  echo "== [3/4] 无测试源码，跳过 =="
fi
