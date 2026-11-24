import { Check, Package, TriangleAlert, Truck } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import StatusBadge from "../../components/ui/StatusBadge";
import { useAuth } from "../auth/AuthContext";
import { checkHealth } from "../../lib/healthService";
import {
  HOME_STAT_ROLES,
  homeStatsService,
  type HomeStats,
} from "../../lib/homeStatsService";
import { getMenuForRole } from "../../lib/navigation";
import { ROLE_DESCRIPTIONS, ROLE_LABELS } from "../../lib/roles";
import { ACTOR_LABELS, DELIVERY_FLOW } from "../../lib/workflow";
import type { RoleName } from "../../types/api";

type Status = { state: "loading" | "ok" | "error"; message: string };

const STATS: { label: string; key: keyof HomeStats; icon: typeof Package }[] = [
  { label: "Tổng số đơn", key: "total", icon: Package },
  { label: "Đang xử lý", key: "inProgress", icon: Truck },
  { label: "Đã giao", key: "delivered", icon: Check },
  { label: "Giao thất bại", key: "failed", icon: TriangleAlert },
];

// Phạm vi số liệu theo vai trò (backend tự lọc, web chỉ hiển thị).
const STATS_NOTES: Partial<Record<RoleName, string>> = {
  CUSTOMER: "Số liệu các đơn giao hàng của bạn.",
  DISPATCHER: "Số liệu các đơn thuộc trạm bạn được phân công.",
  STATION_OPERATOR: "Số liệu các đơn thuộc trạm bạn được phân công.",
  LOGISTICS_MANAGER:
    "Số liệu 30 ngày gần nhất theo ngày tạo đơn, cùng nguồn với Dashboard.",
};

// Ghi chú cho vai trò không trực tiếp xử lý bước nào trong quy trình giao hàng
const FLOW_NOTES: Partial<Record<RoleName, string>> = {
  LOGISTICS_MANAGER:
    "Bạn không xử lý từng đơn mà giám sát toàn bộ quy trình qua dashboard, báo cáo, hiệu suất các trạm và Audit Log.",
  SYSTEM_ADMIN:
    "Bạn không xử lý đơn giao hàng mà quản trị tài khoản, vai trò, cấu hình hệ thống và theo dõi Audit Log.",
};

export default function HomePage() {
  const { user, isDemo } = useAuth();
  const [api, setApi] = useState<Status>({ state: "loading", message: "" });
  const [stats, setStats] = useState<HomeStats | null>(null);
  const [statsError, setStatsError] = useState(false);
  const role = user?.role;
  const showStats = !!role && HOME_STAT_ROLES.includes(role);

  useEffect(() => {
    if (isDemo) return; // chế độ demo không gọi backend
    checkHealth()
      .then(() => setApi({ state: "ok", message: "Kết nối API thành công" }))
      .catch((e: Error) => setApi({ state: "error", message: e.message }));
  }, [isDemo]);

  useEffect(() => {
    if (isDemo || !role || !HOME_STAT_ROLES.includes(role)) return;
    let active = true;
    setStats(null);
    setStatsError(false);
    homeStatsService
      .load(role)
      .then((value) => {
        if (active) setStats(value);
      })
      .catch(() => {
        if (active) setStatsError(true);
      });
    return () => {
      active = false;
    };
  }, [role, isDemo]);

  if (!user) return null;

  const shortcuts = getMenuForRole(user.role).filter(
    (i) => i.to !== "/" && i.to !== "/profile",
  );
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
        <div className="hero-text">
          <span className="eyebrow">
            {ROLE_LABELS[user.role].toUpperCase()}
          </span>
          <h3>Xin chào, {user.fullName}</h3>
          <p className="muted" style={{ margin: 0 }}>
            {ROLE_DESCRIPTIONS[user.role]}
          </p>
        </div>
        <img className="hero-img" src="/images/hero-drone.svg" alt="" />
      </div>

      {showStats && (
        <>
          <div className="stats">
            {STATS.map(({ label, key, icon: Icon }) => (
              <div className="card stat" key={label}>
                <span className="stat-icon">
                  <Icon size={20} strokeWidth={2} />
                </span>
                <span className="muted">{label}</span>
                <strong>{stats ? stats[key] : "—"}</strong>
              </div>
            ))}
          </div>
          <p className="muted" style={{ marginTop: -8, fontSize: 13 }}>
            {isDemo
              ? "Đăng nhập bằng tài khoản thật để xem số liệu."
              : statsError
                ? "Không tải được số liệu. Hãy tải lại trang."
                : STATS_NOTES[user.role]}
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
            <span className="feature-icon">
              <i.icon size={22} strokeWidth={1.8} />
            </span>
            <span className="muted" style={{ fontSize: 11 }}>
              {i.code}
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
