import { formatMinutes } from "../../lib/formatDuration";
import AiSourceBadge from "../../components/ui/AiSourceBadge";
import { aiService, type EtaResult } from "../../lib/aiService";
import { useAiTask } from "../../lib/useAiTask";

const when = (v: string) => new Date(v).toLocaleString("vi-VN");

const REASONS: Record<string, string> = {
  STATUS_NOT_APPLICABLE: "Trạng thái hiện tại của đơn không áp dụng ước tính thời gian giao.",
  COORDINATES_MISSING: "Chưa có toạ độ trạm nên chưa ước tính được.",
  SPEED_INVALID: "Cấu hình ước tính chưa hợp lệ nên chưa ước tính được.",
  WAIT_INVALID: "Cấu hình ước tính chưa hợp lệ nên chưa ước tính được.",
};

const LIMITATIONS: Record<string, string> = {
  STRAIGHT_LINE_DISTANCE: "Dùng khoảng cách đường chim bay, không tính đường bay thực tế.",
  CONSTANT_SPEED_UNCALIBRATED: "Vận tốc là giả định cố định, chưa hiệu chuẩn.",
  CONSTANT_STATION_WAIT: "Thời gian chờ tại trạm là giá trị cố định.",
  START_CLAMPED_TO_NOW: "Mốc xuất phát đã qua nên được tính từ hiện tại.",
  OVERDUE_NO_LIVE_POSITION: "Đã quá thời gian ước tính; chưa có vị trí drone thực tế nên chưa biết còn bao lâu.",
  DEPARTURE_FROM_STATUS_HISTORY: "Mốc cất cánh lấy từ lịch sử trạng thái.",
};

function EtaBody({ eta }: { eta: EtaResult }) {
  if (!eta.applicable || !eta.computed) {
    return <p>{REASONS[eta.reason ?? ""] ?? "Chưa ước tính được thời gian giao."}</p>;
  }
  return (
    <>
      <p>
        <strong>
          {eta.etaAt ? when(eta.etaAt) : "—"}
          {eta.etaMinutes != null && ` (khoảng ${formatMinutes(eta.etaMinutes)} nữa)`}
        </strong>
      </p>
      <p className="muted">
        Bay khoảng {eta.travelMinutes != null ? formatMinutes(eta.travelMinutes) : "—"} · chờ trạm {eta.waitMinutes != null ? formatMinutes(eta.waitMinutes) : "—"} ·
        quãng đường {eta.distanceKm ?? "—"} km
      </p>
      <p className="muted">Độ tin cậy: thấp (chỉ là ước tính, không đảm bảo giờ giao).</p>
      {eta.limitations && eta.limitations.length > 0 && (
        <ul className="ai-limits">
          {eta.limitations.map((l) => (
            <li key={l}>{LIMITATIONS[l] ?? l}</li>
          ))}
        </ul>
      )}
    </>
  );
}

/** ETA (7.2): công thức, tự tải khi mở trang, tải lại khi đơn đổi trạng thái hoặc phiên bản. */
export default function OrderEtaPanel({
  orderId,
  status,
  version,
}: {
  orderId: string;
  status: string;
  version: number;
}) {
  const { state, start, canRetry } = useAiTask<EtaResult>(
    (signal) => aiService.eta(orderId, signal),
    `${orderId}:${status}:${version}`,
    true,
  );

  return (
    <section className="card customer-panel ai-panel" aria-live="polite">
      <header className="ai-panel-head">
        <h3>Thời gian giao dự kiến (ETA)</h3>
        {state.status === "success" && <AiSourceBadge envelope={state.envelope} />}
      </header>
      {state.status === "loading" && <p role="status">Đang ước tính thời gian giao…</p>}
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
              <p className="muted">Đã thử nhiều lần không được. Hãy tải lại trang sau ít phút.</p>
            )
          )}
        </div>
      )}
      {state.status === "success" &&
        (state.envelope.result ? (
          <EtaBody eta={state.envelope.result} />
        ) : (
          <p>Chưa có kết quả ước tính cho đơn này.</p>
        ))}
    </section>
  );
}
