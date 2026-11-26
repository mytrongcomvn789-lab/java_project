import json, sys, urllib.request, urllib.error

BASE = "http://localhost:5000/api"
MODE = sys.argv[1]  # fallback | nofallback


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def login(email):
    return call("POST", "/v1/auth/login", body={"email": email, "password": "Demo@12345"})[1]["accessToken"]


import time
for _ in range(30):
    try:
        cust, mgr = login("customer@demo.local"), login("manager@demo.local")
        break
    except KeyError:
        time.sleep(2)
orders = call("GET", "/v1/orders?page=1&pageSize=50", cust)[1]["items"]
oid = next(o["id"] for o in orders if o["status"] == "SCHEDULED")
ok_all = True


def show(name, s, b, expect):
    global ok_all
    r = b.get("result") if isinstance(b, dict) else None
    info = {"http": s, "source": b.get("source"), "degraded": b.get("degraded"), "mock": b.get("mock"),
            "reason": b.get("fallbackReason"), "code": b.get("code"),
            "mode": (r or {}).get("mode") or (r or {}).get("narrativeMode"),
            "reply": ((r or {}).get("reply") or "")[:90]}
    ok = expect(s, b, r)
    ok_all &= bool(ok)
    print(("PASS " if ok else "FAIL ") + name, {k: v for k, v in info.items() if v not in (None, "")})


s, b = call("GET", f"/v1/ai/orders/{oid}/eta", cust)
show("ETA không phụ thuộc AI (vẫn FORMULA)", s, b, lambda s, b, r: s == 200 and b["source"] == "FORMULA")

if MODE == "fallback":
    s, b = call("POST", f"/v1/ai/orders/{oid}/summary", cust)
    show("Tóm tắt fallback", s, b, lambda s, b, r: s == 200 and b["source"] == "FALLBACK" and b["degraded"] and r and r["mode"] == "DETERMINISTIC_FALLBACK" and r["timeline"])
    s, b = call("POST", "/v1/ai/chat", cust, {"message": "Đơn của tôi đâu?", "orderId": oid})
    show("Chat fallback (có reply + conversationId)", s, b, lambda s, b, r: s == 200 and b["source"] == "FALLBACK" and r and r["reply"] and r["conversationId"])
    s, b = call("POST", "/v1/ai/analytics", mgr, {})
    show("Analytics fallback (số liệu vẫn đủ)", s, b, lambda s, b, r: s == 200 and b["source"] == "FALLBACK" and r and r["narrativeMode"] == "DETERMINISTIC_FALLBACK" and r["report"]["summary"])
else:
    s, b = call("POST", f"/v1/ai/orders/{oid}/summary", cust)
    show("Tóm tắt (docs 7.3: luôn có bản xác định kể cả khi fallback chung tắt)", s, b, lambda s, b, r: s == 200 and b["source"] == "FALLBACK" and r and r["mode"] == "DETERMINISTIC_FALLBACK" and b["fallbackReason"] == "AI_NOT_CONFIGURED")
    s, b = call("POST", "/v1/ai/chat", cust, {"message": "Đơn của tôi đâu?", "orderId": oid})
    show("Chat không fallback -> AI_NOT_CONFIGURED", s, b, lambda s, b, r: s == 503 and b.get("code") == "AI_NOT_CONFIGURED")
    s, b = call("POST", "/v1/ai/analytics", mgr, {})
    # 7.5: AI_NOT_CONFIGURED vẫn trả báo cáo xác định (HTTP 200) kể cả khi fallback chung tắt
    show("Analytics không fallback chung (docs 7.5: vẫn 200 FALLBACK)", s, b, lambda s, b, r: s == 200 and b["source"] == "FALLBACK" and r)

print("TỔNG:", "ĐẠT" if ok_all else "CÓ LỖI")
sys.exit(0 if ok_all else 1)
