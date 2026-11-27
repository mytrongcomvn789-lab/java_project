import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import AiSourceBadge from "../../components/ui/AiSourceBadge";
import {
  aiService,
  describeAiError,
  isAiCanceled,
  type AiEnvelope,
  type AiErrorInfo,
  type ChatResult,
} from "../../lib/aiService";
import { orderService, type OrderSummary } from "../../lib/orderService";
import { MAX_AI_FAILURES } from "../../lib/useAiTask";
import { useAuth } from "../auth/AuthContext";
import "./assistant.css";

// Khớp smartdrone.ai.max-chat-message-chars (mặc định 1000); server vẫn kiểm tra lại.
const MAX_MESSAGE_CHARS = 1000;
const MAX_HISTORY = 30;

interface Turn {
  id: number;
  role: "user" | "assistant";
  text: string;
  /** Chỉ có ở lượt trả lời của trợ lý. */
  envelope?: AiEnvelope<ChatResult>;
}

/** Một cuộc trò chuyện đã lưu trong trình duyệt (server không có API lịch sử). */
interface SavedChat {
  id: string;
  title: string;
  orderId: string;
  orderLabel: string;
  conversationId?: string;
  updatedAt: number;
  turns: Turn[];
}

type Mode = "general" | "order";

const EMPTY_FILTERS = { code: "", status: "", from: "", to: "", stationId: "", sort: "" };
const SUGGESTIONS: Record<Mode, string[]> = {
  general: [
    "Drone giao hàng có giới hạn cân nặng không?",
    "Làm sao để tạo yêu cầu giao hàng?",
    "Mã nhận hàng dùng như thế nào?",
  ],
  order: ["Đơn của tôi đang ở trạng thái nào?", "Khi nào đơn của tôi được giao?", "Vì sao đơn của tôi bị chậm?"],
};

const storageKey = (userId: string) => `sd:assistant-history:${userId}`;
function loadHistory(userId: string): SavedChat[] {
  try {
    const raw = localStorage.getItem(storageKey(userId));
    const parsed = raw ? (JSON.parse(raw) as SavedChat[]) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}
function persistHistory(userId: string, list: SavedChat[]) {
  try {
    localStorage.setItem(storageKey(userId), JSON.stringify(list));
  } catch {
    /* hết dung lượng hoặc bị chặn: bỏ qua, chat vẫn dùng bình thường */
  }
}
const newChatId = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;

function dayGroup(ts: number): string {
  const startOfToday = new Date().setHours(0, 0, 0, 0);
  const day = 86_400_000;
  if (ts >= startOfToday) return "Hôm nay";
  if (ts >= startOfToday - day) return "Hôm qua";
  if (ts >= startOfToday - 7 * day) return "7 ngày qua";
  return "Cũ hơn";
}

const SparkIcon = () => (
  <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
    <path d="M12 2l1.9 5.6L19.5 9.5l-5.6 1.9L12 17l-1.9-5.6L4.5 9.5l5.6-1.9L12 2zm7 11l.9 2.6 2.6.9-2.6.9L19 20l-.9-2.6-2.6-.9 2.6-.9L19 13z" />
  </svg>
);
const SendIcon = () => (
  <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.2"
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M12 19V5M5 12l7-7 7 7" />
  </svg>
);

/**
 * Chat khách hàng (7.4/7.6). Chỉ đọc: AI không hủy/đổi đơn. Hội thoại đang mở nằm trong bộ nhớ trang;
 * lịch sử lưu ở trình duyệt theo từng tài khoản (chỉ xem lại, không nối tiếp vì server không giữ lâu).
 * Câu trả lời là văn bản không tin cậy nên chỉ render dạng text.
 */
export default function AssistantPage() {
  const { user, isDemo } = useAuth();
  const userId = user?.id;
  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [ordersError, setOrdersError] = useState("");
  const [mode, setMode] = useState<Mode>("general");
  const [orderId, setOrderId] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [conversationId, setConversationId] = useState<string | undefined>();
  const [chatId, setChatId] = useState(newChatId);
  const [history, setHistory] = useState<SavedChat[]>([]);
  const [showHistory, setShowHistory] = useState(false);
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<AiErrorInfo | null>(null);
  const [failures, setFailures] = useState(0);
  const controllerRef = useRef<AbortController | null>(null);
  const nextId = useRef(1);
  const endRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);

  const cancel = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
  }, []);

  const resetConversation = useCallback(() => {
    cancel();
    setTurns([]);
    setConversationId(undefined);
    setChatId(newChatId());
    setError(null);
    setFailures(0);
    setPending(false);
  }, [cancel]);

  useEffect(() => cancel, [cancel]);

  // Hết phiên: xóa nội dung đang mở (lịch sử đã lưu vẫn thuộc về tài khoản, không bị hiển thị cho người khác).
  useEffect(() => {
    window.addEventListener("sd:unauthorized", resetConversation);
    return () => window.removeEventListener("sd:unauthorized", resetConversation);
  }, [resetConversation]);

  // Đổi tài khoản: nạp lịch sử của tài khoản mới, bỏ mọi thứ của tài khoản cũ.
  useEffect(() => {
    resetConversation();
    setOrders([]);
    setOrderId("");
    setMode("general");
    setDraft("");
    setHistory(userId ? loadHistory(userId) : []);
  }, [userId, resetConversation]);

  useEffect(() => {
    if (isDemo || !userId) return;
    let active = true;
    orderService
      .list(EMPTY_FILTERS, 1, 50)
      .then((p) => active && setOrders(p.items))
      .catch(() => active && setOrdersError("Không tải được danh sách đơn. Bạn vẫn có thể hỏi chung."));
    return () => {
      active = false;
    };
  }, [isDemo, userId]);

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ block: "nearest", behavior: "smooth" });
  }, [turns, pending, error]);

  // Tự co giãn ô nhập theo nội dung (tối đa ~6 dòng).
  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 150)}px`;
  }, [draft]);

  const orderLabel = (o: OrderSummary) => `${o.id.slice(0, 8)}… · ${o.statusLabel} · ${o.recipientName}`;
  const selectedOrder = orders.find((o) => o.id === orderId);

  function saveCurrent(next: Turn[], label: string, convId: string | undefined) {
    if (!userId || next.length === 0) return;
    const first = next.find((t) => t.role === "user")?.text ?? "Cuộc trò chuyện";
    const entry: SavedChat = {
      id: chatId,
      title: first.length > 60 ? first.slice(0, 60) + "…" : first,
      orderId,
      orderLabel: label,
      conversationId: convId,
      updatedAt: Date.now(),
      turns: next,
    };
    setHistory((h) => {
      const list = [entry, ...h.filter((c) => c.id !== chatId)].slice(0, MAX_HISTORY);
      persistHistory(userId, list);
      return list;
    });
  }

  function changeMode(next: Mode) {
    if (next === mode) return;
    setMode(next);
    if (next === "general") setOrderId("");
    resetConversation();
  }
  function changeOrder(next: string) {
    // Server không cho đổi đơn trong cùng hội thoại: chọn đơn khác = hội thoại mới.
    setOrderId(next);
    resetConversation();
  }

  function openSaved(chat: SavedChat) {
    cancel();
    setPending(false);
    setError(null);
    setFailures(0);
    // Nạp lại để nhắn tiếp: nếu server đã xóa hội thoại, send() tự mở hội thoại mới và vẫn giữ nội dung đang hiện.
    setChatId(chat.id);
    setTurns(chat.turns);
    setConversationId(chat.conversationId);
    setOrderId(chat.orderId);
    setMode(chat.orderId ? "order" : "general");
    nextId.current = Math.max(nextId.current, ...chat.turns.map((t) => t.id + 1));
    setShowHistory(false);
    inputRef.current?.focus();
  }
  function deleteSaved(id: string) {
    if (!userId) return;
    setHistory((h) => {
      const list = h.filter((c) => c.id !== id);
      persistHistory(userId, list);
      return list;
    });
    if (chatId === id) resetConversation();
  }
  function clearHistory() {
    if (!userId || !window.confirm("Xóa toàn bộ lịch sử hỏi đáp trên trình duyệt này?")) return;
    persistHistory(userId, []);
    setHistory([]);
    resetConversation();
  }
  function startNew() {
    resetConversation();
    setShowHistory(false);
    inputRef.current?.focus();
  }

  const exhausted = failures >= MAX_AI_FAILURES;
  const orderMissing = mode === "order" && !orderId;

  async function send(override?: string) {
    const text = (override ?? draft).trim();
    if (!text || pending || exhausted || orderMissing) return;
    cancel();
    const controller = new AbortController();
    controllerRef.current = controller;
    setPending(true);
    setError(null);
    try {
      const body = { message: text, orderId: orderId || undefined };
      let env: AiEnvelope<ChatResult>;
      try {
        env = await aiService.chat({ ...body, conversationId }, controller.signal);
      } catch (e) {
        // Hội thoại cũ đã hết hạn/bị xóa trên server: mở hội thoại mới, nội dung đã hiện vẫn giữ nguyên.
        if (conversationId && describeAiError(e).kind === "notFound" && !isAiCanceled(e)) {
          env = await aiService.chat(body, controller.signal);
        } else throw e;
      }
      if (controllerRef.current !== controller) return;
      const result = env.result;
      if (!result) throw new Error("empty-chat-result");
      setConversationId(result.conversationId);
      const next: Turn[] = [
        ...turns,
        { id: nextId.current++, role: "user", text },
        { id: nextId.current++, role: "assistant", text: result.reply, envelope: env },
      ];
      setTurns(next);
      saveCurrent(next, selectedOrder ? orderLabel(selectedOrder) : "", result.conversationId);
      setDraft("");
      setFailures(0);
    } catch (e) {
      if (controllerRef.current !== controller || isAiCanceled(e)) return;
      setFailures((n) => n + 1);
      setError(
        e instanceof Error && e.message === "empty-chat-result"
          ? { kind: "unknown", message: "Trợ lý chưa trả lời được. Vui lòng thử lại.", retryable: true }
          : describeAiError(e),
      );
    } finally {
      if (controllerRef.current === controller) {
        controllerRef.current = null;
        setPending(false);
      }
    }
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void send();
    }
  }

  const needsNewConversation =
    exhausted || (error !== null && (error.kind === "notFound" || error.kind === "validation") && !!conversationId);

  const grouped = useMemo(() => {
    const groups: { label: string; items: SavedChat[] }[] = [];
    for (const c of history) {
      const label = dayGroup(c.updatedAt);
      const g = groups.find((x) => x.label === label);
      if (g) g.items.push(c);
      else groups.push({ label, items: [c] });
    }
    return groups;
  }, [history]);

  if (isDemo) {
    return (
      <>
        <PageHeader title="Trợ lý AI" crumbs={["Trang chủ", "Trợ lý AI"]} code="FR-10" />
        <div className="customer-area">
          <div className="card customer-notice">Hãy đăng nhập bằng tài khoản thật để dùng trợ lý AI.</div>
        </div>
      </>
    );
  }

  const shown = turns;
  const empty = shown.length === 0 && !pending;

  return (
    <>
      <PageHeader
        title="Trợ lý AI"
        subtitle="Hỏi đáp chỉ đọc. Trợ lý không thể hủy, sửa hay xác nhận đơn."
        crumbs={["Trang chủ", "Trợ lý AI"]}
        code="FR-10"
      />
      <div className="assistant-shell">
        <aside className={`assistant-side${showHistory ? " is-open" : ""}`} aria-label="Lịch sử hỏi đáp">
          <button type="button" className="assistant-new" onClick={startNew}>
            <span aria-hidden="true">＋</span> Cuộc trò chuyện mới
          </button>
          <div className="assistant-history">
            {history.length === 0 && (
              <p className="assistant-history-empty">Chưa có lịch sử. Các cuộc trò chuyện của bạn sẽ hiện ở đây.</p>
            )}
            {grouped.map((g) => (
              <div key={g.label} className="assistant-group">
                <h4>{g.label}</h4>
                {g.items.map((c) => (
                  <div key={c.id} className={`assistant-item${chatId === c.id && turns.length > 0 ? " is-active" : ""}`}>
                    <button type="button" className="assistant-item-main" onClick={() => openSaved(c)}>
                      <span className="assistant-item-title">{c.title}</span>
                      <span className="assistant-item-sub">{c.orderId ? `Đơn ${c.orderId.slice(0, 8)}…` : "Hỏi chung"}</span>
                    </button>
                    <button
                      type="button"
                      className="assistant-item-del"
                      aria-label={`Xóa cuộc trò chuyện ${c.title}`}
                      onClick={() => deleteSaved(c.id)}
                    >
                      ×
                    </button>
                  </div>
                ))}
              </div>
            ))}
          </div>
          {history.length > 0 && (
            <button type="button" className="assistant-clear" onClick={clearHistory}>
              Xóa toàn bộ lịch sử
            </button>
          )}
          <small className="assistant-privacy">Lịch sử chỉ lưu trên trình duyệt này. Đừng nhập mật khẩu hay mã OTP.</small>
        </aside>

        <section className="assistant-main">
          <header className="assistant-top">
            <button type="button" className="assistant-toggle" onClick={() => setShowHistory((v) => !v)}>
              Lịch sử
            </button>
            <div className="assistant-tabs" role="tablist" aria-label="Chế độ hỏi đáp">
              <button type="button" role="tab" aria-selected={mode === "general"}
                className={mode === "general" ? "is-active" : ""} disabled={pending} onClick={() => changeMode("general")}>
                Hỏi chung
              </button>
              <button type="button" role="tab" aria-selected={mode === "order"}
                className={mode === "order" ? "is-active" : ""} disabled={pending} onClick={() => changeMode("order")}>
                Hỏi về đơn hàng
              </button>
            </div>
            {mode === "order" && (
              <select
                aria-label="Chọn đơn muốn hỏi"
                className="assistant-order-select"
                value={orderId}
                onChange={(e) => changeOrder(e.target.value)}
                disabled={pending}
              >
                <option value="">Chọn một đơn…</option>
                {orders.map((o) => (
                  <option key={o.id} value={o.id}>
                    {orderLabel(o)}
                  </option>
                ))}
              </select>
            )}
          </header>
          {(ordersError || orderId) && (
            <div className="assistant-note">
              {ordersError && <span role="status">{ordersError}</span>}
              {orderId && (
                <span>
                  Trạng thái chính thức xem tại <Link to={`/deliveries/${orderId}`}>chi tiết đơn</Link>, không dựa vào
                  câu trả lời của trợ lý.
                </span>
              )}
            </div>
          )}

          <div className="assistant-log" aria-live="polite" aria-label="Nội dung trò chuyện">
            {empty && (
              <div className="assistant-hero">
                <div className="assistant-logo"><SparkIcon /></div>
                <h2>{mode === "order" ? "Hỏi về đơn hàng của bạn" : "Tôi có thể giúp gì cho bạn?"}</h2>
                <p>
                  {mode === "order"
                    ? orderId
                      ? "Hỏi về trạng thái, thời gian giao hoặc tiến trình của đơn đã chọn."
                      : "Chọn một đơn ở phía trên để bắt đầu."
                    : "Hỏi về cách dùng dịch vụ giao hàng bằng drone. Chuyển sang “Hỏi về đơn hàng” để hỏi về một đơn cụ thể."}
                </p>
                <div className="assistant-chips">
                  {SUGGESTIONS[mode].map((s) => (
                    <button key={s} type="button" disabled={orderMissing || pending} onClick={() => void send(s)}>
                      {s}
                    </button>
                  ))}
                </div>
              </div>
            )}
            {shown.map((t) => (
              <div key={t.id} className={`assistant-row assistant-row-${t.role}`}>
                {t.role === "assistant" && <div className="assistant-avatar"><SparkIcon /></div>}
                <div className={`assistant-bubble assistant-${t.role}`}>
                  <div className="assistant-text">{t.text}</div>
                  {t.envelope && (
                    <div className="assistant-meta">
                      <AiSourceBadge envelope={t.envelope} />
                      {t.envelope.result && t.envelope.result.sources.length > 0 && (
                        <details>
                          <summary>Quy tắc hệ thống được cung cấp cho trợ lý</summary>
                          <ul>
                            {t.envelope.result.sources.map((s) => (
                              <li key={s.id}>{s.title}</li>
                            ))}
                          </ul>
                          <small className="muted">Đây là danh mục quy tắc, không chứng minh mọi câu trả lời đều đúng.</small>
                        </details>
                      )}
                    </div>
                  )}
                </div>
              </div>
            ))}
            {pending && (
              <div className="assistant-row assistant-row-assistant" role="status" aria-label="Trợ lý đang trả lời">
                <div className="assistant-avatar"><SparkIcon /></div>
                <div className="assistant-bubble assistant-assistant assistant-typing">
                  <span /><span /><span />
                </div>
              </div>
            )}
            <div ref={endRef} />
          </div>

          {error && (
            <div role="alert" className="assistant-error">
              <p>{error.message}</p>
              {error.kind === "network" && (
                <small>Nếu mạng chập chờn, yêu cầu có thể đã được xử lý; gửi lại có thể tạo thêm một lượt hỏi.</small>
              )}
              {error.traceId && <small className="muted">Mã tra cứu: {error.traceId}</small>}
              {exhausted && <small>Đã thất bại nhiều lần. Hãy bắt đầu cuộc trò chuyện mới hoặc thử lại sau.</small>}
              {needsNewConversation && (
                <button type="button" onClick={startNew}>Cuộc trò chuyện mới</button>
              )}
            </div>
          )}

          <form
            className="assistant-composer"
            onSubmit={(e) => {
              e.preventDefault();
              void send();
            }}
          >
            <label htmlFor="assistant-message" className="sr-only">Câu hỏi của bạn</label>
            <textarea
              id="assistant-message"
              ref={inputRef}
              value={draft}
              maxLength={MAX_MESSAGE_CHARS}
              rows={1}
              placeholder={orderMissing ? "Chọn một đơn trước khi hỏi…" : "Nhập câu hỏi… (Enter để gửi, Shift+Enter xuống dòng)"}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={onKeyDown}
              disabled={pending || exhausted || orderMissing}
            />
            <span className="assistant-count">{draft.length}/{MAX_MESSAGE_CHARS}</span>
            <button
              type="submit"
              className="assistant-send"
              aria-label={error?.retryable && !exhausted ? "Gửi lại" : "Gửi"}
              disabled={pending || exhausted || orderMissing || draft.trim().length === 0}
            >
              <SendIcon />
            </button>
            </form>
        </section>
      </div>
    </>
  );
}
