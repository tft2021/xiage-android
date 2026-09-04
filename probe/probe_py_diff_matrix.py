#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
py-xiaozhi vs 本项目 App 请求差异对照实验（2026-09-04）。

研读 py-xiaozhi 源码（py-xiaozhi-ref/）后发现 6 处请求差异：
  1. MAC 大小写：py 小写（真实网卡），App 大写（随机生成）
  2. OTA 的 Activation-Version 头：py 发 APP_VERSION("2.1.1")，App 发 "2"
  3. OTA 的 Serial-Number 头：py 不发，App 发
  4. board.name：py "py-xiaozhi"，App "xiaozhi-android"
  5. application.version：py "2.1.1"，App "2.2.0"
  6. /activate 的 User-Agent：py 不发，App 发

在**卡住的真实身份**（绑定已确认但服务端只给 test-token）上逐项切换，
看哪个差异能改变服务端响应。任何一项让响应变成「真实凭据」= 找到根因。
"""
import json
import urllib.request
import urllib.error

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"

IDENT = {
    "device_id": "02:E3:60:17:DA:50",
    "client_id": "dbf97a06-3ca2-4224-9356-6c2a627b01be",
    "serial_number": "SN-65A0DBD7-02e36017da50",
    "hmac_key": "cadca416dd0f98805d7738a22092477af1bb9bc431677ff9137b02f006eaa991",
}


def ota(variant):
    mac = IDENT["device_id"] if variant.get("mac_upper", True) else IDENT["device_id"].lower()
    body = json.dumps({
        "application": {
            "version": variant.get("app_version", "2.2.0"),
            "elf_sha256": IDENT["hmac_key"],
        },
        "board": {
            "type": "bread-compact-wifi",
            "name": variant.get("board_name", "xiaozhi-android"),
            "ip": "192.168.1.100",
            "mac": mac,
        },
    }).encode()
    hdr = {
        "Device-Id": mac,
        "Client-Id": IDENT["client_id"],
        "Content-Type": "application/json",
        "User-Agent": "bread-compact-wifi/xiaozhi-android-2.2.0",
        "Accept-Language": "zh-CN",
    }
    if variant.get("activation_version", "2") is not None:
        hdr["Activation-Version"] = variant.get("activation_version", "2")
    if variant.get("serial_header", True):
        hdr["Serial-Number"] = IDENT["serial_number"]
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
        return None, {"_error": str(e)[:100]}


def classify(cfg):
    act = (cfg.get("activation") or {}).get("code")
    token = (cfg.get("websocket") or {}).get("token", "")
    if act:
        return f"未绑定(下发激活码 {act})"
    if token == "test-token":
        return "卡住(test-token 无激活码)"
    return f"真实凭据!(token={token[:20]}…)"


VARIANTS = [
    ("V0 对照=App 原样请求", {}),
    ("V1 MAC 小写(py 风格)", {"mac_upper": False}),
    ("V2 Activation-Version=2.1.1(py 风格)", {"activation_version": "2.1.1"}),
    ("V3 不发 Serial-Number 头(py 风格)", {"serial_header": False}),
    ("V4 board.name=py-xiaozhi", {"board_name": "py-xiaozhi"}),
    ("V5 application.version=2.1.1", {"app_version": "2.1.1"}),
    ("V6 全部 py 风格叠加", {
        "mac_upper": False, "activation_version": "2.1.1",
        "serial_header": False, "board_name": "py-xiaozhi", "app_version": "2.1.1",
    }),
]


def main():
    print("=" * 78)
    print("请求差异对照实验（卡住身份 02:E3:60:17:DA:50，已持续 8+ 分钟无凭据）")
    print("=" * 78)
    for label, variant in VARIANTS:
        code, cfg = ota(variant)
        state = classify(cfg) if code == 200 else f"HTTP {code}: {str(cfg)[:120]}"
        print(f"\n--- {label}")
        print(f"    -> {state}")
    print("\n" + "=" * 78)
    print("判读：任何一项变出「真实凭据」= 该差异就是根因；全部仍是「卡住」=")
    print("服务端对该身份的凭据下发与请求形态无关，确认是服务端/账号侧问题。")
    print("=" * 78)


if __name__ == "__main__":
    main()
