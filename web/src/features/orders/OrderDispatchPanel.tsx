import { useEffect, useState } from "react";
import { formatMinutes } from "../../lib/formatDuration";
import { dispatchService, STAGE_LABEL, type DispatchView } from "../../lib/dispatchService";

const clock = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleString("vi-VN", { hour: "2-digit", minute: "2-digit", day: "2-digit", month: "2-digit" })
    : "—";

/**
 * Điều phối drone của đơn. Khách hàng chỉ thấy thời gian chờ, lý do và giờ dự kiến;
 * nhân viên thấy thêm drone, trạm xuất phát của drone và thiết bị sạc đã đặt trước.
 * Mọi số liệu là ước tính theo công thức, không phải vị trí hay pin thật.
 */
export default function OrderDispatchPanel({
  orderId,
  status,
  version,
}: {
  orderId: string;
  status: string;
  version: number;
}) {
  const [data, setData] = useState<DispatchView | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    setData(null);
    setError("");
    dispatchService
      .forOrder(orderId, controller.signal)
      .then(setData)
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        setError(e instanceof Error ? e.message : "Không tải được thông tin điều phối drone.");
      });
    return () => controller.abort();
  }, [orderId, status, version]);

  // Đơn chưa/không còn ở bước điều phối thì không chiếm chỗ trên trang.
  if (!error && (!data || data.stage === "NONE")) return null;

  return (
    <section className="card customer-panel" aria-live="polite">
      <header className="ai-panel-head">
        <h3>Điều phối drone</h3>
        {data && <span className="ai-source ai-source-mock" title="Không phải vị trí hay pin thật của drone">Ước tính theo công thức</span>}
      </header>
      {error ? (
        <p role="alert">{error}</p>
      ) : data ? (
        <>
          <p>
            <strong>{STAGE_LABEL[data.stage]}</strong>
            {data.message ? ` · ${data.message}` : ""}
          </p>
          {data.plannedDepartureAt && (
            <p className="muted">
              {data.waitMinutes != null && data.waitMinutes > 0
                ? `Chờ khoảng ${formatMinutes(data.waitMinutes)} · `
                : ""}
              Khởi hành dự kiến {clock(data.plannedDepartureAt)} · Đến trạm nhận dự kiến{" "}
              {clock(data.plannedArrivalAt)}
            </p>
          )}
          {data.droneCode && (
            <p className="muted">
              Drone {data.droneCode}
              {data.originStationName ? ` (đang ở ${data.originStationName}` : ""}
              {data.repositionKm ? `, phải bay thêm ${data.repositionKm.toFixed(1)} km tới trạm gửi` : ""}
              {data.originStationName ? ")" : ""}
              {data.reservedChargerCode ? ` · đã đặt trước thiết bị sạc ${data.reservedChargerCode} ở trạm nhận` : ""}
            </p>
          )}
        </>
      ) : null}
    </section>
  );
}
