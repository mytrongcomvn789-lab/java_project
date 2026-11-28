import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import { formatMinutes } from "../../lib/formatDuration";
import {
  DRONE_STATUS_LABEL,
  STAGE_LABEL,
  dispatchService,
  type DispatchBoard,
} from "../../lib/dispatchService";
import { useAuth } from "../auth/AuthContext";
import "./stations.css";
import "./chargers.css";

const REFRESH_MS = 30000;
const clock = (iso: string | null) =>
  iso ? new Date(iso).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit", hour12: false }) : "—";
const errorText = (e: unknown, fallback: string) => (e instanceof Error && e.message ? e.message : fallback);

/** Bảng điều phối drone cho nhân viên: đội drone dùng chung và các đơn đang chờ hoặc đã có drone. */
export default function DroneDispatchBoardPage() {
  const { user } = useAuth();
  const isOperator = user?.role === "STATION_OPERATOR";
  const [board, setBoard] = useState<DispatchBoard | null>(null);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    dispatchService
      .board(controller.signal)
      .then((value) => {
        if (controllerRef.current !== controller) return;
        setBoard(value);
        setError("");
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted || controllerRef.current !== controller) return;
        setError(errorText(e, "Không thể tải bảng điều phối drone."));
      });
  }, []);

  useEffect(() => {
    load();
    const timer = window.setInterval(load, REFRESH_MS);
    return () => {
      window.clearInterval(timer);
      controllerRef.current?.abort();
    };
  }, [load]);

  async function assign(orderId: string) {
    if (busy) return;
    setBusy(orderId);
    setActionError("");
    setMessage("");
    try {
      await dispatchService.assign(orderId);
      setMessage("Đã tính lại và phân công drone cho đơn.");
      load();
    } catch (e) {
      setActionError(errorText(e, "Không phân công được drone cho đơn."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="stations-page">
      <PageHeader
        title="Điều phối drone"
        subtitle="Drone dùng chung giữa các trạm: hệ thống chọn drone, đặt trước chỗ sạc ở trạm nhận và tính thời gian chờ."
        crumbs={["Trang chủ", "Điều phối drone"]}
      />
      <p className="sim-note">
        Mọi mốc thời gian là <strong>ước tính theo công thức</strong> (khoảng cách đường chim bay, tốc độ trung bình,
        tốc độ sạc giả lập), chưa lấy từ drone thật. Tự làm mới mỗi 30 giây.
      </p>
      <div className="station-toolbar">
        <button type="button" className="station-reload" onClick={load}>
          Tải lại
        </button>
        <Link to="/stations/monitoring" className="station-reload">
          Giám sát trạm
        </Link>
      </div>
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

      {error && !board ? (
        <div className="station-panel" role="alert">
          <p className="station-error">{error}</p>
        </div>
      ) : !board ? (
        <p role="status">Đang tải bảng điều phối…</p>
      ) : (
        <>
          <section className="station-panel">
            <h3>Đội drone ({board.fleet.length})</h3>
            {board.fleet.length === 0 ? (
              <p className="station-hint">Hệ thống chưa có drone.</p>
            ) : (
              <ul className="idle-drones">
                {board.fleet.map((d) => (
                  <li key={d.id}>
                    <strong>{d.code}</strong> · {DRONE_STATUS_LABEL[d.status]} · pin {d.batteryPercent}%
                    <br />
                    <small className="muted">{d.stationName ?? "Đang bay"}</small>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="station-panel">
            <h3>Đơn cần hoặc đã có drone ({board.orders.length})</h3>
            {board.orders.length === 0 ? (
              <p className="station-hint">Không có đơn nào đang chờ điều phối trong phạm vi trạm của bạn.</p>
            ) : (
              <div className="charger-grid">
                {board.orders.map((row) => {
                  const d = row.dispatch;
                  return (
                    <article key={row.orderId} className="charger-card">
                      <header>
                        <div>
                          <h4>
                            <Link to={`/deliveries/${row.orderId}`}>Đơn {row.orderId.slice(0, 8)}…</Link>
                          </h4>
                          <small className="muted">
                            {row.pickupStationName} → {row.dropoffStationName}
                          </small>
                        </div>
                      </header>
                      <p>
                        <strong>{STAGE_LABEL[d.stage]}</strong>
                      </p>
                      {d.message && <p className="station-hint">{d.message}</p>}
                      <dl>
                        {d.droneCode && (
                          <>
                            <dt>Drone</dt>
                            <dd>
                              {d.droneCode}
                              {d.originStationName ? ` (từ ${d.originStationName})` : ""}
                            </dd>
                          </>
                        )}
                        {d.repositionKm ? (
                          <>
                            <dt>Bay tới trạm gửi</dt>
                            <dd>{d.repositionKm.toFixed(1)} km</dd>
                          </>
                        ) : null}
                        {d.reservedChargerCode && (
                          <>
                            <dt>Chỗ sạc đặt trước</dt>
                            <dd>{d.reservedChargerCode}</dd>
                          </>
                        )}
                        {d.waitMinutes != null && (
                          <>
                            <dt>Thời gian chờ</dt>
                            <dd>{d.waitMinutes === 0 ? "Không chờ" : formatMinutes(d.waitMinutes)}</dd>
                          </>
                        )}
                        {d.plannedDepartureAt && (
                          <>
                            <dt>Khởi hành</dt>
                            <dd>{clock(d.plannedDepartureAt)}</dd>
                            <dt>Đến trạm nhận</dt>
                            <dd>{clock(d.plannedArrivalAt)}</dd>
                          </>
                        )}
                      </dl>
                      {isOperator && row.orderStatus === "PREPARING" && d.stage !== "IN_FLIGHT" && (
                        <div className="station-actions">
                          <button
                            type="button"
                            className="station-primary"
                            disabled={busy !== null}
                            onClick={() => void assign(row.orderId)}
                          >
                            {busy === row.orderId
                              ? "Đang xử lý…"
                              : d.assigned
                                ? "Tính lại điều phối"
                                : "Điều phối ngay"}
                          </button>
                        </div>
                      )}
                    </article>
                  );
                })}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
