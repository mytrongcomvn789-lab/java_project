import { useEffect, useState } from "react";
import PageHeader from "../../components/ui/PageHeader";
import {
  stationService,
  type StationConnection,
  type StationMonitoring,
  type StationPackageLoad,
  type StationStatus,
} from "../../lib/stationService";
import { useAuth } from "../auth/AuthContext";
import "./stations.css";

const STATUS_LABELS: Record<StationStatus, string> = {
  ACTIVE: "Đang hoạt động",
  MAINTENANCE: "Đang bảo trì",
  INACTIVE: "Ngừng hoạt động",
};

const STATUS_ACTIONS: Record<StationStatus, string> = {
  ACTIVE: "Kích hoạt",
  MAINTENANCE: "Chuyển sang bảo trì",
  INACTIVE: "Ngừng hoạt động",
};

const ALL_STATUSES: StationStatus[] = ["ACTIVE", "MAINTENANCE", "INACTIVE"];

// Các nhóm số kiện do backend trả về (quy tắc đếm nằm ở StationLoadRules.java).
// Khi 4.8 xong và backend thêm nhóm mới vào DTO, chỉ cần thêm một dòng vào mảng này.
const PACKAGE_GROUPS: {
  key: keyof StationPackageLoad;
  label: string;
  hint: string;
}[] = [
  {
    key: "scheduled",
    label: "Đã lên lịch",
    hint: "Trạm gửi, kiện chưa tới trạm",
  },
  {
    key: "preparing",
    label: "Đang chuẩn bị",
    hint: "Trạm gửi, đã nhận, chờ xuất phát",
  },
  {
    key: "inbound",
    label: "Đang tới trạm",
    hint: "Trạm nhận, đang trên đường",
  },
  {
    key: "waitingPickup",
    label: "Chờ khách lấy",
    hint: "Trạm nhận, kiện đã tới",
  },
];

function formatTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" });
}

function connectionLabel(connection: StationConnection): string {
  switch (connection.state) {
    case "UNKNOWN":
      return "Chưa có tín hiệu từ thiết bị";
    case "ONLINE":
      return "Trực tuyến";
    case "OFFLINE":
      return "Mất kết nối";
    default:
      return connection.state;
  }
}

function connectionDetail(connection: StationConnection): string {
  const parts = [connection.source === "NONE" ? "Chưa tích hợp heartbeat thiết bị" : `Nguồn thiết bị: ${connection.source}`];
  if (connection.lastSeenAt) {
    const seen = new Date(connection.lastSeenAt);
    parts.push(
      `Lần cuối: ${Number.isNaN(seen.getTime()) ? connection.lastSeenAt : seen.toLocaleString("vi-VN")}`,
    );
  }
  return parts.join(" · ");
}

export default function StationMonitoringPage() {
  const { user } = useAuth();
  const canChangeStatus =
    user?.role === "LOGISTICS_MANAGER" || user?.role === "STATION_OPERATOR";
  const isOperator = user?.role === "STATION_OPERATOR";
  const [filter, setFilter] = useState<StationStatus | "">("");
  const [rows, setRows] = useState<StationMonitoring[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [actionError, setActionError] = useState("");
  const [message, setMessage] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError("");
    stationService
      .monitoring(filter || undefined)
      .then((data) => {
        if (active) setRows(data);
      })
      .catch((error) => {
        if (!active) return;
        setRows(null);
        setLoadError(
          error instanceof Error
            ? error.message
            : "Không thể tải dữ liệu giám sát trạm.",
        );
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [filter, revision]);

  useEffect(() => {
    const timer = window.setInterval(() => setRevision((value) => value + 1), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  function reload() {
    setMessage("");
    setActionError("");
    setRevision((value) => value + 1);
  }

  async function changeStatus(row: StationMonitoring, next: StationStatus) {
    if (busyId) return;
    if (
      !window.confirm(
        `Đổi trạng thái trạm "${row.name}" sang "${STATUS_LABELS[next]}"?`,
      )
    )
      return;
    setBusyId(row.id);
    setActionError("");
    setMessage("");
    try {
      await stationService.changeStatus(row.id, next);
      setMessage(
        `Đã đổi trạng thái trạm "${row.name}" sang "${STATUS_LABELS[next]}".`,
      );
      setRevision((value) => value + 1);
    } catch (error) {
      // Backend là nơi kiểm tra quyền; nếu bị chặn (403) thì hiện đúng thông báo backend trả về.
      setActionError(
        error instanceof Error
          ? error.message
          : "Không thể đổi trạng thái trạm.",
      );
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="stations-page">
      <PageHeader
        title="Giám sát trạm"
        subtitle="Dữ liệu trạm, đơn hàng và lịch giao đồng bộ từ hệ thống; tự cập nhật mỗi 30 giây."
        crumbs={["Trang chủ", "Giám sát trạm"]}
      />

      <div className="station-toolbar">
        <label className="station-filter" htmlFor="station-monitoring-status">
          <span>Trạng thái vận hành</span>
          <select
            id="station-monitoring-status"
            value={filter}
            onChange={(event) => {
              setRows(null);
              setFilter(event.target.value as StationStatus | "");
            }}
          >
            <option value="">Tất cả</option>
            {ALL_STATUSES.map((status) => (
              <option key={status} value={status}>
                {STATUS_LABELS[status]}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="station-reload"
          disabled={loading || busyId !== null}
          onClick={reload}
        >
          {loading ? "Đang tải…" : "Tải lại"}
        </button>
      </div>

      {isOperator && (
        <p className="station-hint">
          Bạn chỉ xem và đổi trạng thái được các trạm đã được phân công.
        </p>
      )}
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

      {loadError ? (
        <div className="station-panel" role="alert">
          <p className="station-error">{loadError}</p>
          <div className="station-actions">
            <button type="button" onClick={reload}>
              Thử tải lại
            </button>
          </div>
        </div>
      ) : rows === null ? (
        <p role="status">Đang tải dữ liệu giám sát trạm…</p>
      ) : rows.length === 0 ? (
        <div className="station-panel">
          <h3>Không có trạm nào</h3>
          <p>
            {filter
              ? `Không có trạm nào ở trạng thái "${STATUS_LABELS[filter]}".`
              : "Chưa có trạm nào trong phạm vi của bạn."}
          </p>
        </div>
      ) : (
        <div className="station-grid">
          {rows.map((row) => (
            <article className="station-panel" key={row.id}>
              <div className="station-heading">
                <h3>{row.name}</h3>
                <span className={`station-status ${row.status.toLowerCase()}`}>
                  {STATUS_LABELS[row.status]}
                </span>
              </div>

              <p className="station-address">
                {row.address ?? "Chưa có địa chỉ"}
              </p>

              <div>
                <p className="station-metric-title">Kết nối</p>
                <p className="station-connection">
                  {connectionLabel(row.connection)}
                </p>
                <p className="station-hint">
                  {connectionDetail(row.connection)}
                </p>
              </div>

              <div>
                <p className="station-metric-title">
                  Khung giờ hiện tại ({formatTime(row.slot.start)} –{" "}
                  {formatTime(row.slot.end)})
                </p>
                <p>
                  Đang giữ {row.slot.used} / {row.capacity} chỗ · Còn trống{" "}
                  {row.slot.free}
                </p>
              </div>

              <div>
                <p className="station-metric-title">Số kiện</p>
                <dl className="station-metrics">
                  {PACKAGE_GROUPS.map((group) => (
                    <div key={group.key} className="station-metric">
                      <dt>{group.label}</dt>
                      <dd>{row.packages[group.key]}</dd>
                      <small>{group.hint}</small>
                    </div>
                  ))}
                </dl>
                <p>
                  Đang ở trạm: <strong>{row.packages.atStation}</strong> · Tổng
                  đang xử lý: <strong>{row.packages.processingTotal}</strong>
                </p>
              </div>

              {canChangeStatus && (
                <div className="station-actions">
                  {ALL_STATUSES.filter((status) => status !== row.status).map(
                    (status) => (
                      <button
                        type="button"
                        key={status}
                        disabled={busyId !== null}
                        onClick={() => void changeStatus(row, status)}
                      >
                        {busyId === row.id
                          ? "Đang đổi…"
                          : STATUS_ACTIONS[status]}
                      </button>
                    ),
                  )}
                </div>
              )}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
