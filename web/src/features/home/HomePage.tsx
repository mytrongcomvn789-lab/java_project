import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import StatusBadge from "../../components/ui/StatusBadge";
import { useAuth } from "../auth/AuthContext";
import { checkHealth } from "../../lib/healthService";
import { getMenuForRole } from "../../lib/navigation";
import { ROLE_DESCRIPTIONS, ROLE_LABELS } from "../../lib/roles";
import { ACTOR_LABELS, DELIVERY_FLOW } from "../../lib/workflow";
import type { RoleName } from "../../types/api";

type Status = { state: "loading" | "ok" | "error"; message: string };

// Vai trò xem số liệu tổng quan (FR-09): khách hàng xem đơn của mình, điều phối & hậu cần xem toàn hệ thống.
const STAT_ROLES: RoleName[] = ["CUSTOMER", "DISPATCHER", "LOGISTICS_MANAGER"];

const STATS = ["Tổng số đơn", "Đang xử lý", "Đã giao", "Giao thất bại"];

// Ghi chú cho vai trò không trực tiếp xử lý bước nào trong quy trình giao hàng
const FLOW_NOTES: Partial<Record<RoleName, string>> = {
  LOGISTICS_MANAGER:
    "Bạn không xử lý từng đơn mà giám sát toàn bộ quy trình qua dashboard, báo cáo và hiệu suất các trạm.",
  SYSTEM_ADMIN:
    "Bạn không xử lý đơn giao hàng mà quản trị tài khoản, vai trò, cấu hình hệ thống và theo dõi Audit Log.",
};

export default function HomePage() {
  const { user, isDemo } = useAuth();
  const [api, setApi] = useState<Status>({ state: "loading", message: "" });

  useEffect(() => {
    if (isDemo) return; // chế độ demo không gọi backend
    checkHealth()
      .then(() => setApi({ state: "ok", message: "Kết nối API thành công" }))
      .catch((e: Error) => setApi({ state: "error", message: e.message }));
  }, [isDemo]);

  if (!user) return null;

  const shortcuts = getMenuForRole(user.role).filter(
    (i) => i.to !== "/" && i.to !== "/profile",
  );
  const showStats = STAT_ROLES.includes(user.role);
  const flowNote = FLOW_NOTES[user.role];

  return (
    <>
      <PageHeader
        crumbs={["Trang chủ"]}
        title="Trang chủ"
        subtitle="Tổng quan và lối tắt đến các chức năng của bạn."
        code={ROLE_LABELS[user.role]}
      />

      <div className="hero">
        <span className="eyebrow">{ROLE_LABELS[user.role].toUpperCase()}</span>
        <h3>Xin chào, {user.fullName}</h3>
        <p className="muted" style={{ margin: 0 }}>
          {ROLE_DESCRIPTIONS[user.role]}
        </p>
      </div>

      {showStats && (
        <>
          <div className="stats">
            {STATS.map((label) => (
              <div className="card stat" key={label}>
                <span className="muted">{label}</span>
                <strong>—</strong>
              </div>
            ))}
          </div>
          <p className="muted" style={{ marginTop: -8, fontSize: 13 }}>
            {user.role === "CUSTOMER"
              ? "Số liệu đơn hàng của bạn sẽ hiển thị khi có API đơn giao hàng."
              : "Số liệu dashboard sẽ hiển thị khi có API thống kê."}
          </p>
        </>
      )}

      <h3 className="section-title">Chức năng của bạn</h3>
      <div className="feature-grid">
        {shortcuts.map((i) => (
          <Link
            className="card feature-card"
            to={i.to}
            key={`${i.to}-${i.label}`}
          >
            <span className="muted" style={{ fontSize: 11 }}>
              {i.icon} {i.code}
            </span>
            <h4>{i.label}</h4>
            <p className="muted">{i.summary}</p>
          </Link>
        ))}
      </div>

      <h3 className="section-title">Quy trình giao hàng</h3>
      {flowNote && (
        <p className="muted" style={{ marginTop: -8 }}>
          {flowNote}
        </p>
      )}
      <ol className="stepper">
        {DELIVERY_FLOW.map((s, idx) => {
          const mine = s.actors.includes(user.role);
          return (
            <li className={`step ${mine ? "mine" : ""}`} key={s.title}>
              <span className="step-no">{idx + 1}</span>
              <div>
                <strong>
                  {s.title}
                  {mine && <span className="tag mine">Bạn phụ trách</span>}
                </strong>
                <small>
                  {s.actors.map((a) => ACTOR_LABELS[a]).join(" · ")} —{" "}
                  {s.detail}
                </small>
              </div>
              <StatusBadge status={s.status} />
            </li>
          );
        })}
      </ol>

      {!isDemo && (
        <div className="card api-status" style={{ marginTop: 24 }}>
          Trạng thái API:{" "}
          <span className={`badge ${api.state}`}>
            {api.state === "loading" ? "Đang kiểm tra..." : api.message}
          </span>
        </div>
      )}
    </>
  );
}
