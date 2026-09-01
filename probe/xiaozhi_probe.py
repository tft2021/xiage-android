#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小智官方服务器 (xiaozhi.me / api.tenclass.net) 第三方客户端接入可行性探测脚本

协议来源：
  - OTA / 激活：78/xiaozhi-esp32  main/ota.cc
  - WebSocket：78/xiaozhi-esp32  docs/websocket.md

用法：
  python xiaozhi_probe.py
"""
import base64
import json
import os
import socket
import ssl
import struct
import sys
import urllib.error
import urllib.request
import uuid

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
HOST = "api.tenclass.net"
WS_PATH = "/xiaozhi/v1/"

# 真实固件 UA 格式见 main/system_info.cc：BOARD_NAME "/" app_version
# 服务端会解析该串校验 board type 与 app version，格式不符返回 400
UA = "Xiaozhi/1.7.2"
APP_VERSION = "1.7.2"


def hr(title):
    print("\n" + "=" * 62 + f"\n{title}\n" + "=" * 62)


# ---------------------------------------------------------------- OTA
def ota_check(device_id, client_id, activation_version, serial_number=None):
    body = json.dumps({
        "version": 2,
        "flash_size": 16777216,
        "minimum_free_heap_size": 8315496,
        "mac_address": device_id,
        "uuid": client_id,
        "chip_model_name": "esp32s3",
        "chip_info": {"model": 1, "cores": 2, "revision": 0, "features": 18},
        "application": {
            "name": "xiaozhi", "version": APP_VERSION,
            "compile_time": "2026-01-01T00:00:00Z", "idf_version": "v5.4.0",
        },
        "partition_table": [
            {"label": "app_0", "type": 0, "subtype": 16, "address": 65536, "size": 4194304}
        ],
        "ota": {"label": "app_0"},
    }, ensure_ascii=False).encode()

    hdr = {
        "Activation-Version": str(activation_version),
        "Device-Id": device_id,
        "Client-Id": client_id,
        "User-Agent": UA,
        "Accept-Language": "zh-CN",
        "Content-Type": "application/json",
    }
    if serial_number:
        hdr["Serial-Number"] = serial_number

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


def activate(device_id, client_id, payload=b"{}"):
    """POST {ota_url}/activate
    固件逻辑（main/ota.cc）：无 eFuse 序列号时 GetActivationPayload() 直接返回 "{}"
      - 200 -> 激活成功
      - 202 -> 等待用户在 xiaozhi.me 网页输入 6 位激活码
    """
    hdr = {
        "Activation-Version": "1",
        "Device-Id": device_id,
        "Client-Id": client_id,
        "User-Agent": UA,
        "Accept-Language": "zh-CN",
        "Content-Type": "application/json",
    }
    req = urllib.request.Request(
        OTA_URL + "activate", data=payload, headers=hdr, method="POST"
    )
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, r.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")


# ---------------------------------------------------------------- WebSocket
def _ws_recv(sock):
    def rd(n):
        d = b""
        while len(d) < n:
            c = sock.recv(n - len(d))
            if not c:
                return None
            d += c
        return d

    h = rd(2)
    if not h:
        return None
    op = h[0] & 0x0F
    ln = h[1] & 0x7F
    if ln == 126:
        ln = int.from_bytes(rd(2), "big")
    elif ln == 127:
        ln = int.from_bytes(rd(8), "big")
    payload = rd(ln) if ln else b""
    return op, payload


def _ws_send_text(sock, obj):
    p = json.dumps(obj, ensure_ascii=False).encode()
    n = len(p)
    head = b"\x81"
    if n < 126:
        head += bytes([n])
    elif n < 65536:
        head += bytes([126]) + n.to_bytes(2, "big")
    else:
        head += bytes([127]) + n.to_bytes(8, "big")
    mask = os.urandom(4)
    sock.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(p)))


def ws_probe(device_id, client_id, token, frames=6):
    """返回 (http_status_line, [事件列表])"""
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        f"GET {WS_PATH} HTTP/1.1\r\nHost: {HOST}\r\n"
        f"Upgrade: websocket\r\nConnection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
        f"Authorization: Bearer {token}\r\n"
        f"Protocol-Version: 1\r\n"
        f"Device-Id: {device_id}\r\nClient-Id: {client_id}\r\n\r\n"
    )

    ctx = ssl.create_default_context()
    sock = ctx.wrap_socket(
        socket.create_connection((HOST, 443), timeout=25), server_hostname=HOST
    )
    sock.settimeout(20)
    sock.sendall(req.encode())

    buf = b""
    while b"\r\n\r\n" not in buf:
        d = sock.recv(4096)
        if not d:
            break
        buf += d
    status = buf.split(b"\r\n")[0].decode("latin1")

    events = []
    if "101" not in status:
        sock.close()
        return status, events

    _ws_send_text(sock, {
        "type": "hello", "version": 1,
        "features": {"mcp": True}, "transport": "websocket",
        "audio_params": {"format": "opus", "sample_rate": 16000,
                         "channels": 1, "frame_duration": 60},
    })
    events.append(">>> 已发送 hello")

    for _ in range(frames):
        f = _ws_recv(sock)
        if f is None:
            events.append("<<< 连接被关闭（无帧）")
            break
        op, pl = f
        if op == 0x1:
            events.append("<<< 文本帧: " + pl.decode("utf-8", "ignore")[:500])
            if '"hello"' in pl.decode("utf-8", "ignore"):
                break
        elif op == 0x2:
            events.append(f"<<< 二进制帧 {len(pl)} 字节")
        elif op == 0x8:
            code = int.from_bytes(pl[:2], "big") if len(pl) >= 2 else 0
            reason = pl[2:].decode("utf-8", "ignore")
            events.append(f"<<< 服务端 Close, code={code}, reason={reason!r}")
            break
        elif op == 0x9:
            events.append("<<< Ping")
        elif op == 0xA:
            events.append("<<< Pong")
    sock.close()
    return status, events


# ---------------------------------------------------------------- MQTT
def _mqtt_str(s):
    b = s.encode()
    return struct.pack(">H", len(b)) + b


def mqtt_probe(cfg, port=8883, tls=True):
    client_id = cfg.get("client_id", "")
    username = cfg.get("username", "")
    password = cfg.get("password", "")

    payload = _mqtt_str(client_id) + _mqtt_str(username) + _mqtt_str(password)
    var = struct.pack(">H", 4) + b"MQTT" + bytes([0x04, 0xC2]) + struct.pack(">H", 60)
    remaining = var + payload
    # 剩余长度编码
    rem = b""
    x = len(remaining)
    while True:
        b = x % 128
        x //= 128
        rem += bytes([b | (0x80 if x > 0 else 0)])
        if x == 0:
            break
    pkt = b"\x10" + rem + remaining

    try:
        if tls:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            sock = ctx.wrap_socket(
                socket.create_connection((HOST, port), timeout=25), server_hostname=HOST
            )
        else:
            sock = socket.create_connection((HOST, port), timeout=25)
        sock.settimeout(20)
        sock.sendall(pkt)
        head = sock.recv(4)
        sock.close()
        if len(head) < 4:
            return f"仅收到 {len(head)} 字节: {head.hex()}"
        rc = head[3]
        names = {0: "0 接受连接", 1: "1 协议版本不支持", 2: "2 Client-Id 被拒绝",
                 3: "3 服务不可用", 4: "4 用户名或密码错误", 5: "5 未授权"}
        return f"CONNACK 返回码 = {names.get(rc, rc)}   (原始 {head.hex()})"
    except Exception as e:
        return f"连接失败: {type(e).__name__}: {e}"


# ---------------------------------------------------------------- main
def main():
    hr("探测 1 · 新设备（无 eFuse 序列号，Activation-Version=1）")
    dev1 = "AA:BB:CC:DD:EE:%02X" % (os.urandom(1)[0] or 1)
    cid1 = str(uuid.uuid4())
    print(f"Device-Id = {dev1}\nClient-Id = {cid1}")
    st, resp = ota_check(dev1, cid1, 1)
    print(f"\nOTA HTTP {st}")
    print(json.dumps(resp, ensure_ascii=False, indent=2)[:2000])
    ws = resp.get("websocket") or {}
    mq = resp.get("mqtt") or {}
    print(f"\n关键字段：activation 段 = {'有' if 'activation' in resp else '无（未下发激活挑战）'}"
          f" | websocket.token = {ws.get('token')!r}")
    if mq:
        print(f"MQTT client_id = {mq.get('client_id')}")
        print(f"MQTT subscribe_topic = {mq.get('subscribe_topic')!r}")
        try:
            print("MQTT username 解码 =",
                  base64.b64decode(mq.get("username", "") + "==").decode("utf-8", "ignore"))
        except Exception:
            pass

    hr("探测 2 · 同一设备重复调用 OTA（观察是否进入激活流程）")
    st2, resp2 = ota_check(dev1, cid1, 1)
    print(f"OTA HTTP {st2}")
    print(json.dumps(resp2, ensure_ascii=False, indent=2)[:1200])
    print(f"\n关键字段：activation 段 = {'有' if 'activation' in resp2 else '无'}"
          f" | websocket.token = {(resp2.get('websocket') or {}).get('token')!r}")

    hr("探测 3 · 用官方下发的 token 做 WebSocket 握手")
    token = ws.get("token")
    if token:
        status, events = ws_probe(dev1, cid1, token)
        print("HTTP 状态行:", status)
        for e in events:
            print(" ", e)
    else:
        print("OTA 未返回 token，跳过")

    hr("探测 4 · MQTT 链路（若 OTA 返回了 mqtt 配置）")
    if mq:
        for port, use_tls in ((8883, True), (1883, False)):
            print(f"  {HOST}:{port} TLS={use_tls} -> {mqtt_probe(mq, port, use_tls)}")
    else:
        print("OTA 未返回 mqtt 配置，跳过")

    hr("探测 5 · 带假序列号（Activation-Version=2）")
    dev2 = "AA:BB:CC:DD:EE:%02X" % (os.urandom(1)[0] or 2)
    cid2 = str(uuid.uuid4())
    sn = "FAKESERIALNUMBER0000000000000000"[:32]
    st5, resp5 = ota_check(dev2, cid2, 2, sn)
    print(f"Device-Id = {dev2}  Serial-Number = {sn}")
    print(f"OTA HTTP {st5}")
    print(json.dumps(resp5, ensure_ascii=False, indent=2)[:1500])

    hr("探测 6 · 激活端点 /activate（空载荷模拟无序列号设备）")
    for label, dv, cv in (("新设备", dev1, cid1), ("固定 Device-Id AA:BB:CC:DD:EE:01",
                                                 "AA:BB:CC:DD:EE:01",
                                                 "fe9b2555-3544-4e3f-8ef7-78c5927e2c55")):
        st6, txt6 = activate(dv, cv)
        print(f"  [{label}] HTTP {st6} -> {txt6[:200]}")
        print(f"      202=等待用户在 xiaozhi.me 输入激活码   200=已激活")

    hr("结论判据")
    print("1) mqtt.client_id 前缀为 'GID_test'    -> 设备处于测试组，未绑定账号")
    print("2) mqtt.subscribe_topic == 'null'      -> 未分配订阅主题，收不到任何下行")
    print("3) websocket.token == 'test-token'     -> 占位凭据，WS 发完 hello 即被 Close")
    print("以上三条同时成立 => 必须先完成 xiaozhi.me 账号绑定，才能拿到真实凭据。")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
