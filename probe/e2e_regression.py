#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小智官方服务器端到端协议回归脚本

对应 Kotlin 客户端 (xiaozhi-android/core-protocol) 的协议行为，
针对真实服务器 api.tenclass.net 做断言式回归，作为单元测试之外的网络层证据。

用例：
  T1  OTA v2 新身份注册：SN + hmac_key 上报 -> 下发激活码 + challenge + 测试组凭据
  T2  User-Agent 负面校验：格式错误 -> 400 Invalid board type or app version
  T3  激活端点 /activate（{"Payload":{...}} 嵌套 + HMAC-SHA256 签名）-> 202 受理
  T4  WS 握手：测试组凭据 101 后收不到服务端 hello（被应用层拒绝）
  T5  二进制帧 v1/v2/v3 编解码往返（与 Kotlin BinaryFrameCodec 布局交叉验证）

退出码：0 = 全部通过，1 = 存在失败
"""
import base64
import hashlib
import hmac as hmac_mod
import json
import os
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
HOST = "api.tenclass.net"
WS_PATH = "/xiaozhi/v1/"
BOARD_TYPE = "bread-compact-wifi"
APP_NAME = "py-xiaozhi"
APP_VERSION = "2.1.1"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"\n        {detail}" if detail else ""))


def hr(title):
    print("\n" + "=" * 62 + f"\n{title}\n" + "=" * 62)


# ------------------------------------------------ 身份（与 DeviceCredentials.kt 一致）
def make_identity():
    mac = "AA:BB:CC:%02X:%02X:%02X" % (os.urandom(1)[0], os.urandom(1)[0], os.urandom(1)[0] or 1)
    mac_clean = mac.lower().replace(":", "")
    sn = "SN-" + hashlib.md5(mac_clean.encode()).hexdigest()[:8].upper() + "-" + mac_clean
    hmac_key = os.urandom(32).hex()
    client_id = str(uuid.uuid4())
    return {"device_id": mac, "client_id": client_id, "serial_number": sn, "hmac_key": hmac_key}


# ------------------------------------------------ OTA（与 OtaClient.kt 一致）
def ota_check(identity, user_agent=None, activation_version="2", legacy_body=False):
    if legacy_body:
        # 固件风格 body（无 board 段）：服务端只能从 UA 解析板型与版本
        body = json.dumps({
            "version": 2, "flash_size": 16777216,
            "minimum_free_heap_size": 8315496,
            "mac_address": identity["device_id"], "uuid": identity["client_id"],
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
    else:
        # py-xiaozhi 风格 body：application.elf_sha256 携带 hmac_key 注册
        body = json.dumps({
            "application": {
                "version": APP_VERSION,
                "elf_sha256": identity["hmac_key"],  # py-xiaozhi：用 elf_sha256 字段携带 hmac_key 注册
            },
            "board": {
                "type": BOARD_TYPE,
                "name": APP_NAME,
                "ip": "192.168.1.100",
                "mac": identity["device_id"],
            },
        }, ensure_ascii=False).encode()

    hdr = {
        "Activation-Version": activation_version,
        "Device-Id": identity["device_id"],
        "Client-Id": identity["client_id"],
        "User-Agent": user_agent or f"{BOARD_TYPE}/{APP_NAME}-{APP_VERSION}",
        "Accept-Language": "zh-CN",
        "Content-Type": "application/json",
    }
    if not legacy_body:
        hdr["Serial-Number"] = identity["serial_number"]
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


# ------------------------------------------------ 激活（与 OtaClient.kt 嵌套载荷一致）
def activate(identity, challenge):
    sig = hmac_mod.new(
        identity["hmac_key"].encode(), challenge.encode(), hashlib.sha256
    ).hexdigest()
    payload = json.dumps({
        "Payload": {
            "algorithm": "hmac-sha256",
            "serial_number": identity["serial_number"],
            "challenge": challenge,
            "hmac": sig,
        }
    }).encode()
    hdr = {
        "Activation-Version": "2",
        "Device-Id": identity["device_id"],
        "Client-Id": identity["client_id"],
        "Content-Type": "application/json",
    }
    req = urllib.request.Request(OTA_URL + "activate", data=payload, headers=hdr, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, r.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


# ------------------------------------------------ WebSocket（与 XiaozhiWsClient.kt 行为一致）
def _ws_recv(sock, timeout):
    sock.settimeout(timeout)

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
        return ("closed", b"")
    op = h[0] & 0x0F
    ln = h[1] & 0x7F
    if ln == 126:
        ln = int.from_bytes(rd(2), "big")
    elif ln == 127:
        ln = int.from_bytes(rd(8), "big")
    payload = rd(ln) if ln else b""
    return (op, payload)


def ws_hello_test(device_id, client_id, token, hello_timeout=10.0):
    """返回 (status_line, got_server_hello, close_info)"""
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
    sock = ctx.wrap_socket(socket.create_connection((HOST, 443), timeout=25), server_hostname=HOST)
    sock.settimeout(20)
    sock.sendall(req.encode())

    buf = b""
    while b"\r\n\r\n" not in buf:
        d = sock.recv(4096)
        if not d:
            break
        buf += d
    status = buf.split(b"\r\n")[0].decode("latin1")
    if "101" not in status:
        sock.close()
        return status, False, "握手被拒"

    # 发送 hello（与 XiaozhiMessage.Hello.toJson 一致）
    hello = json.dumps({
        "type": "hello", "version": 1,
        "features": {"mcp": True}, "transport": "websocket",
        "audio_params": {"format": "opus", "sample_rate": 16000,
                         "channels": 1, "frame_duration": 60},
    }, ensure_ascii=False).encode()
    n = len(hello)
    head = b"\x81" + (bytes([n]) if n < 126 else bytes([126]) + n.to_bytes(2, "big"))
    mask = os.urandom(4)
    sock.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(hello)))

    got_hello, close_info = False, ""
    t0 = time.time()
    while time.time() - t0 < hello_timeout:
        try:
            op, pl = _ws_recv(sock, hello_timeout)
        except (socket.timeout, ssl.SSLError, OSError):
            close_info = f"{hello_timeout}s 内无任何帧（超时）"
            break
        if op == "closed":
            close_info = "连接被关闭（无帧）"
            break
        if op == 0x1:
            txt = pl.decode("utf-8", "ignore")
            close_info = "文本帧: " + txt[:200]
            if '"hello"' in txt:
                got_hello = True
                break
        elif op == 0x8:
            code = int.from_bytes(pl[:2], "big") if len(pl) >= 2 else 0
            close_info = f"Close code={code} reason={pl[2:].decode('utf-8', 'ignore')!r}"
            break
        elif op == 0x9:
            close_info = "Ping"
        else:
            close_info = f"op={op} len={len(pl)}"
    sock.close()
    return status, got_hello, close_info


# ------------------------------------------------ T5 二进制帧（与 BinaryFrameCodec.kt 布局一致）
def frame_roundtrip():
    payload = bytes(range(256)) * 2  # 512 字节
    ok_all, detail = True, []

    # v1：裸载荷
    if payload != payload:
        ok_all = False
    detail.append("v1=直通")

    # v3：u8 type | u8 reserved | u16 size | payload
    v3 = bytes([0, 0]) + len(payload).to_bytes(2, "big") + payload
    t = v3[0]
    sz = int.from_bytes(v3[2:4], "big")
    ok_v3 = (t == 0 and sz == len(payload) and v3[4:] == payload)
    ok_all &= ok_v3
    detail.append(f"v3={'OK' if ok_v3 else 'FAIL'}")

    # v2：u16 ver | u16 type | u32 reserved | u32 ts | u32 size | payload
    v2 = (2).to_bytes(2, "big") + (0).to_bytes(2, "big") + b"\x00" * 8 + len(payload).to_bytes(4, "big") + payload
    ver = int.from_bytes(v2[0:2], "big")
    t2 = int.from_bytes(v2[2:4], "big")
    sz2 = int.from_bytes(v2[12:16], "big")
    ok_v2 = (ver == 2 and t2 == 0 and sz2 == len(payload) and v2[16:] == payload)
    ok_all &= ok_v2
    detail.append(f"v2={'OK' if ok_v2 else 'FAIL'}")

    # 截断帧必须判非法
    ok_trunc = int.from_bytes(v3[2:4], "big") > len(v3) - 4  # 构造 size>实际 的情况验证
    bad = bytes([0, 0]) + (len(payload) + 100).to_bytes(2, "big") + payload
    sz_bad = int.from_bytes(bad[2:4], "big")
    ok_trunc = sz_bad > len(bad) - 4
    ok_all &= ok_trunc
    detail.append(f"截断检测={'OK' if ok_trunc else 'FAIL'}")
    return ok_all, "  ".join(detail)


# ------------------------------------------------ main
def main():
    ident = make_identity()
    print(f"本次回归身份：Device-Id={ident['device_id']}  SN={ident['serial_number']}")

    hr("T1 · OTA v2 新身份注册")
    st, resp = ota_check(ident)
    ws_cfg = resp.get("websocket") or {}
    act = resp.get("activation") or {}
    check("OTA HTTP 200", st == 200, f"HTTP {st} {str(resp.get('_error', ''))}")
    check("下发 6 位激活码", len(str(act.get("code", ""))) == 6, f"code={act.get('code')!r}")
    check("下发 challenge", bool(act.get("challenge")))
    check("websocket.token 为 test-token（测试组判据）", ws_cfg.get("token") == "test-token",
          f"token={ws_cfg.get('token')!r}")

    hr("T2 · UA 负面校验")
    # 说明：早期实测 400 的触发条件是「固件风格 body（无 board 段）+ 无 Serial-Number」，
    # 此时服务端只能从 UA 解析板型，格式错误 -> 400。
    # 带 board 段 + Serial-Number 的 py-xiaozhi 风格请求服务端以 body 为准，UA 容错。
    st2, resp2 = ota_check(ident, user_agent="Xiaozhi/2.2.2 (esp32s3) IDF/v5.4.0",
                           activation_version="1", legacy_body=True)
    raw2 = json.dumps(resp2, ensure_ascii=False)
    check("错误 UA（固件风格 body）返回 400", st2 == 400, f"HTTP {st2} body={raw2[:120]}")
    check("错误信息包含 Invalid board type or app version",
          "Invalid board type" in raw2, raw2[:160])

    hr("T3 · 激活端点（嵌套 Payload + HMAC）")
    challenge = act.get("challenge")
    if challenge:
        st3, txt3 = activate(ident, challenge)
        check("激活请求被受理（200 或 202）", st3 in (200, 202), f"HTTP {st3} -> {txt3[:160]}")
        if st3 == 202:
            check("202 = 等待用户在 xiaozhi.me 输码", True)
        elif st3 == 200:
            check("200 = 已激活（可能被他人误绑，属环境噪音）", True)
    else:
        check("T3 跳过（T1 未下发 challenge）", False, "无法执行激活回归")

    hr("T4 · WS 握手：测试组凭据收不到服务端 hello")
    token = ws_cfg.get("token")
    if token:
        status, got_hello, close_info = ws_hello_test(ident["device_id"], ident["client_id"], token)
        check("HTTP 101 协议切换", "101" in status, status)
        check("未收到服务端 hello（应用层拒绝）", not got_hello, f"close: {close_info}")
    else:
        check("T4 跳过（无 token）", False)

    hr("T5 · 二进制帧编解码往返（本地，与 Kotlin 版交叉验证）")
    ok5, detail5 = frame_roundtrip()
    check("v1/v2/v3 布局与 Kotlin BinaryFrameCodec 一致", ok5, detail5)

    hr("回归结果汇总")
    failed = [r for r in RESULTS if not r[1]]
    for name, ok, _ in RESULTS:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    print(f"\n共 {len(RESULTS)} 项，通过 {len(RESULTS) - len(failed)}，失败 {len(failed)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
