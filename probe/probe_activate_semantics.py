#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
/activate 语义探测 —— 追查"xiaozhi.me 已绑定但手机没反应"

实测发现（probe_ua_matrix.py）：未绑定设备调 /activate 一律返回
    HTTP 202 + {"message":"Device activation timeout"}
这个 "timeout" 到底是什么意思？本脚本逐项拆解服务端判定：

  T1 正确 HMAC + 当前 challenge          -> 期望 202（等待输码）
  T2 同一身份连续轮询 3 次               -> 观察是否一直是 202/同一 message
  T3 **错误 HMAC**（乱签）               -> 若仍 202：绑定前服务端根本不校验签名，
                                            202 纯粹是"未绑定"语义
                                            若 4xx：服务端在校验 HMAC，说明我们的签名是对的
  T4 **过期 challenge**（OTA 两次用旧的） -> 服务端是否认旧 challenge
  T5 不存在的 serial_number              -> 未知设备怎么办
  T6 Activation-Version 头取 1 / 1.7.6   -> 该头是否影响判定（ESP32 用 "2"，
                                            py-xiaozhi 用 app 版本号字符串）
"""
import hashlib
import hmac as hmac_mod
import json
import os
import time
import urllib.error
import urllib.request
import uuid

OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
BOARD_TYPE = "bread-compact-wifi"
APP_NAME = "xiaozhi-android"
APP_VERSION = "2.1.6"
UA = f"{BOARD_TYPE}/{APP_NAME}-{APP_VERSION}"


def new_identity():
    mac = "02:%02x:%02x:%02x:%02x:%02x" % tuple(os.urandom(5))
    clean = mac.replace(":", "")
    sn = "SN-" + hashlib.md5(clean.encode()).hexdigest()[:8].upper() + "-" + clean
    return {"device_id": mac, "client_id": str(uuid.uuid4()),
            "serial_number": sn, "hmac_key": os.urandom(32).hex()}


def ota(ident):
    body = json.dumps({
        "application": {"version": APP_VERSION, "elf_sha256": ident["hmac_key"]},
        "board": {"type": BOARD_TYPE, "name": APP_NAME, "ip": "192.168.1.100",
                  "mac": ident["device_id"]},
    }, ensure_ascii=False).encode()
    hdr = {"Activation-Version": "2", "Device-Id": ident["device_id"],
           "Client-Id": ident["client_id"], "Serial-Number": ident["serial_number"],
           "User-Agent": UA, "Accept-Language": "zh-CN", "Content-Type": "application/json"}
    req = urllib.request.Request(OTA_URL, data=body, headers=hdr, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, json.loads(r.read().decode("utf-8", "ignore"))
    except urllib.error.HTTPError as e:
        return e.code, {"_raw": e.read().decode("utf-8", "ignore")[:200]}


def activate(ident, challenge, hmac_hex=None, serial=None, act_ver="2", label=""):
    sig = hmac_hex or hmac_mod.new(
        ident["hmac_key"].encode(), challenge.encode(), hashlib.sha256
    ).hexdigest()
    payload = json.dumps({"Payload": {
        "algorithm": "hmac-sha256",
        "serial_number": serial if serial is not None else ident["serial_number"],
        "challenge": challenge,
        "hmac": sig,
    }}).encode()
    hdr = {"Device-Id": ident["device_id"], "Client-Id": ident["client_id"],
           "Content-Type": "application/json", "User-Agent": UA}
    if act_ver is not None:
        hdr["Activation-Version"] = act_ver
    req = urllib.request.Request(OTA_URL + "activate", data=payload, headers=hdr, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, r.read().decode("utf-8", "ignore")[:160]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")[:160]
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


def show(name, code, body, note=""):
    meaning = {200: "✅ 激活成功(已绑定)", 202: "⏳ 等待/未绑定", None: "❌ 网络异常"}
    tag = meaning.get(code, f"❗ HTTP {code}")
    print(f"  {name:<34} {tag:<20} {body[:90]}")
    if note:
        print(f"      └ {note}")


def main():
    print("=" * 88)
    print("/activate 服务端语义探测 —— 追查『已绑定但手机无反应』")
    print("=" * 88)

    ident = new_identity()
    st, cfg = ota(ident)
    if st != 200:
        print(f"OTA 失败: {st} {cfg}")
        return
    act = cfg.get("activation") or {}
    challenge = act.get("challenge")
    code = act.get("code")
    print(f"\n身份 device_id={ident['device_id']}")
    print(f"激活码={code}  challenge={challenge}\n")

    print("【基础】")
    show("T1 正确 HMAC + 当前 challenge", *activate(ident, challenge))
    time.sleep(2)
    show("T2 第 2 次轮询", *activate(ident, challenge))
    time.sleep(2)
    show("T2 第 3 次轮询", *activate(ident, challenge))

    print("\n【签名/参数异常 —— 判断服务端到底校验什么】")
    show("T3 错误 HMAC（乱签 64hex）",
         *activate(ident, challenge, hmac_hex="ab" * 32),
         note="若仍 202：绑定前服务端不校验签名，202 = 纯粹的『未绑定』语义")
    show("T4 过期的 challenge（上一轮 OTA 的）", "?", "?")
    # 先再 OTA 一次拿到新 challenge，然后用旧 challenge 发
    st2, cfg2 = ota(ident)
    new_challenge = (cfg2.get("activation") or {}).get("challenge")
    print(f"      新的 challenge={new_challenge}（旧={challenge}）")
    show("T4a 用旧 challenge（正确 HMAC）", *activate(ident, challenge),
         note="若 202 说明服务端仍认旧 challenge；若 4xx 说明 challenge 已作废")
    show("T4b 用新 challenge（正确 HMAC）", *activate(ident, new_challenge))
    show("T5 不存在的 serial_number",
         *activate(ident, new_challenge, serial="SN-00000000-000000000000"))

    print("\n【Activation-Version 头】")
    show("T6a Activation-Version=1", *activate(ident, new_challenge, act_ver="1"))
    show("T6b Activation-Version=1.7.6（py-xiaozhi 风格）",
         *activate(ident, new_challenge, act_ver="1.7.6"))
    show("T6c 不传 Activation-Version", *activate(ident, new_challenge, act_ver=None))

    print("\n" + "=" * 88)
    print("判读要点：")
    print("  1) 若 T3(错误 HMAC) 也是 202：服务端在绑定前不校验签名，")
    print("     202 只表示『这个设备还没绑定』，与签名/UA/challenge 都无关。")
    print("     => 输码后仍 202 = 服务端没把网页输码与这台设备关联上，")
    print("        即 xiaozhi.me 上绑的是【别的设备条目】（旧身份），不是当前 App 身份。")
    print("  2) 若 T3 是 4xx：服务端严格校验 HMAC，我们的签名是对的；")
    print("     那么输码后仍 202 同样是『绑错条目』或『码已过期』。")
    print("=" * 88)


if __name__ == "__main__":
    main()
