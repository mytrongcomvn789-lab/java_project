import json, sys, time, urllib.request, urllib.error, uuid

BASE = "http://localhost:5000/api"
results = []


def call(method, path, token=None, body=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else None), dict(r.headers)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt), dict(e.headers)
        except Exception:
            return e.code, txt, dict(e.headers)


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + ((" :: " + str(detail)) if detail else ""))


def login(email):
    s, b, _ = call("POST", "/v1/auth/login", body={"email": email, "password": "Demo@12345"})
    assert s == 200, (email, s, b)
    return b["accessToken"]


tok = {r: login(f"{r}@demo.local") for r in ["customer", "dispatcher", "operator", "manager", "admin"]}

# ---- đơn của khách demo ----
s, page, _ = call("GET", "/v1/orders?page=1&pageSize=50", tok["customer"])
orders = page["items"]
by_status = {}
for o in orders:
    by_status.setdefault(o["status"], o["id"])
print("Trạng thái đơn demo:", {k: v[:8] for k, v in by_status.items()})

# ---- ETA ----
for st in ["SCHEDULED", "PREPARING", "IN_TRANSIT", "DELIVERED", "PENDING"]:
    if st not in by_status:
        continue
    s, b, h = call("GET", f"/v1/ai/orders/{by_status[st]}/eta", tok["customer"])
    r = b.get("result") if isinstance(b, dict) else None
    ok = s == 200 and b["source"] == "FORMULA" and r is not None and "applicable" in r and "computed" in r
    detail = {k: r.get(k) for k in ["applicable", "computed", "reason", "etaMinutes", "confidence"]} if r else b
    check(f"ETA customer {st}", ok, detail)
    if st == "SCHEDULED":
        check("ETA có Cache-Control no-store", "no-store" in h.get("Cache-Control", ""), h.get("Cache-Control"))
        check("ETA computed có đủ trường web/mobile đọc", all(k in r for k in ["etaAt", "etaMinutes", "travelMinutes", "waitMinutes", "distanceKm", "confidence", "limitations"]), list(r.keys()))
s, b, _ = call("GET", f"/v1/ai/orders/{uuid.uuid4()}/eta", tok["customer"])
check("ETA đơn không tồn tại -> 404", s == 404, s)
s, b, _ = call("GET", f"/v1/ai/orders/{by_status['SCHEDULED']}/eta", tok["manager"])
check("ETA manager -> 403", s == 403, s)
s, b, _ = call("GET", f"/v1/ai/orders/{by_status['SCHEDULED']}/eta")
check("ETA không JWT -> 401", s == 401, s)
s, b, _ = call("GET", f"/v1/ai/orders/{by_status['SCHEDULED']}/eta", tok["dispatcher"])
check("ETA dispatcher (đơn thuộc trạm) -> 200", s == 200, s)
s, b, _ = call("GET", f"/v1/ai/orders/{by_status['SCHEDULED']}/eta", tok["operator"])
check("ETA operator -> 200", s == 200, s)

# ---- Tóm tắt ----
for st in ["SCHEDULED", "DELIVERED"]:
    if st not in by_status:
        continue
    s, b, _ = call("POST", f"/v1/ai/orders/{by_status[st]}/summary", tok["customer"])
    r = b.get("result") if isinstance(b, dict) else None
    ok = s == 200 and r is not None and "summary" in r and "timeline" in r and r["mode"] in ("AI_VERIFIED", "DETERMINISTIC_FALLBACK")
    check(f"Tóm tắt customer {st}", ok, {"source": b.get("source"), "mock": b.get("mock"), "degraded": b.get("degraded"), "mode": r and r.get("mode"), "timeline": r and len(r["timeline"]), "reason": b.get("fallbackReason")} if isinstance(b, dict) else b)
    if st == "SCHEDULED":
        print("   summary text:", r["summary"])
s, b, _ = call("POST", f"/v1/ai/orders/{by_status['SCHEDULED']}/summary", tok["manager"])
check("Tóm tắt manager -> 403", s == 403, s)

# ---- Chat ----
oid = by_status["SCHEDULED"]
s, b, _ = call("POST", "/v1/ai/chat", tok["customer"], {"message": "Đơn của tôi đang ở trạng thái nào?", "orderId": oid})
r = b.get("result") if isinstance(b, dict) else None
ok = s == 200 and r and r.get("conversationId") and "reply" in r
check("Chat lượt 1 (kèm orderId)", ok, {"source": b.get("source"), "mock": b.get("mock"), "model": b.get("model"), "orderId": r and r.get("orderId")} if isinstance(b, dict) else b)
conv = r["conversationId"] if r else None
print("   reply:", (r or {}).get("reply"))
check("Chat có sources", bool(r and r.get("sources")), r and [x["title"] for x in r["sources"]][:2])
s, b, _ = call("POST", "/v1/ai/chat", tok["customer"], {"message": "Tôi cần làm gì tiếp?", "conversationId": conv})
check("Chat lượt 2 (conversationId, không orderId)", s == 200 and b["result"]["conversationId"] == conv, s)
s, b, _ = call("POST", "/v1/ai/chat", tok["customer"], {"message": "hi", "conversationId": str(uuid.uuid4())})
check("Chat conversationId lạ -> 404", s == 404, s)
s, b, _ = call("POST", "/v1/ai/chat", tok["customer"], {"message": "hi", "conversationId": conv, "orderId": by_status.get("PENDING", oid)})
check("Chat đổi đơn trong hội thoại cũ -> 400", s == 400, (s, b.get("code") if isinstance(b, dict) else b))
s, b, _ = call("POST", "/v1/ai/chat", tok["customer"], {"message": "   "})
check("Chat message rỗng -> 400", s == 400, s)
s, b, _ = call("POST", "/v1/ai/chat", tok["customer"], {"message": "x" * 1001})
check("Chat 1001 ký tự -> 400", s == 400, s)
s, b, _ = call("POST", "/v1/ai/chat", tok["dispatcher"], {"message": "hi"})
check("Chat dispatcher -> 403", s == 403, s)
s, b, _ = call("POST", "/v1/ai/chat", tok["manager"], {"message": "hi"})
check("Chat manager -> 403", s == 403, s)
s, b, _ = call("POST", "/v1/ai/chat", None, {"message": "hi"})
check("Chat không JWT -> 401", s == 401, s)

# ---- Analytics ----
s, b, _ = call("POST", "/v1/ai/analytics", tok["manager"], {})
r = b.get("result") if isinstance(b, dict) else None
ok = s == 200 and r and all(k in r for k in ["report", "analysis", "insights", "recommendations", "narrativeMode"])
check("Analytics manager (mặc định)", ok, {"source": b.get("source"), "mock": b.get("mock"), "narrativeMode": r and r.get("narrativeMode"), "evidence": r and len(r["analysis"]["evidence"]), "warnings": r and len(r["analysis"]["warnings"]), "stationLoad": r and len(r["report"]["stationLoad"])} if isinstance(b, dict) else b)
if r:
    print("   insights:", r["insights"][:2])
    check("Analytics report.summary có trường web đọc", all(k in r["report"]["summary"] for k in ["orders", "rates", "deliveryTime", "from", "to", "timezone", "dateBasis"]), list(r["report"]["summary"].keys()))
    check("Analytics trend/failureRate có trong analysis", all(k in r["analysis"] for k in ["failureRate", "failureSample", "trend", "warnings", "evidence"]), list(r["analysis"].keys()))
s, b, _ = call("POST", "/v1/ai/analytics", tok["manager"], {"from": "2026-11-01", "to": "2026-11-24", "dateBasis": "COMPLETED"})
check("Analytics COMPLETED có khoảng ngày", s == 200, s)
s, b, _ = call("POST", "/v1/ai/analytics", tok["manager"], {"from": "2026-11-24", "to": "2026-11-01"})
check("Analytics from>to -> 400", s == 400, s)
s, b, _ = call("POST", "/v1/ai/analytics", tok["manager"], {"from": "2025-01-01", "to": "2026-11-01"})
check("Analytics >366 ngày -> 400", s == 400, s)
s, b, _ = call("POST", "/v1/ai/analytics", tok["customer"], {})
check("Analytics customer -> 403", s == 403, s)
s, b, _ = call("POST", "/v1/ai/analytics", tok["dispatcher"], {})
check("Analytics dispatcher -> 403", s == 403, s)

# ---- Rate limit (làm cuối) ----
codes = []
for i in range(8):
    s, b, _ = call("POST", "/v1/ai/chat", tok["customer"], {"message": f"hỏi {i}"})
    codes.append((s, b.get("code") if isinstance(b, dict) and s != 200 else None))
print("   chuỗi chat liên tiếp:", codes)
check("Rate limit chat: có 429 AI_RATE_LIMITED", any(c == (429, "AI_RATE_LIMITED") for c in codes), codes)

fails = [r for r in results if not r[1]]
print(f"\nTỔNG: {len(results) - len(fails)}/{len(results)} đạt")
sys.exit(1 if fails else 0)
