#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
持续监控调试版当前身份在服务端的状态转换（2026-09-04）。

已知起点（11:49 探测）：无激活码 + test-token（绑定已确认但凭据不下发）。
本脚本每 60s 拉一次 OTA，记录状态何时/如何变化：
  - 变成「返回激活码」    -> 绑定被服务端回滚（和旧身份 BE:C8 一样的生命周期）
  - 变成「真实凭据」      -> 迟到的凭据终于下发（App 应该已自动连上）
  - 保持不动             -> 卡死状态持续
"""
import json
import time
import urllib.request
import urllib.error

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
UA = "bread-compact-wifi/xiaozhi-android-2.2.0"

IDENT = {
    "device_id": "02:E3:60:17:DA:50",
    "client_id": "dbf97a06-3ca2-4224-9356-6c2a627b01be",
    "serial_number": "SN-65A0DBD7-02e36017da50",
    "hmac_key": "cadca416dd0f98805d7738a22092477af1bb9bc431677ff9137b02f006eaa991",
}


def ota():
    body = json.dumps({
        "application": {"version": "2.2.0", "elf_sha256": IDENT["hmac_key"]},
        "board": {"type": "bread-compact-wifi", "name": "xiaozhi-android",
                  "ip": "192.168.1.100", "mac": IDENT["device_id"]},
    }).encode()
    hdr = {
        "Activation-Version": "2",
        "Device-Id": IDENT["device_id"],
        "Client-Id": IDENT["client_id"],
        "Serial-Number": IDENT["serial_number"],
        "User-Agent": UA,
        "Accept-Language": "zh-CN",
        "Content-Type": "application/json",
    }
    req = urllib.request.Request(OTA_URL, data=body, headers=hdr, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, json.loads(r.read().decode("utf-8", "ignore"))
    except Exception as e:
        return None, {"_error": str(e)[:100]}


def classify(cfg):
    if cfg is None:
        return "请求失败"
    act = (cfg.get("activation") or {}).get("code")
    token = (cfg.get("websocket") or {}).get("token", "")
    if act:
        return f"未绑定(code={act})"
    if token == "test-token":
        return "卡住(test-token无激活码)"
    return f"已下发真实凭据(token前16={token[:16]})"


def main():
    print("监控当前身份 02:E3:60:17:DA:50 的服务端状态（每60s一次，共20次/20分钟）")
    last = None
    for i in range(20):
        code, cfg = ota()
        state = classify(cfg if code == 200 else None)
        ts = time.strftime("%H:%M:%S")
        if state != last:
            print(f"[{ts}] #{i+1} HTTP {code} -> {state}", flush=True)
            last = state
        else:
            print(f"[{ts}] #{i+1} 不变: {state}", flush=True)
        if "真实凭据" in state:
            print("!! 凭据已下发——App 应已自动连接，监控结束", flush=True)
            break
        time.sleep(60)
    print("监控结束")


if __name__ == "__main__":
    main()
