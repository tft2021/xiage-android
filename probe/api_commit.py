#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通过 GitHub REST API 创建提交（绕过会挂死的 git push 大包上传）。
流程：读凭据 -> 取 base -> 逐文件建 blob -> 建 tree -> 建 commit -> 快进 main。
"""
import base64
import json
import os
import subprocess
import sys
import urllib.request
import concurrent.futures

REPO = "tft2021/xiage-android"
BRANCH = "main"
ROOT = r"D:\code\虾哥小智"
GCM = r"C:/Users/T/.workbuddy/binaries/PortableGit/versions/1.2.0/mingw64/bin/git-credential-manager.exe"

# 变更文件清单（与被打断的那次提交内容一致）
JAVA_DIR = os.path.join(ROOT, r"xiaozhi-android\core-protocol\src\main\java")
RELJava = "xiaozhi-android/core-protocol/src/main/java"
extra_files = [
    "build-local.sh",
    "probe/fetch_concentus.py",
    "xiaozhi-android/README.md",
    "xiaozhi-android/app/build.gradle.kts",
    "xiaozhi-android/app/src/main/java/com/xiaozhi/app/ui/XiaozhiViewModel.kt",
    "xiaozhi-android/core-protocol/src/main/kotlin/com/xiaozhi/protocol/audio/AudioIO.kt",
    "xiaozhi-android/core-protocol/src/main/kotlin/com/xiaozhi/protocol/session/XiaozhiSession.kt",
    "xiaozhi-android/core-protocol/src/main/kotlin/com/xiaozhi/protocol/audio/ConcentusCodecProvider.kt",
    "xiaozhi-android/core-protocol/src/test/kotlin/com/xiaozhi/protocol/audio/ConcentusCodecTest.kt",
]


def get_token():
    out = subprocess.run(
        [GCM, "get"], input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True, timeout=30).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    raise RuntimeError("未取到 token")


TOKEN = get_token()
HDR = {
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/vnd.github+json",
    "Content-Type": "application/json",
    "User-Agent": "xiaozhi-ci-helper",
}


def api(method, path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(f"https://api.github.com{path}", data=data,
                                 headers=HDR, method=method)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def collect_files():
    files = []
    for dirpath, _, names in os.walk(JAVA_DIR):
        for n in names:
            full = os.path.join(dirpath, n)
            rel = os.path.relpath(full, ROOT).replace("\\", "/")
            files.append(rel)
    files.extend(extra_files)
    return sorted(set(files))


def main():
    files = collect_files()
    print(f"待上传文件: {len(files)}", flush=True)

    ref = api("GET", f"/repos/{REPO}/git/ref/heads/{BRANCH}")
    base_sha = ref["object"]["sha"]
    base_commit = api("GET", f"/repos/{REPO}/git/commits/{base_sha}")
    base_tree = base_commit["tree"]["sha"]
    print(f"base commit: {base_sha[:10]}  tree: {base_tree[:10]}", flush=True)

    def make_blob(rel):
        with open(os.path.join(ROOT, rel.replace("/", os.sep)), "rb") as f:
            content = f.read()
        if b"\x00" in content:
            return rel, None, "跳过二进制"
        blob = api("POST", f"/repos/{REPO}/git/blobs",
                   {"content": base64.b64encode(content).decode(), "encoding": "base64"})
        return rel, blob["sha"], None

    entries = []
    with concurrent.futures.ThreadPoolExecutor(4) as ex:
        for i, (rel, sha, err) in enumerate(ex.map(make_blob, files)):
            if err:
                print(f"  跳过 {rel}: {err}")
                continue
            entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": sha})
            if (i + 1) % 30 == 0:
                print(f"  blob 进度 {i + 1}/{len(files)}", flush=True)
    print(f"blob 完成: {len(entries)}", flush=True)

    tree = api("POST", f"/repos/{REPO}/git/trees",
               {"base_tree": base_tree, "tree": entries})
    print(f"tree: {tree['sha'][:10]} ({len(tree['tree'])} 项, truncated={tree.get('truncated')})", flush=True)

    commit = api("POST", f"/repos/{REPO}/git/commits", {
        "message": "feat: 接入 Opus 编解码（Concentus 纯 Java 移植，源码内嵌）\n\n"
                   "- Concentus 全量 Java 源码（124 文件）内嵌至 core-protocol/src/main/java/org/concentus/，附 CONCENTUS-LICENSE\n"
                   "- 新增 ConcentusCodecProvider：上行 16k/mono/60ms VOIP 24kbps，下行按 hello 协商采样率（16k/24k）\n"
                   "- AudioCodecProvider.createDecoder() 增加 sampleRate 参数；Session 按下行采样率懒创建、协商变化自动重建\n"
                   "- XiaozhiViewModel 注入 ConcentusCodecProvider（替换 NoOp 降级实现）\n"
                   "- build-local.sh 增加 javac 步骤；新增 5 项编解码往返测试，全量 57/57 通过\n"
                   "- versionName 1.0.2 (versionCode 3)\n\n"
                   "（注：本提交经 GitHub API 创建，git push 大包在当前网络持续挂死）",
        "tree": tree["sha"],
        "parents": [base_sha],
    })
    print(f"commit: {commit['sha']}", flush=True)

    api("PATCH", f"/repos/{REPO}/git/refs/heads/{BRANCH}", {"sha": commit["sha"], "force": False})
    print("main 已更新", flush=True)
    print(commit["sha"])


if __name__ == "__main__":
    sys.exit(main())
