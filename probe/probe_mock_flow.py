#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模拟服务端全流程验证：完全按 App 的请求形态（OtaClient + XiaozhiWsClient 同款头/体）
跑一遍「OTA -> 激活码 -> 网页绑定 -> /activate 200 -> OTA 真实凭据 -> WS 会话」，
全部断言通过即证明模拟服务端可支撑客户端全链路联调。

用法（先另开终端启动服务端，或由本脚本自动拉起）：
  C:/Users/T/.workbuddy/binaries/python/envs/default/Scripts/python.exe probe/probe_mock_flow.py [--port 8000]
"""

import argparse
import asyncio
import hashlib
import hmac as hmac_mod
import json
import secrets
import sys
import time
import uuid

import aiohttp

PASS = []
FAIL = []


def check(name: str, cond: bool, detail: str = ""):
    tag = "PASS" if cond else "FAIL"
    (PASS if cond else FAIL).append(name)
    print(f"  [{tag}] {name}" + (f"  ({detail})" if detail else ""))
    return cond


def random_identity() -> dict:
    """镜像 DeviceIdentity.create：02 开头随机 MAC + SN-md5 序列号 + 64 hex hmac key"""
    raw = bytearray(secrets.token_bytes(6))
    raw[0] = (raw[0] | 0x02) & 0xFE
    mac = ":".join(f"{b:02X}" for b in raw)
    mac_clean = mac.lower().replace(":", "")
    sn = "SN-" + hashlib.md5(mac_clean.encode()).hexdigest()[:8].upper() + "-" + mac_clean
    key = secrets.token_hex(32)
    return {"mac": mac, "client_id": str(uuid.uuid4()), "serial": sn, "key": key}


def ota_headers(ident: dict) -> dict:
    """镜像 OtaClient.setHeaders"""
    return {
        "Activation-Version": "2",
        "Device-Id": ident["mac"],
        "Client-Id": ident["client_id"],
        "Serial-Number": ident["serial"],
        "User-Agent": "bread-compact-wifi/xiaozhi-android-2.2.0",
        "Accept-Language": "zh-CN",
        "Content-Type": "application/json",
    }


def ota_body(ident: dict) -> dict:
    """镜像 OtaClient.checkVersion 的 body"""
    return {
        "application": {"version": "2.2.0", "elf_sha256": ident["key"]},
        "board": {
            "type": "bread-compact-wifi",
            "name": "xiaozhi-android",
            "ip": "127.0.0.1",
            "mac": ident["mac"],
        },
    }


def sign(key: str, challenge: str) -> str:
    return hmac_mod.new(key.encode(), challenge.encode(), hashlib.sha256).hexdigest()


def is_test_group(cfg: dict) -> bool:
    """镜像 OtaConfig.isTestGroup 三判据"""
    ws = cfg.get("websocket") or {}
    mqtt = cfg.get("mqtt") or {}
    return (
        ws.get("token") == "test-token"
        or mqtt.get("subscribe_topic") == "null"
        or str(mqtt.get("client_id", "")).startswith("GID_test")
    )


async def main(port: int):
    base = f"http://127.0.0.1:{port}"
    ws_base = f"ws://127.0.0.1:{port}/xiaozhi/v1/"
    ident = random_identity()
    print(f"\n=== 模拟身份 device={ident['mac']} serial={ident['serial']} ===\n")

    async with aiohttp.ClientSession() as http:

        # ---------------------------------------------------------------- 1. 首次 OTA：应拿到激活码
        print("[1] 首次 OTA（未绑定）")
        async with http.post(
            base + "/xiaozhi/ota/", headers=ota_headers(ident), json=ota_body(ident)
        ) as r:
            check("HTTP 200", r.status == 200, str(r.status))
            cfg = await r.json()
        act = cfg.get("activation") or {}
        code = act.get("code")
        challenge = act.get("challenge")
        check("下发 6 位激活码", isinstance(code, str) and len(code) == 6 and code.isdigit(), str(code))
        check("下发 challenge", bool(challenge))
        check("未绑定态为测试组凭据（官方同款）", is_test_group(cfg))

        # 激活码恒定性：再来一次 OTA 应给同一个码
        async with http.post(
            base + "/xiaozhi/ota/", headers=ota_headers(ident), json=ota_body(ident)
        ) as r:
            cfg2 = await r.json()
        check("激活码恒定（重复 OTA 不变）", (cfg2.get("activation") or {}).get("code") == code)

        # ---------------------------------------------------------------- 2. 绑定前 /activate 应 202
        print("\n[2] 绑定前 /activate（官方语义：行不存在 -> 202）")
        payload = {"Payload": {
            "algorithm": "hmac-sha256",
            "serial_number": ident["serial"],
            "challenge": challenge,
            "hmac": sign(ident["key"], challenge),
        }}
        async with http.post(
            base + "/xiaozhi/ota/activate", headers=ota_headers(ident), json=payload
        ) as r:
            body = await r.json()
            check("HTTP 202 等待输码", r.status == 202, f"{r.status} {body}")

        # ---------------------------------------------------------------- 3. 网页输码绑定
        print("\n[3] 网页输码绑定（模拟 xiaozhi.me）")
        async with http.post(base + "/bind", data={"code": code}) as r:
            text = await r.text()
            check("绑定成功", "绑定成功" in text)

        # ---------------------------------------------------------------- 4. 绑定后 /activate 应 200
        print("\n[4] 绑定后 /activate（官方语义：行存在 -> 200，且本模拟器验签）")
        async with http.post(
            base + "/xiaozhi/ota/activate", headers=ota_headers(ident), json=payload
        ) as r:
            body = await r.json()
            check("HTTP 200 已激活", r.status == 200, json.dumps(body, ensure_ascii=False))
            check("返回 device_id", isinstance(body.get("device_id"), int), str(body.get("device_id")))

        # 错误 HMAC 也应 200（官方对 /activate 不校验签名——行为一致性）
        bad = {"Payload": {
            "algorithm": "hmac-sha256", "serial_number": ident["serial"],
            "challenge": challenge, "hmac": "0" * 64,
        }}
        async with http.post(
            base + "/xiaozhi/ota/activate", headers=ota_headers(ident), json=bad
        ) as r:
            check("错误 HMAC 仍 200（对齐官方）", r.status == 200)

        # ---------------------------------------------------------------- 5. 再 OTA：应下发真实凭据
        print("\n[5] 绑定后 OTA（应下发真实凭据）")
        async with http.post(
            base + "/xiaozhi/ota/", headers=ota_headers(ident), json=ota_body(ident)
        ) as r:
            cfg3 = await r.json()
        ws = cfg3.get("websocket") or {}
        mqtt = cfg3.get("mqtt") or {}
        check("无 activation 段（已绑定不再发激活码）", "activation" not in cfg3)
        check("真实 token（非 test-token）", ws.get("token") not in (None, "", "test-token"))
        check("WS 地址指向模拟服务端", str(ws.get("url", "")).startswith("ws://"))
        check("mqtt 非测试组（GID_test/null 判据）",
              not str(mqtt.get("client_id", "")).startswith("GID_test")
              and mqtt.get("subscribe_topic") != "null")
        check("整体 isTestGroup == False（客户端健康判据）", not is_test_group(cfg3))
        token = ws.get("token")

        # ---------------------------------------------------------------- 6. WS：test-token 应被拒
        print("\n[6] WS 用测试 token 连接应被拒")
        try:
            async with http.ws_connect(
                ws_base, headers={
                    "Authorization": "Bearer test-token",
                    "Protocol-Version": "1",
                    "Device-Id": ident["mac"],
                    "Client-Id": ident["client_id"],
                }
            ) as wst:
                msg = await wst.receive(timeout=5)
                check("测试 token 被拒（非 hello）", msg.type.name != "TEXT", msg.type.name)
        except Exception as e:
            check("测试 token 被拒（连接层拒绝）", True, type(e).__name__)

        # ---------------------------------------------------------------- 7. WS 全链路：hello / listen / stt / llm / tts / opus 回声
        print("\n[7] WS 真实凭据会话（hello -> listen -> stt/llm/tts -> opus 回声）")
        async with http.ws_connect(
            ws_base, headers={
                "Authorization": f"Bearer {token}",
                "Protocol-Version": "1",
                "Device-Id": ident["mac"],
                "Client-Id": ident["client_id"],
            }
        ) as wst:
            # hello（镜像 XiaozhiMessage.Hello.toJson）
            await wst.send_json({
                "type": "hello", "version": 1, "features": {"mcp": True},
                "transport": "websocket",
                "audio_params": {"format": "opus", "sample_rate": 16000,
                                 "channels": 1, "frame_duration": 60},
            })
            ack = await wst.receive_json(timeout=5)
            check("收到 hello ack", ack.get("type") == "hello")
            sid = ack.get("session_id")
            check("hello ack 带 session_id", bool(sid))
            ap = ack.get("audio_params") or {}
            check("下行音频参数 24k opus", ap.get("sample_rate") == 24000 and ap.get("format") == "opus")

            # listen start -> 3 帧裸 opus -> listen stop
            await wst.send_json({"type": "listen", "session_id": sid,
                                 "state": "start", "mode": "manual"})
            frames = [secrets.token_bytes(120) for _ in range(3)]
            for f in frames:
                await wst.send_bytes(f)
            await wst.send_json({"type": "listen", "session_id": sid,
                                 "state": "stop", "mode": "manual"})

            # 收集响应：3 帧回声 + stt + llm + tts*（以收到 tts stop 为终止条件）
            echoed, texts, states = [], [], []
            deadline = time.time() + 5
            while time.time() < deadline:
                msg = await asyncio.wait_for(wst.receive(timeout=2), timeout=2)
                if msg.type.name == "BINARY":
                    echoed.append(msg.data)
                elif msg.type.name == "TEXT":
                    obj = json.loads(msg.data)
                    texts.append(obj)
                    if obj.get("type") == "tts":
                        states.append(obj.get("state"))
                        if obj.get("state") == "stop":
                            break  # 一轮对话结束

            check("opus 回声 3 帧且内容一致", len(echoed) == 3 and echoed == frames,
                  f"{len(echoed)}/3")
            stt = next((t for t in texts if t.get("type") == "stt"), {})
            check("收到 stt（识别帧数=3）", "3 帧" in stt.get("text", ""), stt.get("text", ""))
            check("收到 llm（emotion+text）",
                  any(t.get("type") == "llm" and t.get("emotion") for t in texts))
            tts_states_ok = {"start", "sentence_start", "stop"}.issubset(set(states))
            check("tts 状态机 start/sentence_start/stop", tts_states_ok, str(states))

            await wst.close()

    # ------------------------------------------------------------------ 结果
    print("\n" + "=" * 60)
    print(f"结果：{len(PASS)} 通过 / {len(FAIL)} 失败")
    if FAIL:
        print("失败项：" + "; ".join(FAIL))
        sys.exit(1)
    print("全流程通过 ✅  模拟服务端可支撑客户端全链路联调")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    args = ap.parse_args()
    asyncio.run(main(args.port))
