#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
User-Agent 对照矩阵实测（回答"已绑定但手机没反应会不会是 UA 的锅"）

背景：
  ESP32 固件 SystemInfo::GetUserAgent()（main/system_info.cc）:
      return std::string(BOARD_NAME "/") + app_desc->version;
  即原生格式是 "{板型}/{固件版本}"，例如 bread-compact-wifi/2.1.0

  而 py-xiaozhi 与本项目用的是 "{板型}/{应用名}-{版本}"
  例如 bread-compact-wifi/xiaozhi-android-2.1.6，中间多一节。

本脚本对同一服务器逐项实测：
  1. 各 UA 下 OTA 是否被接受（200 / 400）
  2. 是否下发 activation（未绑定判据）
  3. websocket.token 是否 test-token
  4. **带 UA 的 /activate 返回什么**（e2e_regression.py 里的 activate 不发 UA，
     而 App 是发的 —— 这条路径此前从未被测过，正是"输码后无反应"的嫌疑点）
"""
import hashlib
import hmac as hmac_mod
import json
import os
import urllib.error
import urllib.request
import uuid

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
BOARD_TYPE = "bread-compact-wifi"
APP_NAME = "xiaozhi-android"
APP_VERSION = "2.1.6"

CASES = [
    ("当前实现(py 风格)", f"{BOARD_TYPE}/{APP_NAME}-{APP_VERSION}"),
    ("ESP32 原生(板型/固件版本)", f"{BOARD_TYPE}/2.1.6"),
    ("ESP32 原生(真实固件版本)", f"{BOARD_TYPE}/2.1.0"),
    ("不带 UA", None),
    ("已知失败对照(客户端自报格式)", "Xiaozhi/2.2.2 (esp32s3) IDF/v5.4.0"),
]


def new_identity():
    mac = "02:%02x:%02x:%02x:%02x:%02x" % tuple(os.urandom(5))
    clean = mac.replace(":", "")
    sn = "SN-" + hashlib.md5(clean.encode()).hexdigest()[:8].upper() + "-" + clean
    return {
        "device_id": mac,
        "client_id": str(uuid.uuid4()),
        "serial_number": sn,
        "hmac_key": os.urandom(32).hex(),
    }


def ota(ident, ua):
    body = json.dumps({
        "application": {"version": APP_VERSION, "elf_sha256": ident["hmac_key"]},
        "board": {"type": BOARD_TYPE, "name": APP_NAME, "ip": "192.168.1.100",
                  "mac": ident["device_id"]},
    }, ensure_ascii=False).encode()
    hdr = {
        "Activation-Version": "2",
        "Device-Id": ident["device_id"],
        "Client-Id": ident["client_id"],
        "Serial-Number": ident["serial_number"],
        "Accept-Language": "zh-CN",
        "Content-Type": "application/json",
    }
    if ua is not None:
        hdr["User-Agent"] = ua
    req = urllib.request.Request(OTA_URL, data=body, headers=hdr, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, json.loads(r.read().decode("utf-8", "ignore"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "ignore")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"_raw": raw[:200]}
    except Exception as e:
        return None, {"_error": f"{type(e).__name__}: {e}"}


def activate(ident, challenge, ua):
    """带 UA 的 /activate —— 与 App 的 OtaClient.activate 行为一致"""
    sig = hmac_mod.new(ident["hmac_key"].encode(), challenge.encode(), hashlib.sha256).hexdigest()
    payload = json.dumps({"Payload": {
        "algorithm": "hmac-sha256",
        "serial_number": ident["serial_number"],
        "challenge": challenge,
        "hmac": sig,
    }}).encode()
    hdr = {
        "Activation-Version": "2",
        "Device-Id": ident["device_id"],
        "Client-Id": ident["client_id"],
        "Content-Type": "application/json",
    }
    if ua is not None:
        hdr["User-Agent"] = ua
    req = urllib.request.Request(OTA_URL + "activate", data=payload, headers=hdr, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, r.read().decode("utf-8", "ignore")[:120]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")[:120]
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


def main():
    print("=" * 78)
    print("User-Agent 对照矩阵（服务端 api.tenclass.net）")
    print("ESP32 原生格式: {BOARD_NAME}/{固件版本}  本项目: {板型}/{应用名}-{版本}")
    print("=" * 78)

    for label, ua in CASES:
        ident = new_identity()
        code, cfg = ota(ident, ua)
        shown = ua if ua else "(无)"
        print(f"\n--- {label}")
        print(f"    UA: {shown}")

        if code != 200:
            print(f"    OTA: HTTP {code}  << 被拒绝")
            print(f"    响应: {str(cfg)[:160]}")
            continue

        act = cfg.get("activation") or {}
        ws = cfg.get("websocket") or {}
        token = ws.get("token", "")
        has_act = bool(act.get("code"))
        print(f"    OTA: 200  下发激活码={has_act}  token={'test-token(测试组)' if token == 'test-token' else token[:16] + '…(真实凭据)'}")

        if has_act:
            a_code, a_body = activate(ident, act["challenge"], ua)
            # 200=已绑定成功 / 202=等待用户在 xiaozhi.me 输码 / 4xx=被拒
            meaning = {200: "已绑定", 202: "等待输码"}.get(a_code, "异常/被拒")
            print(f"    /activate(带 UA): HTTP {a_code} = {meaning}")
            print(f"    响应: {a_body}")
            # 不发 UA 的对照
            n_code, n_body = activate(ident, act["challenge"], None)
            print(f"    /activate(无 UA): HTTP {n_code}  响应: {n_body}")
        else:
            print("    （未下发 activation，跳过 /activate）")

    print("\n" + "=" * 78)
    print("结论判读：")
    print("  - 只要 OTA 200 且下发 activation，UA 就不是'输码后无反应'的原因")
    print("  - 若某 UA 下 /activate 返回 4xx 而非 202，那才是 UA 的锅")
    print("=" * 78)


if __name__ == "__main__":
    main()
