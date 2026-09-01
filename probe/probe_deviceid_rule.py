#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第三轮：锁定 Device-Id 规则——服务端是不是拒绝所有 02 开头的 MAC？

用例（全部全新身份，唯一变量是 Device-Id 首字节）：
  F1  02:xx:xx:xx:xx:xx（App randomMacLike 的真实形态，随机）
  F2  03:xx:xx:xx:xx:xx
  F3  5C:xx:xx:xx:xx:xx（真实 OUI 段）
  F4  AA:xx:xx:xx:xx:xx（e2e 基线段，已知可用）
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
APP_NAME = "xiaozhi-android"
APP_VERSION = "2.1.1"


def ota(mac):
    mac_clean = mac.lower().replace(":", "")
    sn = "SN-" + hashlib.md5(mac_clean.encode()).hexdigest()[:8].upper() + "-" + mac_clean
    body = json.dumps({
        "application": {"version": APP_VERSION, "elf_sha256": os.urandom(32).hex()},
        "board": {"type": BOARD_TYPE, "name": APP_NAME, "ip": "192.168.1.100", "mac": mac},
    }).encode()
    hdr = {
        "Activation-Version": "2",
        "Device-Id": mac,
        "Client-Id": str(uuid.uuid4()),
        "Serial-Number": sn,
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


def main():
    cases = [
        ("F1  02 开头(App形态)", "%02X:%02X:%02X:%02X:%02X:%02X" % ((0x02,) + tuple(os.urandom(5)))),
        ("F2  03 开头",         "%02X:%02X:%02X:%02X:%02X:%02X" % ((0x03,) + tuple(os.urandom(5)))),
        ("F3  5C 开头(真实OUI)", "%02X:%02X:%02X:%02X:%02X:%02X" % ((0x5C,) + tuple(os.urandom(5)))),
        ("F4  AA 开头(基线段)",  "%02X:%02X:%02X:%02X:%02X:%02X" % ((0xAA,) + tuple(os.urandom(5)))),
    ]
    print("=" * 66)
    print("Device-Id 首字节规则验证")
    print("=" * 66)
    results = []
    for name, mac in cases:
        st, resp = ota(mac)
        act = resp.get("activation") or {}
        code = str(act.get("code", ""))
        has = len(code) == 6
        token = (resp.get("websocket") or {}).get("token", "")
        print(f"  {name}  MAC={mac}")
        print(f"      HTTP {st}  activation={'下发 ' + code if has else '无'}  token={token!r}")
        results.append((name, has))
    print("\n汇总：")
    for name, has in results:
        print(f"  {name}: {'下发激活码' if has else '无激活码 <-- 服务端拒绝该形态'}")


if __name__ == "__main__":
    sys.exit(main())
