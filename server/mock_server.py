#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小智服务端模拟器（mock server）

目的：在本地完整复刻官方 api.tenclass.net 的 OTA / 激活 / 绑定 / WebSocket 协议，
用于验证客户端（xiaozhi-android / py-xiaozhi）全链路逻辑：
  拿激活码 -> 网页输码绑定 -> /activate 200 -> OTA 下发真实凭据 -> WS 会话（hello/listen/stt/llm/tts/opus）

协议依据（均与官方线上实测比对过）：
  - OTA  POST /xiaozhi/ota/       未绑定 -> activation.code(6位)+challenge；已绑定 -> 真实凭据
  - ACT  POST /xiaozhi/ota/activate  行存在->200 / 行不存在->202（官方不校验 HMAC，本模拟器校验并仅记日志）
  - WS   GET  /xiaozhi/v1/           Bearer 真实 token，hello -> hello ack(session_id + audio_params)
  - 绑定控制台  GET /               模拟 xiaozhi.me 的输码页面（手机/PC 浏览器打开即可绑定）

用法：
  C:/Users/T/.workbuddy/binaries/python/envs/default/Scripts/python.exe server/mock_server.py [--port 8000]

设备状态持久化在 server/mock_devices.json（重启不丢绑定）。
"""

import argparse
import asyncio
import hashlib
import hmac as hmac_mod
import json
import logging
import secrets
import time
import uuid
from pathlib import Path

from aiohttp import web, WSMsgType

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("mock")

STATE_FILE = Path(__file__).with_name("mock_devices.json")
TEST_TOKEN = "test-token"
NEXT_DEVICE_ID = 2632000  # 仿官方数字 device_id

# ------------------------------------------------------------------ 设备状态


def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            log.warning("状态文件损坏，重新开始")
    return {"devices": {}, "next_device_id": NEXT_DEVICE_ID}


STATE = load_state()


def save_state():
    STATE_FILE.write_text(json.dumps(STATE, ensure_ascii=False, indent=2), encoding="utf-8")


def norm_mac(mac: str) -> str:
    return mac.strip().upper()


def get_device(mac_raw: str) -> dict | None:
    return STATE["devices"].get(norm_mac(mac_raw))


def register_device(mac_raw: str, board: dict, ua: str) -> dict:
    """首次 OTA 登记设备：发恒定激活码（对齐官方：同一身份激活码不变）"""
    mac = norm_mac(mac_raw)
    dev = STATE["devices"].get(mac)
    if dev is None:
        STATE["next_device_id"] += 1
        dev = {
            "mac": mac,
            "device_id": STATE["next_device_id"],
            "code": f"{secrets.randbelow(1000000):06d}",
            "challenge": str(uuid.uuid4()),
            "bound": False,
            "real_token": f"mock-token-{secrets.token_hex(16)}",
            "created_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "last_seen": 0,
            "board": board,
            "ua": ua,
            "hmac_key": None,
            "bind_time": None,
        }
        STATE["devices"][mac] = dev
        save_state()
        log.info("新设备登记 mac=%s code=%s", mac, dev["code"])
    dev["last_seen"] = time.time()
    dev["board"] = board or dev.get("board", {})
    dev["ua"] = ua or dev.get("ua", "")
    return dev


def bind_by_code(code: str) -> dict | None:
    """网页输码绑定（模拟 xiaozhi.me）"""
    code = code.strip()
    for dev in STATE["devices"].values():
        if dev["code"] == code and not dev["bound"]:
            dev["bound"] = True
            dev["bind_time"] = time.strftime("%Y-%m-%d %H:%M:%S")
            save_state()
            log.info("设备绑定成功 mac=%s device_id=%s", dev["mac"], dev["device_id"])
            return dev
    return None


def unbind(mac: str) -> bool:
    dev = STATE["devices"].get(norm_mac(mac))
    if dev is None:
        return False
    # 仿官方"回滚"：删行 = 设备回到未绑定（激活码会重新可见）
    STATE["devices"].pop(norm_mac(mac))
    save_state()
    log.info("设备已解绑（删除设备行）mac=%s", mac)
    return True


# ------------------------------------------------------------------ OTA 响应构造


def ws_base(request: web.Request) -> str:
    """用请求方看到的主机名拼 WS 地址，手机访问 192.168.x.x 时自动得到正确地址"""
    host = request.headers.get("Host") or request.host
    return f"ws://{host}/xiaozhi/v1/"


def ota_response_bound(request: web.Request, dev: dict) -> dict:
    """已绑定：下发真实凭据（判据与客户端 isTestGroup 对着干：token 非测试值、
    mqtt.client_id 非 GID_test 前缀、subscribe_topic 非 "null"）"""
    mac = dev["mac"]
    client_gid = f"GID_mock@@@{mac.replace(':', '_').lower()}@@@{uuid.uuid4()}"
    return {
        "websocket": {"url": ws_base(request), "token": dev["real_token"]},
        "mqtt": {
            "endpoint": "mqtts://mock.local:8883",
            "client_id": client_gid,
            "username": secrets.token_hex(8),
            "password": secrets.token_hex(8),
            "publish_topic": f"device/{mac.lower()}/up",
            "subscribe_topic": f"device/{mac.lower()}/down",
        },
        "server_time": int(time.time()),
    }


def ota_response_unbound(request: web.Request, dev: dict) -> dict:
    """未绑定：下发激活码 + challenge + 测试组凭据（官方同款形态）"""
    return {
        "websocket": {"url": ws_base(request), "token": TEST_TOKEN},
        "mqtt": {
            "endpoint": "mqtts://mock.local:8883",
            "client_id": f"GID_test@@@{dev['mac'].replace(':', '_').lower()}@@@test",
            "username": "",
            "password": "",
            "publish_topic": "null",
            "subscribe_topic": "null",
        },
        "activation": {"code": dev["code"], "challenge": dev["challenge"]},
        "server_time": int(time.time()),
    }


# ------------------------------------------------------------------ HTTP handlers


async def handle_ota(request: web.Request) -> web.Response:
    body = {}
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"message": "Invalid JSON body"}, status=400)

    mac = request.headers.get("Device-Id") or (body.get("board") or {}).get("mac", "")
    if not mac:
        return web.json_response({"message": "Missing Device-Id"}, status=400)

    app = body.get("application") or {}
    board = body.get("board") or {}
    ua = request.headers.get("User-Agent", "")
    dev = register_device(mac, board, ua)

    # 官方语义：OTA body 的 application.elf_sha256 即设备 hmacKey，登记用于 /activate 验签
    elf = app.get("elf_sha256")
    if elf:
        dev["hmac_key"] = elf

    log.info(
        "OTA mac=%s UA=%s board=%s ver=%s -> %s",
        mac, ua, board.get("type"), app.get("version"),
        "已绑定(真实凭据)" if dev["bound"] else f"未绑定(code={dev['code']})",
    )

    resp = ota_response_bound(request, dev) if dev["bound"] else ota_response_unbound(request, dev)
    return web.json_response(resp)


async def handle_activate(request: web.Request) -> web.Response:
    mac = request.headers.get("Device-Id", "")
    dev = get_device(mac)
    payload = {}
    try:
        payload = (await request.json()).get("Payload", {})
    except Exception:
        pass

    # 与官方一致的语义：只看设备行存在与否（行存在=已绑定 -> 200）
    if dev is not None and dev["bound"]:
        # 额外验签（官方不验，这里只记日志帮客户端自查签名实现）
        ok = False
        key = dev.get("hmac_key")
        if key and payload.get("challenge") and payload.get("hmac"):
            expect = hmac_mod.new(
                key.encode(), payload["challenge"].encode(), hashlib.sha256
            ).hexdigest()
            ok = hmac_mod.compare_digest(expect, payload.get("hmac", ""))
        log.info(
            "activate mac=%s -> 200 device_id=%s 验签=%s challenge匹配=%s",
            mac, dev["device_id"], "PASS" if ok else "SKIP/FAIL",
            payload.get("challenge") == dev.get("challenge"),
        )
        return web.json_response(
            {"message": "Device activated", "device_id": dev["device_id"]}, status=200
        )
    log.info("activate mac=%s -> 202 (未绑定)", mac)
    return web.json_response({"message": "Device activation timeout"}, status=202)


# ------------------------------------------------------------------ 绑定控制台（模拟 xiaozhi.me）


def render_console(request: web.Request, msg: str = "") -> web.Response:
    rows = []
    for dev in sorted(STATE["devices"].values(), key=lambda d: -d.get("last_seen", 0)):
        state = "✅ 已绑定" if dev["bound"] else "⏳ 等待输码"
        seen = time.strftime("%H:%M:%S", time.localtime(dev.get("last_seen", 0)))
        rows.append(f"""
        <tr>
          <td>{dev['mac']}</td>
          <td>{dev['device_id']}</td>
          <td class="code">{dev['code']}</td>
          <td>{state}</td>
          <td>{seen}</td>
          <td>{(dev.get('board') or {}).get('type', '')}</td>
          <td>{dev.get('ua', '')[:48]}</td>
          <td>
            <form method="post" action="/unbind" style="display:inline">
              <input type="hidden" name="mac" value="{dev['mac']}">
              <button class="danger">解绑(仿服务端回滚)</button>
            </form>
          </td>
        </tr>""")
    html = f"""<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<title>小智模拟服务端 - 绑定控制台</title>
<style>
  body {{ font-family: system-ui, sans-serif; margin: 32px auto; max-width: 980px;
         background:#f6f7f9; color:#222; }}
  h1 {{ font-size: 20px; }}
  .card {{ background:#fff; border:1px solid #e3e5e8; border-radius:12px;
           padding:20px 24px; margin-bottom:20px; }}
  .code {{ font-family:monospace; font-size:18px; font-weight:700; color:#c0392b; }}
  input[type=text] {{ font-size:20px; letter-spacing:6px; width:180px; padding:8px;
                      border:1px solid #ccc; border-radius:8px; text-align:center; }}
  button {{ padding:8px 16px; border:none; border-radius:8px; background:#2d6cdf;
            color:#fff; cursor:pointer; font-size:15px; }}
  button.danger {{ background:#c0392b; font-size:12px; padding:4px 10px; }}
  table {{ border-collapse:collapse; width:100%; font-size:13px; }}
  th,td {{ border-bottom:1px solid #eee; padding:8px 10px; text-align:left; }}
  th {{ background:#fafafa; }}
  .msg {{ padding:10px 14px; border-radius:8px; margin-bottom:14px; }}
  .ok {{ background:#e8f7ee; color:#197a3e; }}
  .err {{ background:#fdeceb; color:#a4271b; }}
  .hint {{ color:#777; font-size:13px; }}
</style></head><body>
<h1>小智模拟服务端 · 绑定控制台 <span class="hint">(模拟 xiaozhi.me 输码)</span></h1>
{f'<div class="msg ok">{msg}</div>' if msg and not msg.startswith('!!') else ''}
{f'<div class="msg err">{msg[2:]}</div>' if msg and msg.startswith('!!') else ''}
<div class="card">
  <form method="post" action="/bind">
    <b>输入 6 位激活码：</b>
    <input type="text" name="code" maxlength="6" pattern="[0-9]*" placeholder="______" autofocus>
    <button>绑定设备</button>
  </form>
  <p class="hint">App 点「连接」后把显示的激活码输到这里，即可完成绑定（等价于 xiaozhi.me 输码）。</p>
</div>
<div class="card">
  <table>
    <tr><th>MAC</th><th>device_id</th><th>激活码</th><th>状态</th><th>最近活动</th><th>板型</th><th>UA</th><th>操作</th></tr>
    {''.join(rows) or '<tr><td colspan="8" class="hint">暂无设备，等 App/探测脚本先做一次 OTA</td></tr>'}
  </table>
</div>
<p class="hint">OTA: POST /xiaozhi/ota/ · activate: POST /xiaozhi/ota/activate · WS: GET /xiaozhi/v1/</p>
</body></html>"""
    return web.Response(text=html, content_type="text/html")


async def handle_index(request: web.Request):
    return render_console(request)


async def handle_bind(request: web.Request):
    form = await request.post()
    code = str(form.get("code", ""))
    dev = bind_by_code(code)
    if dev:
        return render_console(
            request, f"绑定成功：{dev['mac']} (device_id={dev['device_id']})，回 App 点「连接」即可拿到真实凭据"
        )
    return render_console(request, f"!!激活码 {code} 无效（不存在或已绑定过）")


async def handle_unbind(request: web.Request):
    form = await request.post()
    mac = str(form.get("mac", ""))
    if unbind(mac):
        return render_console(request, f"已解绑 {mac}（设备行已删除，回到未绑定态）")
    return render_console(request, f"!!解绑失败：{mac} 不存在")


# ------------------------------------------------------------------ WebSocket 会话端点


async def ws_session(request: web.Request) -> web.WebSocketResponse | web.Response:
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)

    auth = request.headers.get("Authorization", "")
    token = auth[7:] if auth.startswith("Bearer ") else ""
    device_id = request.headers.get("Device-Id", "")
    client_id = request.headers.get("Client-Id", "")

    dev = get_device(device_id)
    is_real = dev is not None and dev["bound"] and token == dev["real_token"]
    log.info(
        "WS 连接 device=%s client=%s token=%s -> %s",
        device_id, client_id[:8] + "…", "真实" if is_real else "测试/无效",
        "接受" if is_real else "拒绝",
    )
    if not is_real:
        await ws.close(code=4001, message=b"invalid token (test-token not allowed on mock)")
        return ws

    session_id = str(uuid.uuid4())
    opus_count = 0

    async for msg in ws:
        if msg.type == WSMsgType.TEXT:
            try:
                obj = json.loads(msg.data)
            except Exception:
                continue
            mtype = obj.get("type")

            if mtype == "hello":
                await ws.send_json({
                    "type": "hello",
                    "session_id": session_id,
                    "transport": "websocket",
                    "audio_params": {
                        "format": "opus",
                        "sample_rate": 24000,
                        "channels": 1,
                        "frame_duration": 60,
                    },
                })
                log.info("WS hello -> hello ack (session=%s)", session_id[:8])

            elif mtype == "listen":
                state = obj.get("state")
                if state == "start":
                    opus_count = 0
                elif state == "stop":
                    # 模拟一轮完整对话：stt -> llm -> tts(start/sentence_start/stop)
                    await ws.send_json({
                        "type": "stt", "session_id": session_id,
                        "text": f"(模拟识别) 收到 {opus_count} 帧语音",
                    })
                    await ws.send_json({
                        "type": "llm", "session_id": session_id,
                        "emotion": "happy", "text": "模拟服务端已收到你的语音！客户端全链路正常。",
                    })
                    await ws.send_json({
                        "type": "tts", "session_id": session_id,
                        "state": "start", "text": "模拟服务端已收到你的语音！客户端全链路正常。",
                    })
                    await ws.send_json({
                        "type": "tts", "session_id": session_id,
                        "state": "sentence_start",
                        "text": "模拟服务端已收到你的语音！客户端全链路正常。",
                    })
                    await ws.send_json({
                        "type": "tts", "session_id": session_id,
                        "state": "stop",
                    })
                    log.info("WS 一轮对话完成 (%d 帧回声已发)", opus_count)

            elif mtype == "abort":
                log.info("WS abort: %s", obj.get("reason"))

        elif msg.type == WSMsgType.BINARY:
            # v1 裸 Opus：原样回声，验证客户端音频收发链路（手机上会听到自己的声音）
            opus_count += 1
            await ws.send_bytes(msg.data)

        elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
            break

    log.info("WS 关闭 device=%s session=%s", device_id, session_id[:8])
    return ws


# ------------------------------------------------------------------ main


def build_app() -> web.Application:
    app = web.Application()
    app.router.add_post("/xiaozhi/ota/", handle_ota)
    app.router.add_post("/xiaozhi/ota/activate", handle_activate)
    # 兼容无尾斜杠写法
    app.router.add_post("/xiaozhi/ota", handle_ota)
    app.router.add_get("/xiaozhi/v1/", ws_session)
    app.router.add_get("/", handle_index)
    app.router.add_post("/bind", handle_bind)
    app.router.add_post("/unbind", handle_unbind)
    return app


def main():
    ap = argparse.ArgumentParser(description="小智服务端模拟器")
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()

    log.info("=" * 70)
    log.info("小智模拟服务端启动 http://0.0.0.0:%d  (绑定控制台: http://<本机IP>:%d/)", args.port, args.port)
    log.info("状态文件: %s", STATE_FILE)
    log.info("=" * 70)
    web.run_app(build_app(), host=args.host, port=args.port, print=None)


if __name__ == "__main__":
    main()
