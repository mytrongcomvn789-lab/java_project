import AiSourceBadge from "../../components/ui/AiSourceBadge";
import { aiService, type OrderSummaryResult } from "../../lib/aiService";
import { useAiTask } from "../../lib/useAiTask";

const when = (v: string) => new Date(v).toLocaleString("vi-VN");

/**
 * Tóm tắt đơn (7.3). Chỉ chạy khi người dùng bấm nút vì mỗi lần gọi có thể tốn hạn mức AI.
 * Mọi văn bản được render dạng text thuần (React tự escape), không HTML, không liên kết do AI tạo.
 */
export default function OrderSummaryPanel({
  orderId,
  status,
  version,
}: {
  orderId: string;
  status: string;
  version: number;
}) {
  const { state, start, canRetry } = useAiTask<OrderSummaryResult>(
    (signal) => aiService.summary(orderId, signal),
    `${orderId}:${status}:${version}`,
    false,
  );
  const result = state.status === "success" ? state.envelope.result : null;

  return (
    <section className="card customer-panel ai-panel" aria-live="polite">
      <header className="ai-panel-head">
        <h3>Tóm tắt quá trình giao hàng</h3>
        {state.status === "success" && <AiSourceBadge envelope={state.envelope} />}
      </header>

      {state.status === "idle" && (
        <>
          <p className="muted">
            Tóm tắt các mốc xử lý của đơn này. Chỉ đọc, không thay đổi đơn. Số liệu thời gian lấy từ lịch
            sử đơn.
          </p>
          <button type="button" onClick={start}>
            Tạo tóm tắt
          </button>
        </>
      )}
      {state.status === "loading" && <p role="status">Đang tạo tóm tắt…</p>}
      {state.status === "error" && (
        <div role="alert">
          <p>{state.error.message}</p>
          {state.error.traceId && <small className="muted">Mã tra cứu: {state.error.traceId}</small>}
          {canRetry ? (
            <p>
              <button type="button" onClick={start}>
                Thử lại
              </button>
            </p>
          ) : (
            state.error.retryable && (
              <p className="muted">Đã thử nhiều lần không được. Hãy thử lại sau ít phút.</p>
            )
          )}
        </div>
      )}
      {state.status === "success" && !result && (
        <p>Không có nội dung tóm tắt. Vui lòng thử lại sau.</p>
      )}
      {result && (
        <>
          <p>{result.summary}</p>
          {result.highlights.length > 0 && (
            <ul>
              {result.highlights.map((h, i) => (
                <li key={i}>{h}</li>
              ))}
            </ul>
          )}
          {result.timeline.length === 0 ? (
            <p className="muted">Chưa có lịch sử xử lý để tóm tắt.</p>
          ) : (
            <ol className="ai-timeline">
              {result.timeline.map((s, i) => (
                <li key={`${s.status}-${i}`}>
                  <strong>{s.label}</strong> · {when(s.at)}
                  {s.minutesSincePrevious != null && (
                    <small className="muted"> (sau {s.minutesSincePrevious} phút)</small>
                  )}
                </li>
              ))}
            </ol>
          )}
          <p className="muted">
            Trạng thái chính thức là trạng thái của đơn ở trên, không phải nội dung tóm tắt này.
          </p>
          <button type="button" onClick={start}>
            Tạo lại tóm tắt
          </button>
        </>
      )}
    </section>
  );
}
