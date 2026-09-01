cmake_minimum_required(VERSION 3.22.1)
project(xiaozhi-opus LANGUAGES C)

# ---------------------------------------------------------------------------
# libopus
#
# 默认从 Xiph 官方仓库拉取并静态链接。若构建环境无法联网，改为本地 vendoring：
#   1. 下载 https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz
#   2. 解压到 app/src/main/cpp/opus/
#   3. 把下面的 FetchContent 段整体替换为：
#        add_subdirectory(opus)
# ---------------------------------------------------------------------------
include(FetchContent)

set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DISABLE_INTRINSICS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        opus
        GIT_REPOSITORY https://github.com/xiph/opus.git
        GIT_TAG        v1.5.2
        GIT_SHALLOW    TRUE
        GIT_PROGRESS   TRUE
)

# 只取 opus 子目录，跳过仓库里不需要的部分
FetchContent_MakeAvailable(opus)

# ---------------------------------------------------------------------------
# JNI 桥接层
# ---------------------------------------------------------------------------
add_library(
        xiaozhi-opus
        SHARED
        opus_jni.cpp
)

target_include_directories(xiaozhi-opus PRIVATE ${opus_SOURCE_DIR}/include)

target_link_libraries(xiaozhi-opus PRIVATE Opus::opus log android)
