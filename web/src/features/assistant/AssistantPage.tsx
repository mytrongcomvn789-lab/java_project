import { useCallback, useEffect, useRef, useState } from "react";
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

interface Turn {
  id: number;
  role: "user" | "assistant";
  text: string;
  /** Chỉ có ở lượt trả lời của trợ lý. */
  envelope?: AiEnvelope<ChatResult>;
}

const EMPTY_FILTERS = { code: "", status: "", from: "", to: "", stationId: "", sort: "" };

/**
 * Chat khách hàng (7.4/7.6). Chỉ đọc: AI không hủy/đổi đơn. Hội thoại chỉ nằm trong bộ nhớ trang:
 * rời trang, đổi đơn hay đăng xuất đều xóa conversationId và toàn bộ nội dung.
 * Câu trả lời là văn bản không tin cậy nên chỉ render dạng text.
 */
export default function AssistantPage() {
  const { user, isDemo } = useAuth();
  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [ordersError, setOrdersError] = useState("");
  const [orderId, setOrderId] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [conversationId, setConversationId] = useState<string | undefined>();
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<AiErrorInfo | null>(null);
  const [failures, setFailures] = useState(0);
  const controllerRef = useRef<AbortController | null>(null);
  const nextId = useRef(1);
  const endRef = useRef<HTMLDivElement | null>(null);

  const cancel = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
  }, []);

  const resetConversation = useCallback(() => {
    cancel();
    setTurns([]);
    setConversationId(undefined);
    setError(null);
    setFailures(0);
    setPending(false);
  }, [cancel]);

  // Rời trang: hủy yêu cầu đang chờ.
  useEffect(() => cancel, [cancel]);

  // Đăng xuất/hết phiên hoặc đổi tài khoản: xóa mọi dữ liệu của tài khoản cũ.
  useEffect(() => {
    window.addEventListener("sd:unauthorized", resetConversation);
    return () => window.removeEventListener("sd:unauthorized", resetConversation);
  }, [resetConversation]);
  const userId = user?.id;
  useEffect(() => {
    resetConversation();
    setOrders([]);
    setOrderId("");
    setDraft("");
  }, [userId, resetConversation]);

  // Danh sách đơn của chính khách hàng để gắn vào hội thoại (tùy chọn).
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
    endRef.current?.scrollIntoView?.({ block: "nearest" });
  }, [turns, pending, error]);

  function changeOrder(next: string) {
    // Server không cho đổi đơn trong cùng hội thoại: chọn đơn khác = hội thoại mới.
    setOrderId(next);
    resetConversation();
  }

  async function send() {
    const text = draft.trim();
    if (!text || pending || exhausted) return;
    cancel();
    const controller = new AbortController();
    controllerRef.current = controller;
    setPending(true);
    setError(null);
    try {
      const env = await aiService.chat(
        { message: text, conversationId, orderId: orderId || undefined },
        controller.signal,
      );
      if (controllerRef.current !== controller) return;
      const result = env.result;
      if (!result) throw new Error("empty-chat-result");
      setConversationId(result.conversationId);
      setTurns((t) => [
        ...t,
        { id: nextId.current++, role: "user", text },
        { id: nextId.current++, role: "assistant", text: result.reply, envelope: env },
      ]);
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

  const exhausted = failures >= MAX_AI_FAILURES;
  // 404 (hội thoại hết hạn/không thuộc tài khoản) hoặc 400 (đủ lượt, đổi đơn): cần bắt đầu lại.
  const needsNewConversation =
    exhausted || (error !== null && (error.kind === "notFound" || error.kind === "validation") && !!conversationId);
  const orderLabel = (o: OrderSummary) =>
    `${o.id.slice(0, 8)}… · ${o.statusLabel} · ${o.recipientName}`;

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

  return (
    <>
      <PageHeader
        title="Trợ lý AI"
        subtitle="Hỏi đáp chỉ đọc về đơn của bạn. Trợ lý không thể hủy, sửa hay xác nhận đơn."
        crumbs={["Trang chủ", "Trợ lý AI"]}
        code="FR-10"
      />
      <div className="customer-area">
        <section className="card customer-panel assistant-panel">
          <div className="assistant-order">
            <label htmlFor="assistant-order">Đơn muốn hỏi (tùy chọn)</label>
            <select
              id="assistant-order"
              value={orderId}
              onChange={(e) => changeOrder(e.target.value)}
              disabled={pending}
            >
              <option value="">Hỏi chung, không gắn đơn</option>
              {orders.map((o) => (
                <option key={o.id} value={o.id}>
                  {orderLabel(o)}
                </option>
              ))}
            </select>
            {orderId && (
              <small>
                Trạng thái chính thức xem tại <Link to={`/deliveries/${orderId}`}>chi tiết đơn</Link>, không dựa vào
                câu trả lời của trợ lý.
              </small>
            )}
            {ordersError && <small role="status">{ordersError}</small>}
            <small className="muted">Đổi đơn sẽ bắt đầu cuộc trò chuyện mới.</small>
          </div>

          <div className="assistant-log" aria-live="polite" aria-label="Nội dung trò chuyện">
            {turns.length === 0 && !pending && (
              <p className="muted">
                Chưa có tin nhắn. Hãy hỏi, ví dụ: “Đơn của tôi đang ở trạng thái nào?”. Không nhập mật khẩu, mã OTP
                hay thông tin nhạy cảm.
              </p>
            )}
            {turns.map((t) => (
              <div key={t.id} className={`assistant-turn assistant-${t.role}`}>
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
            ))}
            {pending && (
              <p role="status" className="muted">
                Trợ lý đang trả lời…
              </p>
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
            </div>
          )}

          <form
            className="assistant-form"
            onSubmit={(e) => {
              e.preventDefault();
              void send();
            }}
          >
            <label htmlFor="assistant-message" className="sr-only">
              Câu hỏi của bạn
            </label>
            <textarea
              id="assistant-message"
              value={draft}
              maxLength={MAX_MESSAGE_CHARS}
              rows={3}
              placeholder="Nhập câu hỏi…"
              onChange={(e) => setDraft(e.target.value)}
              disabled={pending || exhausted}
            />
            <div className="customer-actions">
              <small className="muted">
                {draft.length}/{MAX_MESSAGE_CHARS}
              </small>
              <button
                type="submit"
                className="customer-primary"
                disabled={pending || exhausted || draft.trim().length === 0}
              >
                {error?.retryable && !exhausted ? "Gửi lại" : "Gửi"}
              </button>
              {(needsNewConversation || turns.length > 0) && (
                <button type="button" onClick={resetConversation} disabled={pending}>
                  Cuộc trò chuyện mới
                </button>
              )}
            </div>
          </form>
        </section>
      </div>
    </>
  );
}
