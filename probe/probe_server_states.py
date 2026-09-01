#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第二轮排查：复现「凭据仍为测试组」——用 App 可能经历过的各种服务端状态序列，
找出哪条路径会导致 OTA 响应【不再下发 activation 段】。

用例序列（同一身份连续操作，模拟 App 真实经历）：
  E1  新身份首次 OTA                     -> 预期：下发激活码（基线）
  E2  同一身份【不激活】再次 OTA          -> ？（用户可能点了多次连接）
  E3  同一身份轮询 /activate 得到 202 后再 OTA -> ？（激活轮询中断过）
  E4  同一身份但 hmac_key 换成另一个值再 OTA -> ？（密钥错乱/重装丢 SP）
  E5  全新身份 Device-Id=02:00:00:00:00:00 OTA -> ？（Android 占位 MAC）
"""
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

import hmac as hmac_mod

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
BOARD_TYPE = "bread-compact-wifi"
APP_NAME = "xiaozhi-android"
APP_VERSION = "2.1.1"


def make_identity(mac=None):
    mac = mac or "AA:BB:CC:%02X:%02X:%02X" % (os.urandom(1)[0], os.urandom(1)[0], os.urandom(1)[0] or 1)
    mac_clean = mac.lower().replace(":", "")
    sn = "SN-" + hashlib.md5(mac_clean.encode()).hexdigest()[:8].upper() + "-" + mac_clean
    return {
        "device_id": mac,
        "client_id": str(uuid.uuid4()),
        "serial_number": sn,
        "hmac_key": os.urandom(32).hex(),
    }


def ota(ident):
    body = json.dumps({
        "application": {"version": APP_VERSION, "elf_sha256": ident["hmac_key"]},
        "board": {"type": BOARD_TYPE, "name": APP_NAME, "ip": "192.168.1.100",
                  "mac": ident["device_id"]},
    }).encode()
    hdr = {
        "Activation-Version": "2",
        "Device-Id": ident["device_id"],
        "Client-Id": ident["client_id"],
        "Serial-Number": ident["serial_number"],
        "User-Agent": f"{BOARD_TYPE}/{APP_NAME}-{APP_VERSION}",
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
            return e.code, {"_raw": raw}
    except Exception as e:
        return None, {"_error": f"{type(e).__name__}: {e}"}


def activate(ident, challenge):
    sig = hmac_mod.new(ident["hmac_key"].encode(), challenge.encode(), hashlib.sha256).hexdigest()
    payload = json.dumps({"Payload": {
        "algorithm": "hmac-sha256", "serial_number": ident["serial_number"],
        "challenge": challenge, "hmac": sig}}).encode()
    hdr = {"Activation-Version": "2", "Device-Id": ident["device_id"],
           "Client-Id": ident["client_id"], "Content-Type": "application/json"}
    req = urllib.request.Request(OTA_URL + "activate", data=payload, headers=hdr, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, r.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


def show(tag, st, resp):
    act = resp.get("activation") or {}
    ws = resp.get("websocket") or {}
    code = str(act.get("code", ""))
    token = ws.get("token", "")
    extra = ""
    if "_raw" in resp or "_error" in resp:
        extra = "  raw=" + json.dumps(resp, ensure_ascii=False)[:160]
    print(f"  {tag}: HTTP {st}  activation={'下发 ' + code if len(code) == 6 else '无'}  "
          f"token={token!r}{extra}")
    return len(code) == 6, token


def main():
    print("=" * 66)
    print("服务端状态机探测：哪条路径不再下发 activation")
    print("=" * 66)

    # ---- 场景 1：正常新身份 + 重复 OTA + 激活轮询后 OTA
    ident = make_identity()
    print(f"\n【场景1】身份 {ident['device_id']}")
    st, resp = ota(ident)
    ok1, _ = show("E1 首次OTA", st, resp)

    st, resp = ota(ident)
    ok2, _ = show("E2 重复OTA(未激活)", st, resp)

    ch = (resp.get("activation") or {}).get("challenge")
    if ch:
        st3, txt3 = activate(ident, ch)
        print(f"  E2.5 /activate -> HTTP {st3} {txt3[:80]}")
        st, resp = ota(ident)
        ok3, _ = show("E3 轮询202后再OTA", st, resp)
    else:
        print("  E3 跳过（E2 无 challenge）")
        ok3 = None

    # ---- 场景 2：hmac_key 错乱
    ident2 = make_identity()
    print(f"\n【场景2】身份 {ident2['device_id']}")
    st, resp = ota(ident2)
    ok4, _ = show("E4a 首次OTA注册K1", st, resp)

    ident2["hmac_key"] = os.urandom(32).hex()  # 密钥换成 K2（模拟重装/密钥错乱）
    st, resp = ota(ident2)
    ok5, _ = show("E4b 同身份换K2再OTA", st, resp)

    # ---- 场景 3：占位 MAC
    ident3 = make_identity("02:00:00:00:00:00")
    print(f"\n【场景3】占位 MAC {ident3['device_id']}")
    st, resp = ota(ident3)
    ok6, _ = show("E5 占位MAC首次OTA", st, resp)
    st, resp = ota(make_identity("02:00:00:00:00:00"))  # 另一个新 client_id 同占位 MAC
    ok7, _ = show("E6 占位MAC另一client_id", st, resp)

    print("\n" + "=" * 66)
    print("汇总（False/无 = 复现『不下发激活码』）")
    print("=" * 66)
    for name, ok in [("E1 新身份", ok1), ("E2 重复OTA", ok2), ("E3 轮询后OTA", ok3),
                     ("E4a 注册", ok4), ("E4b 换密钥", ok5),
                     ("E5 占位MAC", ok6), ("E6 占位MAC换CID", ok7)]:
        print(f"  {name:<16}: {'下发激活码' if ok else ('无激活码 <-- 嫌疑路径' if ok is not None else '跳过')}")


if __name__ == "__main__":
    sys.exit(main())
