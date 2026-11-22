import { useEffect, useState } from "react";
import PageHeader from "../../components/ui/PageHeader";
import {
  adminService,
  type AuditEvent,
  type AuditEventPage,
} from "../../lib/adminService";
import { ROLE_LABELS } from "../../lib/roles";
import "./admin.css";

const PAGE_SIZE = 20;

// Tên tiếng Việt cho các action backend đang ghi (xem docs/05-api/audit-api.md).
const ACTION_LABELS: Record<string, string> = {
  USER_REGISTERED: "Đăng ký tài khoản",
  LOGIN_SUCCEEDED: "Đăng nhập",
  LOGIN_FAILED: "Đăng nhập thất bại",
  LOGOUT: "Đăng xuất",
  REFRESH_TOKEN_REUSED: "Token bị dùng lại (nghi đánh cắp)",
  PASSWORD_CHANGED: "Đổi mật khẩu",
  USER_ACCESS_CHANGED: "Đổi quyền / khóa tài khoản",
  ACCOUNT_CREATED: "Tạo tài khoản",
  ACCOUNT_UPDATED: "Cập nhật tài khoản",
  ACCOUNT_LOCKED: "Khóa tài khoản",
  ACCOUNT_UNLOCKED: "Mở khóa tài khoản",
  ROLE_PERMISSIONS_UPDATED: "Đổi quyền của vai trò",
  SETTINGS_UPDATED: "Đổi cấu hình hệ thống",
  STATION_CREATED: "Tạo trạm",
  STATION_UPDATED: "Cập nhật trạm",
  STATION_STATUS_CHANGED: "Đổi trạng thái trạm",
  API_KEY_ISSUED: "Cấp API key",
  API_KEY_REVOKED: "Thu hồi API key",
  API_KEY_ROTATED: "Xoay vòng API key",
};

// Hành động cần chú ý về bảo mật được tô màu cảnh báo.
const WARNING_ACTIONS = new Set([
  "LOGIN_FAILED",
  "REFRESH_TOKEN_REUSED",
  "ACCOUNT_LOCKED",
]);

const TARGET_LABELS: Record<string, string> = {
  USER: "Tài khoản",
  ROLE: "Vai trò",
  SETTINGS: "Cấu hình",
  STATION: "Trạm",
  API_KEY: "API key",
};

const formatTime = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString("vi-VN");
};

// Ô ngày (yyyy-mm-dd, giờ địa phương) -> mốc ISO. "Đến ngày" tính hết ngày đó.
function dayStart(day: string, plusDays = 0): string | undefined {
  if (!day) return undefined;
  const [y, m, d] = day.split("-").map(Number);
  return new Date(y, m - 1, d + plusDays).toISOString();
}

interface ActorFilter {
  id: string;
  label: string;
}

export default function AuditLogPage() {
  const [data, setData] = useState<AuditEventPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [page, setPage] = useState(1);
  const [action, setAction] = useState("");
  const [targetType, setTargetType] = useState("");
  const [fromDay, setFromDay] = useState("");
  const [toDay, setToDay] = useState("");
  const [actor, setActor] = useState<ActorFilter | null>(null);
  const [revision, setRevision] = useState(0);

  const rangeInvalid = !!fromDay && !!toDay && fromDay > toDay;

  useEffect(() => {
    if (rangeInvalid) return;
    let active = true;
    setLoading(true);
    setLoadError("");
    adminService
      .listAuditEvents({
        action,
        targetType,
        actorId: actor?.id,
        from: dayStart(fromDay),
        to: dayStart(toDay, 1),
        page,
        pageSize: PAGE_SIZE,
      })
      .then((result) => {
        if (active) setData(result);
      })
      .catch((e: Error) => {
        if (!active) return;
        setData(null);
        setLoadError(e.message || "Không thể tải nhật ký hoạt động.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [action, targetType, actor, fromDay, toDay, page, revision, rangeInvalid]);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;
  const hasFilter = !!(action || targetType || fromDay || toDay || actor);

  // Đổi bộ lọc thì quay về trang 1.
  function filter<T>(set: (v: T) => void) {
    return (v: T) => {
      set(v);
      setPage(1);
    };
  }

  function clearFilters() {
    setAction("");
    setTargetType("");
    setFromDay("");
    setToDay("");
    setActor(null);
    setPage(1);
  }

  function actorCell(e: AuditEvent) {
    if (!e.actorId) return <span className="muted">Không xác định</span>;
    const label = e.actorEmail ?? e.actorId;
    return (
      <>
        <button
          type="button"
          className="audit-link"
          title="Chỉ xem thao tác của người này"
          onClick={() => filter(setActor)({ id: e.actorId!, label })}
        >
          {label}
        </button>
        {e.actorRole && (
          <div className="muted audit-sub">{ROLE_LABELS[e.actorRole]}</div>
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        crumbs={["Trang chủ", "Audit Log"]}
        title="Audit Log"
        subtitle="Truy vết các thao tác quan trọng của người dùng và hệ thống."
        code="NFR-07"
      />

      <div className="admin-toolbar">
        <select
          aria-label="Lọc theo thao tác"
          value={action}
          onChange={(e) => filter(setAction)(e.target.value)}
        >
          <option value="">Mọi thao tác</option>
          {Object.entries(ACTION_LABELS).map(([code, label]) => (
            <option key={code} value={code}>
              {label}
            </option>
          ))}
        </select>
        <select
          aria-label="Lọc theo đối tượng"
          value={targetType}
          onChange={(e) => filter(setTargetType)(e.target.value)}
        >
          <option value="">Mọi đối tượng</option>
          {Object.entries(TARGET_LABELS).map(([code, label]) => (
            <option key={code} value={code}>
              {label}
            </option>
          ))}
        </select>
        <label className="audit-date">
          Từ ngày
          <input
            type="date"
            value={fromDay}
            onChange={(e) => filter(setFromDay)(e.target.value)}
          />
        </label>
        <label className="audit-date">
          Đến ngày
          <input
            type="date"
            value={toDay}
            onChange={(e) => filter(setToDay)(e.target.value)}
          />
        </label>
        {hasFilter && (
          <button type="button" className="admin-btn" onClick={clearFilters}>
            Xóa bộ lọc
          </button>
        )}
      </div>

      {actor && (
        <p className="audit-chip">
          Đang xem thao tác của <strong>{actor.label}</strong>
          <button
            type="button"
            className="admin-btn"
            onClick={() => filter(setActor)(null)}
          >
            Bỏ lọc
          </button>
        </p>
      )}

      {rangeInvalid && (
        <div className="alert error" role="alert">
          "Từ ngày" phải trước hoặc bằng "Đến ngày".
        </div>
      )}

      <div className="card admin-card">
        {loading && !data ? (
          <p className="muted admin-state">Đang tải nhật ký hoạt động...</p>
        ) : loadError ? (
          <div className="admin-state">
            <p className="field-error">{loadError}</p>
            <button
              type="button"
              className="admin-btn"
              onClick={() => setRevision((n) => n + 1)}
            >
              Thử lại
            </button>
          </div>
        ) : data && data.items.length === 0 ? (
          <p className="muted admin-state">
            {hasFilter
              ? "Không có thao tác nào khớp với bộ lọc."
              : "Chưa có thao tác nào được ghi nhận."}
          </p>
        ) : (
          data && (
            <div className="admin-table-wrap">
              <table className="admin-table">
                <thead>
                  <tr>
                    <th>Thời gian</th>
                    <th>Thao tác</th>
                    <th>Người thực hiện</th>
                    <th>Đối tượng</th>
                    <th>Chi tiết</th>
                    <th>IP / Trace</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((e) => (
                    <tr key={e.id}>
                      <td className="audit-nowrap">{formatTime(e.occurredAt)}</td>
                      <td>
                        <span
                          className={`badge ${WARNING_ACTIONS.has(e.action) ? "st-failed" : "ok"}`}
                          title={e.action}
                        >
                          {ACTION_LABELS[e.action] ?? e.action}
                        </span>
                      </td>
                      <td>{actorCell(e)}</td>
                      <td>
                        {TARGET_LABELS[e.targetType] ?? e.targetType}
                        {e.targetId && (
                          <div className="muted audit-sub audit-mono" title={e.targetId}>
                            {e.targetId.slice(0, 8)}
                          </div>
                        )}
                      </td>
                      <td className="audit-detail">{e.detail ?? "—"}</td>
                      <td className="audit-mono audit-sub">
                        {e.ipAddress ?? "—"}
                        {e.traceId && (
                          <div className="muted" title={`traceId: ${e.traceId}`}>
                            {e.traceId.slice(0, 8)}
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}

        {data && data.total > 0 && (
          <div className="admin-pager">
            <span className="muted">
              {data.total} thao tác · Trang {page}/{totalPages}
            </span>
            <span>
              <button
                type="button"
                className="admin-btn"
                disabled={page <= 1 || loading}
                onClick={() => setPage((p) => p - 1)}
              >
                Trước
              </button>
              <button
                type="button"
                className="admin-btn"
                disabled={page >= totalPages || loading}
                onClick={() => setPage((p) => p + 1)}
              >
                Sau
              </button>
            </span>
          </div>
        )}
      </div>
    </>
  );
}
