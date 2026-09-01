#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
排查「凭据仍为测试组」：对比不同 appName / UA 形态下，
官方 OTA 服务器是否下发 activation 段（激活码 + challenge）。

假设：OtaClient 默认 appName="xiaozhi-android"（UA=bread-compact-wifi/xiaozhi-android-2.1.1）
     服务端不认识该 app 名 -> 不下发 activation -> App 判定无需激活 -> 直接报测试组凭据。

用例（每个用例用独立身份，避免相互污染）：
  A  py-xiaozhi      形态（e2e T1 已验证会下发激活码，作为基线）
  B  xiaozhi-android 形态（App 当前实际形态）
  C  UA=xiaozhi-android 但 body board.name=py-xiaozhi（定位是 UA 还是 body 决定）
  D  UA=py-xiaozhi 但 body board.name=xiaozhi-android（同上，反向）
"""
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
BOARD_TYPE = "bread-compact-wifi"
APP_VERSION = "2.1.1"


def make_identity():
    mac = "AA:BB:CC:%02X:%02X:%02X" % (os.urandom(1)[0], os.urandom(1)[0], os.urandom(1)[0] or 1)
    mac_clean = mac.lower().replace(":", "")
    sn = "SN-" + hashlib.md5(mac_clean.encode()).hexdigest()[:8].upper() + "-" + mac_clean
    return {
        "device_id": mac,
        "client_id": str(uuid.uuid4()),
        "serial_number": sn,
        "hmac_key": os.urandom(32).hex(),
    }


def ota(ident, ua_app_name, body_app_name):
    body = json.dumps({
        "application": {"version": APP_VERSION, "elf_sha256": ident["hmac_key"]},
        "board": {
            "type": BOARD_TYPE,
            "name": body_app_name,
            "ip": "192.168.1.100",
            "mac": ident["device_id"],
        },
    }, ensure_ascii=False).encode()
    hdr = {
        "Activation-Version": "2",
        "Device-Id": ident["device_id"],
        "Client-Id": ident["client_id"],
        "Serial-Number": ident["serial_number"],
        "User-Agent": f"{BOARD_TYPE}/{ua_app_name}-{APP_VERSION}",
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


def summarize(label, ua_app, body_app, st, resp):
    act = resp.get("activation") or {}
    ws = resp.get("websocket") or {}
    mqtt = resp.get("mqtt") or {}
    code = str(act.get("code", ""))
    token = ws.get("token", "")
    cid = mqtt.get("client_id", "")
    print(f"\n[{label}]  UA={BOARD_TYPE}/{ua_app}-{APP_VERSION}  board.name={body_app}")
    print(f"  HTTP {st}")
    print(f"  activation.code = {code!r}  challenge = {'有' if act.get('challenge') else '无'}")
    print(f"  websocket.token = {token!r}")
    print(f"  mqtt.client_id  = {cid!r}")
    if "_raw" in resp or "_error" in resp:
        print(f"  raw = {json.dumps(resp, ensure_ascii=False)[:200]}")
    return {
        "label": label,
        "has_activation": len(code) == 6,
        "is_test_group": token == "test-token",
    }


def main():
    print("=" * 66)
    print("appName / UA 形态对比探测（目标：确认 activation 下发的决定因素）")
    print("=" * 66)
    results = []

    ident = make_identity()
    print(f"\n（每个用例独立身份，首个 Device-Id={ident['device_id']}）")

    # A：基线 py-xiaozhi
    st, resp = ota(make_identity(), "py-xiaozhi", "py-xiaozhi")
    results.append(summarize("A 基线", "py-xiaozhi", "py-xiaozhi", st, resp))

    # B：App 当前形态
    st, resp = ota(make_identity(), "xiaozhi-android", "xiaozhi-android")
    results.append(summarize("B App现状", "xiaozhi-android", "xiaozhi-android", st, resp))

    # C：UA=xiaozhi-android，body.name=py-xiaozhi
    st, resp = ota(make_identity(), "xiaozhi-android", "py-xiaozhi")
    results.append(summarize("C UA异", "xiaozhi-android", "py-xiaozhi", st, resp))

    # D：UA=py-xiaozhi，body.name=xiaozhi-android
    st, resp = ota(make_identity(), "py-xiaozhi", "xiaozhi-android")
    results.append(summarize("D body异", "py-xiaozhi", "xiaozhi-android", st, resp))

    print("\n" + "=" * 66)
    print("结论")
    print("=" * 66)
    for r in results:
        mark = "下发激活码" if r["has_activation"] else "无 activation 段"
        print(f"  {r['label']:<10} : {mark}  test-group={r['is_test_group']}")

    has_a = [r for r in results if r["has_activation"]]
    if results[0]["has_activation"] and not results[1]["has_activation"]:
        if results[3]["has_activation"]:
            print("\n>>> 判定：body.board.name 是决定因素（UA 可异）。")
            print(">>> 修复方向：OtaClient 保持 UA 为 xiaozhi-android 亦可，但 board.name 改 py-xiaozhi。")
        elif results[2]["has_activation"]:
            print("\n>>> 判定：User-Agent 是决定因素（body 可异）。")
            print(">>> 修复方向：OtaClient 的 UA appName 改为 py-xiaozhi。")
        else:
            print("\n>>> 判定：UA 与 board.name 都必须是 py-xiaozhi。")
            print(">>> 修复方向：OtaClient appName 整体改为 py-xiaozhi。")
    elif all(r["has_activation"] for r in results):
        print("\n>>> 判定：appName 与 activation 下发无关！问题在别处")
        print(">>> （需检查 App 端头序、Device-Id 格式、身份持久化等其它差异）。")
    else:
        print("\n>>> 判定：异常情况，基线 A 未下发激活码，网络/服务端行为可能已变化，需复测。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
