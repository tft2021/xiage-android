#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通过 GitHub REST API 创建提交（绕过会挂死的 git push 大包上传）。

背景：本机到 github.com 的 git-upload-pack 常在大包时 "unexpected disconnect while
reading sideband packet"，push 挂死十几分钟仍失败；但 REST API 单次小请求稳定。
本工具把「本次要提交的文件」逐个建 blob -> 建 tree -> 建 commit -> 快进 main，
41 秒可完成 134 文件的提交。

用法：
    python probe/api_commit.py -m "提交信息"                 # 默认提交 git 已暂存的文件
    python probe/api_commit.py -m "提交信息" path/a path/b   # 指定文件（相对仓库根）
    python probe/api_commit.py -F msg.txt                    # 提交信息从文件读

说明：只支持新增/修改；删除文件暂未支持（用 git push 或手工补 tree 条目 sha=null）。
"""
import argparse
import base64
import concurrent.futures
import json
import os
import subprocess
import sys
import urllib.request

REPO = "tft2021/xiage-android"
BRANCH = "main"
ROOT = r"D:\code\虾哥小智"
GCM = r"C:/Users/T/.workbuddy/binaries/PortableGit/versions/1.2.0/mingw64/bin/git-credential-manager.exe"


def get_token():
    out = subprocess.run(
        [GCM, "get"], input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True, timeout=30).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    raise RuntimeError("未取到 token，请检查 git-credential-manager 是否已登录 github.com")


TOKEN = get_token()
HDR = {
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/vnd.github+json",
    "Content-Type": "application/json",
    "User-Agent": "xiaozhi-ci-helper",
}


def api(method, path, payload=None, retries=4):
    data = json.dumps(payload).encode() if payload is not None else None
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(
                f"https://api.github.com{path}", data=data, headers=HDR, method=method)
            with urllib.request.urlopen(req, timeout=90) as r:
                return json.loads(r.read().decode())
        except Exception as e:          # 网络抖动重试
            last = e
            print(f"  重试 {i + 1}/{retries}: {e}", flush=True)
    raise last


def staged_files():
    out = subprocess.run(["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"],
                         cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return [l.strip().replace("\\", "/") for l in out.splitlines() if l.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-m", "--message")
    ap.add_argument("-F", "--message-file")
    ap.add_argument("files", nargs="*", help="相对仓库根的路径；省略则用 git 已暂存文件")
    args = ap.parse_args()

    if args.message_file:
        with open(args.message_file, encoding="utf-8") as f:
            message = f.read().strip()
    elif args.message:
        message = args.message
    else:
        ap.error("需要 -m 或 -F 指定提交信息")

    files = args.files or staged_files()
    files = sorted({f.replace("\\", "/").lstrip("./") for f in files})
    if not files:
        ap.error("没有要提交的文件")

    # 本地存在性校验，避免建了一半 blob 才发现路径写错
    for rel in files:
        full = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.isfile(full):
            ap.error(f"文件不存在: {rel}")
    print(f"待提交 {len(files)} 个文件", flush=True)

    ref = api("GET", f"/repos/{REPO}/git/ref/heads/{BRANCH}")
    base_sha = ref["object"]["sha"]
    base_tree = api("GET", f"/repos/{REPO}/git/commits/{base_sha}")["tree"]["sha"]
    print(f"base {base_sha[:10]}  tree {base_tree[:10]}", flush=True)

    def make_blob(rel):
        with open(os.path.join(ROOT, rel.replace("/", os.sep)), "rb") as f:
            content = f.read()
        if b"\x00" in content:
            return rel, None, "二进制文件，暂不支持"
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
                print(f"  blob {i + 1}/{len(files)}", flush=True)
    print(f"blob 完成: {len(entries)}", flush=True)

    tree = api("POST", f"/repos/{REPO}/git/trees", {"base_tree": base_tree, "tree": entries})
    print(f"tree {tree['sha'][:10]} ({len(tree['tree'])} 项, truncated={tree.get('truncated')})",
          flush=True)

    commit = api("POST", f"/repos/{REPO}/git/commits", {
        "message": message,
        "tree": tree["sha"],
        "parents": [base_sha],
    })
    print(f"commit {commit['sha']}", flush=True)

    api("PATCH", f"/repos/{REPO}/git/refs/heads/{BRANCH}",
        {"sha": commit["sha"], "force": False})
    print(f"main -> {commit['sha']}", flush=True)
    print(commit["sha"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
