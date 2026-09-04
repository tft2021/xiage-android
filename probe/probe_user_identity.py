#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用调试版导出的真实身份探测服务端状态（2026-09-04）。

背景：调试版诊断显示 /activate 已返回 200（绑定确认），但 App 重新 OTA
仍拿测试组凭据（FetchingCredentials 循环）。本脚本用两个真实身份四元组
直接复现 App 的 OTA 请求，看服务端**现在**到底返回什么：

  1. 当前身份 02:E3:60:17:DA:50（11:48 自动重置出来的，用户刚输码绑定）
  2. 旧身份   BE:C8:02:E7:BA:65（上一次卡死时的身份，被判定不健康而换掉）

判读表（OTA 响应 -> 服务端视角）：
  - 有 activation.code      -> 服务端认为【未绑定】，等用户输码
  - 无 activation + test-token -> 退化/标记状态（绑定丢失或被风控）
  - 无 activation + 真实 token -> 已绑定（那就该能连上）
"""
import hashlib
import hmac as hmac_mod
import json
import urllib.error
import urllib.request

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
BOARD_TYPE = "bread-compact-wifi"
APP_NAME = "xiaozhi-android"
APP_VERSION = "2.2.0"  # 与调试版完全一致，不引入变量
UA = f"{BOARD_TYPE}/{APP_NAME}-{APP_VERSION}"

IDENTITIES = [
    ("当前身份(刚输码绑定, FetchingCredentials卡住)", {
        "device_id": "02:E3:60:17:DA:50",
        "client_id": "dbf97a06-3ca2-4224-9356-6c2a627b01be",
        "serial_number": "SN-65A0DBD7-02e36017da50",
        "hmac_key": "cadca416dd0f98805d7738a22092477af1bb9bc431677ff9137b02f006eaa991",
    }),
    ("旧身份(上次卡死后被自动换掉)", {
        "device_id": "BE:C8:02:E7:BA:65",
        "client_id": "36fca49a-153a-48de-8deb-6d34ad9d02b6",
        "serial_number": "SN-917BC998-bec802e7ba65",
        "hmac_key": "5f88415f659720c63fbaa69a4c78a340cd172bf0e793e84ba18ac0835a16f021",
    }),
]


def ota(ident):
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
        "User-Agent": UA,
        "Accept-Language": "zh-CN",
        "Content-Type": "application/json",
    }
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


def activate(ident, challenge):
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
        "Serial-Number": ident["serial_number"],
        "User-Agent": UA,
        "Content-Type": "application/json",
    }
    req = urllib.request.Request(OTA_URL + "activate", data=payload, headers=hdr, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, r.read().decode("utf-8", "ignore")[:160]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")[:160]
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


def main():
    print("=" * 78)
    print("用调试版导出的真实身份探测服务端（UA 与调试版一致:", UA + "）")
    print("=" * 78)
    for label, ident in IDENTITIES:
        print(f"\n===== {label} =====")
        print(f"  device={ident['device_id']}  serial={ident['serial_number']}")

        code, cfg = ota(ident)
        if code != 200:
            print(f"  OTA: HTTP {code}  响应: {str(cfg)[:200]}")
            continue

        act = cfg.get("activation") or {}
        ws = cfg.get("websocket") or {}
        mqtt = cfg.get("mqtt") or {}
        token = ws.get("token", "")
        if act.get("code"):
            state = "未绑定（等输码）"
        elif token == "test-token":
            state = "无激活码+测试组凭据（退化/被标记状态）"
        else:
            state = "已绑定（真实凭据）"
        print(f"  OTA: 200  -> 服务端视角: {state}")
        print(f"    activation.code = {act.get('code')}")
        print(f"    activation.challenge = {act.get('challenge', '')[:20]}…")
        print(f"    websocket.token = {token if token == 'test-token' else token[:16] + '…(' + str(len(token)) + '字符)'}")
        print(f"    websocket.url = {ws.get('url')}")
        print(f"    mqtt.client_id = {mqtt.get('client_id')}")
        print(f"    subscribe_topic = {mqtt.get('subscribe_topic')}")

        # 若服务端认为未绑定，用它的 challenge 走一次 /activate 看绑定判定
        if act.get("code"):
            a_code, a_body = activate(ident, act["challenge"])
            meaning = {200: "已绑定", 202: "等待输码"}.get(a_code, "异常/被拒")
            print(f"    /activate: HTTP {a_code} = {meaning}  body={a_body}")

        # 再拉一次 OTA 验证稳定性（排除瞬时抖动）
        code2, cfg2 = ota(ident)
        act2 = (cfg2.get("activation") or {}).get("code")
        token2 = (cfg2.get("websocket") or {}).get("token", "")
        print(f"  复核第二次 OTA: activation.code={act2} token={'test' if token2 == 'test-token' else '真实'}")

    print("\n" + "=" * 78)
    print("判读要点：")
    print("  当前身份若返回 activation.code -> 绑定已丢失（输码 200 后又被服务端重置）")
    print("  当前身份若 test-token 无激活码 -> 与退化 MAC 同款响应，身份被服务端标记")
    print("=" * 78)


if __name__ == "__main__":
    main()
