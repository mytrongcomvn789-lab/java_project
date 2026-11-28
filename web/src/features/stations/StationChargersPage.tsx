import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import { formatMinutes } from "../../lib/formatDuration";
import { stationService, type ChargerDetail, type StationChargers } from "../../lib/stationService";
import ChargerIcon, { CHARGER_STATE_LABEL, ChargerLegend } from "./ChargerIcon";
import "./stations.css";
import "./chargers.css";

const REFRESH_MS = 30000;

const clock = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit", hour12: false })
    : "—";

const errorText = (e: unknown, fallback: string) => (e instanceof Error && e.message ? e.message : fallback);

/** Chi tiết thiết bị sạc và drone của một trạm. Chỉ nhân viên trạm được phân công (backend cũng chặn bằng 403/404). */
export default function StationChargersPage() {
  const { id = "" } = useParams();
  const [data, setData] = useState<StationChargers | null>(null);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    stationService
      .chargers(id, controller.signal)
      .then((value) => {
        if (controllerRef.current !== controller) return;
        setData(value);
        setError("");
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted || controllerRef.current !== controller) return;
        setError(errorText(e, "Không thể tải dữ liệu thiết bị sạc."));
      });
  }, [id]);

  // Tải lần đầu và tự làm mới định kỳ; hủy yêu cầu khi rời trang hoặc đổi trạm.
  useEffect(() => {
    setData(null);
    load();
    const timer = window.setInterval(load, REFRESH_MS);
    return () => {
      window.clearInterval(timer);
      controllerRef.current?.abort();
    };
  }, [load]);

  async function run(key: string, action: () => Promise<StationChargers>, success: string) {
    if (busy) return;
    setBusy(key);
    setActionError("");
    setMessage("");
    try {
      setData(await action());
      setMessage(success);
    } catch (e) {
      setActionError(errorText(e, "Không thực hiện được thao tác."));
    } finally {
      setBusy(null);
    }
  }

  const release = (c: ChargerDetail) =>
    run(
      `r-${c.id}`,
      () => stationService.releaseCharger(id, c.id),
      `Drone ${c.drone?.code ?? ""} đã rời thiết bị ${c.code}.`,
    );
  const toggleMaintenance = (c: ChargerDetail) => {
    const next = c.state !== "MAINTENANCE";
    if (!window.confirm(next ? `Chuyển thiết bị ${c.code} sang bảo trì?` : `Đưa thiết bị ${c.code} hoạt động lại?`))
      return;
    void run(
      `m-${c.id}`,
      () => stationService.setChargerMaintenance(id, c.id, next),
      next ? `Thiết bị ${c.code} đã chuyển sang bảo trì.` : `Thiết bị ${c.code} đã hoạt động lại.`,
    );
  };

  return (
    <div className="stations-page">
      <PageHeader
        title={data ? `Thiết bị sạc và drone · ${data.stationName}` : "Thiết bị sạc và drone"}
        subtitle="Pin, thời gian sạc còn lại và drone đang cắm trên từng thiết bị của trạm."
        crumbs={["Trang chủ", "Giám sát trạm", "Thiết bị sạc"]}
      />

      <div className="station-toolbar">
        <Link to="/stations/monitoring" className="station-reload">
          ← Về giám sát trạm
        </Link>
        <button type="button" className="station-reload" onClick={load}>
          Tải lại
        </button>
      </div>

      <p className="sim-note">
        Số liệu sạc (% pin, thời gian còn lại, nhiệt độ, heartbeat) hiện là <strong>giả lập theo công thức</strong>,
        chưa đọc từ phần cứng thật. Tự làm mới mỗi 30 giây.
      </p>
      {message && (
        <p role="status" className="station-success">
          {message}
        </p>
      )}
      {actionError && (
        <p role="alert" className="station-error">
          {actionError}
        </p>
      )}

      {error && !data ? (
        <div className="station-panel" role="alert">
          <p className="station-error">{error}</p>
          <p className="station-hint">Bạn chỉ xem được trạm đã được phân công cho tài khoản của mình.</p>
        </div>
      ) : !data ? (
        <p role="status">Đang tải thiết bị sạc…</p>
      ) : (
        <>
          {error && (
            <p role="alert" className="station-error">
              Không làm mới được: {error} (đang hiển thị dữ liệu lần trước)
            </p>
          )}
          <section className="charger-summary" aria-label="Tóm tắt thiết bị sạc">
            <div className="chip">
              <strong>{data.summary.total}</strong>
              <span>Tổng thiết bị</span>
            </div>
            <div className="chip">
              <strong>{data.summary.empty}</strong>
              <span>Trống</span>
            </div>
            <div className="chip">
              <strong>{data.summary.charging}</strong>
              <span>Đang sạc</span>
            </div>
            <div className="chip">
              <strong>{data.summary.full}</strong>
              <span>Đã đầy</span>
            </div>
            <div className="chip">
              <strong>{data.summary.maintenance}</strong>
              <span>Bảo trì</span>
            </div>
            <div className="chip">
              <strong>
                {data.summary.online}/{data.summary.total}
              </strong>
              <span>Trực tuyến</span>
            </div>
          </section>

          <p>
            {data.summary.empty > 0 ? (
              <>
                Trạm đang còn <strong>{data.summary.empty}</strong> thiết bị trống, có thể nhận drone ngay.
              </>
            ) : data.nextSlotAt ? (
              <>
                Không còn thiết bị trống. Dự kiến có chỗ nhận drone tiếp theo lúc{" "}
                <strong>{clock(data.nextSlotAt)}</strong>. Lưu ý: drone sạc đầy chỉ giải phóng thiết bị khi đã rời
                vị trí sạc.
              </>
            ) : (
              <>Không có thiết bị nào khả dụng để nhận drone (tất cả đang bảo trì).</>
            )}
          </p>
          <ChargerLegend />

          <div className="charger-grid">
            {data.chargers.map((c) => (
              <article key={c.id} className={`charger-card ${c.state.toLowerCase()}`}>
                <header>
                  <ChargerIcon code={c.code} state={c.state} percent={c.batteryPercent} />
                  <div>
                    <h4>{c.code}</h4>
                    <small className="muted">
                      {CHARGER_STATE_LABEL[c.state]} · {c.online ? "Trực tuyến" : "Mất kết nối"}
                    </small>
                  </div>
                </header>
                {c.drone && c.batteryPercent != null ? (
                  <>
                    <div
                      className="battery-bar"
                      role="progressbar"
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={c.batteryPercent}
                      aria-label={`Pin drone ${c.drone.code}`}
                    >
                      <span style={{ width: `${c.batteryPercent}%` }} />
                    </div>
                    <dl>
                      <dt>Drone</dt>
                      <dd>{c.drone.code}</dd>
                      <dt>Pin</dt>
                      <dd>
                        {c.startPercent}% → {c.batteryPercent}% (mục tiêu {c.targetPercent}%)
                      </dd>
                      <dt>Đã sạc</dt>
                      <dd>{c.chargedMinutes != null ? formatMinutes(c.chargedMinutes) : "—"}</dd>
                      <dt>Còn lại</dt>
                      <dd>
                        {c.remainingMinutes === 0
                          ? "Đã xong"
                          : c.remainingMinutes != null
                            ? formatMinutes(c.remainingMinutes)
                            : "—"}
                      </dd>
                      <dt>Dự kiến xong</dt>
                      <dd>{clock(c.readyAt)}</dd>
                      <dt>Nhiệt độ pin</dt>
                      <dd>{c.temperatureC != null ? `${c.temperatureC} °C` : "—"}</dd>
                      <dt>Heartbeat</dt>
                      <dd>{clock(c.lastHeartbeatAt)}</dd>
                    </dl>
                  </>
                ) : (
                  <dl>
                    <dt>Drone</dt>
                    <dd>{c.state === "MAINTENANCE" ? "Không nhận drone (bảo trì)" : "Chưa có drone"}</dd>
                    <dt>Heartbeat</dt>
                    <dd>{clock(c.lastHeartbeatAt)}</dd>
                  </dl>
                )}
                <div className="station-actions">
                  {c.canRelease && (
                    <button
                      type="button"
                      className="station-primary"
                      disabled={busy !== null}
                      onClick={() => void release(c)}
                    >
                      {busy === `r-${c.id}` ? "Đang xử lý…" : "Cho drone rời"}
                    </button>
                  )}
                  {!c.drone && (
                    <button type="button" disabled={busy !== null} onClick={() => toggleMaintenance(c)}>
                      {c.state === "MAINTENANCE" ? "Hoạt động lại" : "Chuyển sang bảo trì"}
                    </button>
                  )}
                </div>
              </article>
            ))}
          </div>

          <section className="station-panel">
            <h3>Drone đang đỗ tại trạm (chưa cắm sạc)</h3>
            {data.idleDrones.length === 0 ? (
              <p className="station-hint">Không có drone nào đang đỗ tại trạm.</p>
            ) : (
              <ul className="idle-drones">
                {data.idleDrones.map((d) => (
                  <li key={d.id}>
                    <strong>{d.code}</strong> · pin {d.batteryPercent}%
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </div>
  );
}
