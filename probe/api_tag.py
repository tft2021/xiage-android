#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通过 GitHub REST API 创建 tag（本机 git push 大包会 unexpected disconnect 挂死）。

用法：
    python probe/api_tag.py v1.0.3                 # 打在当前 main 上
    python probe/api_tag.py v1.0.3 <commit-sha>    # 打在指定提交上

创建后 .github/workflows/build-release.yml 的 tags: ['v*'] 会被触发，
构建产物会自动创建 GitHub Release。
"""
import json
import subprocess
import sys
import urllib.request

REPO = "tft2021/xiage-android"
BRANCH = "main"
GCM = r"C:/Users/T/.workbuddy/binaries/PortableGit/versions/1.2.0/mingw64/bin/git-credential-manager.exe"


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
    with urllib.request.urlopen(req, timeout=90) as r:
        if r.status == 204:
            return {}
        return json.loads(r.read().decode())


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    tag = sys.argv[1]
    sha = sys.argv[2] if len(sys.argv) > 2 else \
        api("GET", f"/repos/{REPO}/git/ref/heads/{BRANCH}")["object"]["sha"]

    commit = api("GET", f"/repos/{REPO}/git/commits/{sha}")
    print(f"tag  {tag}\n指向 {sha[:10]}  {commit['message'].splitlines()[0]}")

    api("POST", f"/repos/{REPO}/git/refs", {"ref": f"refs/tags/{tag}", "sha": sha})
    print(f"已创建 refs/tags/{tag}")

    # 同步本地（远端跟踪引用写入会静默失败，这里手工补 .git/refs/remotes/origin/main）
    if len(sys.argv) <= 2:
        subprocess.run(["git", "update-ref", f"refs/remotes/origin/{BRANCH}", sha],
                       cwd=r"D:\code\虾哥小智", capture_output=True)
    print("CI 已触发，Release 构建完成后自动生成。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
