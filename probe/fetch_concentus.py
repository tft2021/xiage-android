#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 lostromb/concentus 抓取 Java 端口源码，内嵌到 core-protocol/src/main/java/org/concentus/"""
import json
import os
import sys
import urllib.request
import concurrent.futures

DEST = r"D:\code\虾哥小智\xiaozhi-android\core-protocol\src\main\java"
TREE_SHA = "ec8d382b5e0c7f89e5293a4c09f1650cde966377"
RAW = "https://raw.githubusercontent.com/lostromb/concentus/master/Java/"


def main():
    tree = json.load(urllib.request.urlopen(
        f"https://api.github.com/repos/lostromb/concentus/git/trees/{TREE_SHA}?recursive=1"))
    files = [t for t in tree["tree"]
             if t["type"] == "blob" and t["path"].endswith(".java")
             and "TestConsole" not in t["path"] and "OpusApplication" not in t["path"]]
    print("待抓取:", len(files), flush=True)

    def fetch(t):
        url = RAW + t["path"]
        rel = t["path"].split("src/main/java/", 1)[1]
        out = os.path.join(DEST, rel)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        for attempt in range(5):
            try:
                data = urllib.request.urlopen(url, timeout=90).read()
                with open(out, "wb") as f:
                    f.write(data)
                return rel, len(data), None
            except Exception as e:
                err = e
        return rel, 0, str(err)

    with concurrent.futures.ThreadPoolExecutor(4) as ex:
        results = []
        for r in ex.map(fetch, files):
            results.append(r)
            if len(results) % 20 == 0:
                print(f"进度 {len(results)}/{len(files)}", flush=True)
    fails = [r for r in results if r[2]]
    ok = [r for r in results if not r[2]]
    print("成功:", len(ok), "失败:", len(fails))
    for f in fails[:15]:
        print("  FAIL", f)
    print("总字节:", sum(n for _, n, _ in ok))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
