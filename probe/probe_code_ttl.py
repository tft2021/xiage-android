#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
激活码有效期 / 刷新规律实测

用户真机现象：「每按一次连接激活码就变一次」
但此前实测（间隔 3 秒连续 OTA）同一身份激活码恒定不变。
两者矛盾 => 猜测激活码存在 TTL（过期后服务端重新生成），
或服务端在距上次 OTA 超过一定时间后刷新激活码。

本脚本用【同一身份】按递增间隔反复 OTA，记录 activation.code 与 challenge：
    0s / 10s / 30s / 60s / 180s / 300s

判读：
  - 码一直不变        -> TTL 更长或不存在；用户看到的"码变了"是客户端身份变了
  - 某次之后码变了    -> 激活码有 TTL，用户必须"看到码后立刻去输"
  - challenge 每次都变 -> 已知行为（服务端每次 OTA 刷新 challenge）
"""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
BOARD_TYPE = "bread-compact-wifi"
APP_NAME = "xiaozhi-android"
APP_VERSION = "2.1.6"
UA = f"{BOARD_TYPE}/{APP_NAME}-{APP_VERSION}"

GAPS = [0, 10, 30, 60, 180, 300]


def new_identity():
    mac = "02:%02x:%02x:%02x:%02x:%02x" % tuple(os.urandom(5))
    clean = mac.replace(":", "")
    sn = "SN-" + hashlib.md5(clean.encode()).hexdigest()[:8].upper() + "-" + clean
    return {"device_id": mac, "client_id": str(uuid.uuid4()),
            "serial_number": sn, "hmac_key": os.urandom(32).hex()}


def ota(ident):
    body = json.dumps({
        "application": {"version": APP_VERSION, "elf_sha256": ident["hmac_key"]},
        "board": {"type": BOARD_TYPE, "name": APP_NAME, "ip": "192.168.1.100",
                  "mac": ident["device_id"]},
    }, ensure_ascii=False).encode()
    hdr = {"Activation-Version": "2", "Device-Id": ident["device_id"],
           "Client-Id": ident["client_id"], "Serial-Number": ident["serial_number"],
           "User-Agent": UA, "Accept-Language": "zh-CN", "Content-Type": "application/json"}
    req = urllib.request.Request(OTA_URL, data=body, headers=hdr, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, json.loads(r.read().decode("utf-8", "ignore"))
    except urllib.error.HTTPError as e:
        return e.code, {"_raw": e.read().decode("utf-8", "ignore")[:200]}
    except Exception as e:
        return None, {"_error": f"{type(e).__name__}: {e}"}


def main():
    ident = new_identity()
    print("=" * 84)
    print("激活码刷新规律实测（同一身份，间隔递增 OTA）")
    print(f"device_id = {ident['device_id']}")
    print("=" * 84)
    sys.stdout.flush()

    first_code = None
    prev_code = None
    elapsed = 0

    for gap in GAPS:
        if gap:
            print(f"\n  等待 {gap}s ...", end="", flush=True)
            time.sleep(gap)
            print(" 继续")
        else:
            print()
        elapsed += gap
        st, cfg = ota(ident)
        act = cfg.get("activation") or {}
        code = act.get("code")
        challenge = act.get("challenge")
        if first_code is None:
            first_code = code

        changed = "" if prev_code is None else ("  <<< 码变了！" if code != prev_code else "  (与上次相同)")
        print(f"  t=+{elapsed:>4}s  HTTP {st}  code={code}  challenge={challenge}{changed}")
        sys.stdout.flush()
        prev_code = code
        if st != 200:
            print(f"    请求失败: {str(cfg)[:150]}")
            break

    print("\n" + "=" * 84)
    print(f"首次激活码: {first_code}   最后一次: {prev_code}")
    if first_code == prev_code:
        print("结论: 6 分钟内激活码【未刷新】。用户看到的『码变了』说明是客户端身份变了，")
        print("      请检查 SharedPreferences 里的 device_id 是否被覆盖/丢失。")
    else:
        print("结论: 激活码会随时间刷新（存在 TTL）。用户必须『看到码后立刻去 xiaozhi.me 输』，")
        print("      且不能再点连接刷新 —— 否则网页绑的是旧码，永远生效不了。")
    print("=" * 84)


if __name__ == "__main__":
    main()
