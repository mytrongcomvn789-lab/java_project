import { useCallback, useEffect, useRef, useState } from "react";
import PageHeader from "../../components/ui/PageHeader";
import BarChart, {
  type BarChartDataPoint,
  type StackedBarDataPoint,
} from "../../components/charts/BarChart";
import { ApiRequestError } from "../../lib/apiClient";
import { stationService, type Station } from "../../lib/stationService";
import {
  reportService,
  type DateBasis,
  type DailyOrderStats,
  type OrderStatsSummary,
  type OrderStatusName,
  type ReportFilters,
  type StationLoadStat,
} from "../../lib/reportService";
import "./dashboard.css";

// Backend cố định múi giờ này (StatisticsPeriod.ZONE); web không dùng múi giờ trình duyệt.
const ZONE = "Asia/Ho_Chi_Minh";
const MAX_DAYS = 366;
const DAY_MS = 86_400_000;

/** Ngày hôm nay theo Asia/Ho_Chi_Minh, dạng yyyy-MM-dd. */
function todayInZone(): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: ZONE, year: "numeric", month: "2-digit", day: "2-digit",
  }).format(new Date());
}

/** Cộng/trừ ngày trên chuỗi yyyy-MM-dd (tính theo UTC nên không lệch múi giờ máy). */
function addDays(iso: string, delta: number): string {
  return new Date(Date.parse(`${iso}T00:00:00Z`) + delta * DAY_MS).toISOString().slice(0, 10);
}

/** Số ngày gồm cả hai đầu, giống backend. */
function inclusiveDays(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / DAY_MS) + 1;
}

function validateRange(from: string, to: string): string | null {
  if (!from || !to) return "Vui lòng chọn đủ Từ ngày và Đến ngày.";
  if (from > to) return "Từ ngày không được sau Đến ngày.";
  const days = inclusiveDays(from, to);
  if (days > MAX_DAYS) return `Khoảng thời gian tối đa ${MAX_DAYS} ngày (đang chọn ${days} ngày).`;
  return null;
}

const NO_DATA = "Chưa có dữ liệu";

function percent(value: number | null): string {
  return value == null ? NO_DATA : `${(value * 100).toFixed(2)}%`;
}

function formatDuration(seconds: number | null): string {
  if (seconds == null) return NO_DATA;
  const total = Math.round(seconds);
  if (total < 60) return `${total} giây`;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  return h > 0 ? `${h} giờ ${m} phút` : `${m} phút`;
}

const fmtTime = (iso: string) =>
  new Date(iso).toLocaleTimeString("vi-VN", { timeZone: ZONE, hour: "2-digit", minute: "2-digit" });
const fmtDateTime = (iso: string) =>
  new Date(iso).toLocaleString("vi-VN", { timeZone: ZONE });
const n = (v: number) => v.toLocaleString("vi-VN");

// Màu + nhãn: nhãn luôn đi kèm màu nên không chỉ phân biệt bằng màu.
const GROUPS = [
  { key: "pending", label: "Chờ xử lý", color: "#8a8f98" },
  { key: "inProgress", label: "Đang xử lý", color: "#2563a8" },
  { key: "delivered", label: "Đã giao", color: "#2e7d32" },
  { key: "failed", label: "Giao thất bại", color: "#c62828" },
  { key: "returning", label: "Đang hoàn", color: "#b26a00" },
  { key: "returned", label: "Đã hoàn", color: "#7b4a12" },
  { key: "cancelled", label: "Đã hủy", color: "#4a4f57" },
  { key: "rejected", label: "Bị từ chối", color: "#8e24aa" },
] as const;

const STATUS_LABELS: Record<OrderStatusName, string> = {
  PENDING: "Chờ xử lý", APPROVED: "Đã duyệt", REJECTED: "Bị từ chối", CANCELLED: "Đã hủy",
  SCHEDULED: "Đã lên lịch", PREPARING: "Đang chuẩn bị", IN_TRANSIT: "Đang giao",
  AWAITING_PICKUP: "Chờ nhận", DELIVERED: "Đã giao", FAILED: "Giao thất bại",
  RETURNING: "Đang hoàn", RETURNED: "Đã hoàn",
};
const STATUS_ORDER = Object.keys(STATUS_LABELS) as OrderStatusName[];

interface Applied extends ReportFilters { from: string; to: string; dateBasis: DateBasis }

interface Data {
  summary: OrderStatsSummary;
  daily: DailyOrderStats[];
  load: StationLoadStat[];
}

function errorText(e: unknown): string {
  if (e instanceof ApiRequestError && e.status === 403) return "Bạn không có quyền xem dashboard";
  return e instanceof Error ? e.message : "Không thể tải dữ liệu dashboard.";
}

export default function DashboardPage() {
  const initialTo = todayInZone();
  const [from, setFrom] = useState(addDays(initialTo, -29));
  const [to, setTo] = useState(initialTo);
  const [dateBasis, setDateBasis] = useState<DateBasis>("CREATED");
  const [stationId, setStationId] = useState("");
  const [stations, setStations] = useState<Station[]>([]);

  // data + applied luôn được set CÙNG LÚC từ một lần gọi => thẻ và biểu đồ không bao giờ lệch bộ lọc.
  const [data, setData] = useState<Data | null>(null);
  const [applied, setApplied] = useState<Applied | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [formError, setFormError] = useState("");
  const requestId = useRef(0);
  const lastTried = useRef<Applied | null>(null);

  useEffect(() => {
    stationService.list().then(setStations).catch(() => setStations([]));
  }, []);

  const load = useCallback(async (f: Applied) => {
    const invalid = validateRange(f.from, f.to);
    if (invalid) { setFormError(invalid); return; } // không gọi API
    setFormError("");
    lastTried.current = f;
    const id = ++requestId.current;
    setLoading(true); setError("");
    const params: ReportFilters = {
      from: f.from, to: f.to, dateBasis: f.dateBasis, stationId: f.stationId || undefined,
    };
    try {
      const [summary, daily, load] = await Promise.all([
        reportService.summary(params),
        reportService.daily(params),
        reportService.stationLoad(params.stationId),
      ]);
      if (id !== requestId.current) return; // phản hồi cũ, đã đổi bộ lọc
      setData({ summary, daily, load });
      setApplied(f);
    } catch (e) {
      if (id !== requestId.current) return;
      setData(null); setApplied(null);
      setError(errorText(e));
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load({ from, to, dateBasis, stationId });
    return () => { requestId.current++; }; // bỏ qua phản hồi sau khi rời trang
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const apply = () => void load({ from, to, dateBasis, stationId });

  function quick(nextFrom: string, nextTo: string) {
    setFrom(nextFrom); setTo(nextTo);
    void load({ from: nextFrom, to: nextTo, dateBasis, stationId });
  }
  const quickDays = (days: number) => { const t = todayInZone(); quick(addDays(t, -(days - 1)), t); };
  const thisMonth = () => { const t = todayInZone(); quick(`${t.slice(0, 8)}01`, t); };

  const summary = data?.summary;
  const empty = !!summary && summary.orders.total === 0;

  const cards = summary ? [
    { label: "Tổng đơn", value: n(summary.orders.total) },
    { label: "Đã giao", value: n(summary.orders.delivered) },
    { label: "Đang xử lý", value: n(summary.orders.inProgress) },
    { label: "Giao thất bại", value: n(summary.orders.failed) },
    {
      label: "Tỷ lệ giao thành công", value: percent(summary.rates.successRate),
      note: summary.rates.successRate == null ? "Mẫu = 0" : `Mẫu: ${n(summary.rates.deliverySample)} đơn đã có kết quả giao`,
    },
    {
      label: "Tỷ lệ hoàn", value: percent(summary.rates.returnRate),
      note: summary.rates.returnRate == null ? "Mẫu = 0" : `Mẫu: ${n(summary.rates.deliverySample)} đơn đã có kết quả giao`,
    },
    {
      label: "Tỷ lệ giao lại", value: percent(summary.attempts.retryRate),
      note: summary.attempts.retryRate == null
        ? "Mẫu = 0"
        : `${n(summary.attempts.ordersWithRetry)} / ${n(summary.attempts.ordersWithAttempt)} đơn có attempt`,
    },
    {
      label: "Thời gian giao trung bình", value: formatDuration(summary.deliveryTime.avgSeconds),
      note: summary.deliveryTime.avgSeconds == null
        ? `Mẫu = ${summary.deliveryTime.count}${summary.deliveryTime.excludedOrders > 0 ? ` · loại ${n(summary.deliveryTime.excludedOrders)} đơn thiếu mốc` : ""}`
        : `Mẫu: ${n(summary.deliveryTime.count)} đơn · loại ${n(summary.deliveryTime.excludedOrders)} đơn thiếu mốc`,
    },
  ] : [];

  // (a) Cột chồng theo ngày: đủ 8 nhóm để tổng cột = orders.total của ngày.
  const dailyData: StackedBarDataPoint[] = (data?.daily ?? []).map((d) => ({
    label: d.date,
    segments: GROUPS.map((g) => ({
      key: g.key, value: d.orders[g.key], color: g.color, displayLabel: g.label,
    })),
  }));

  // (b) Phân bố trạng thái từ summary.byStatus (đủ 12 trạng thái, kể cả 0).
  const statusData: BarChartDataPoint[] = summary
    ? STATUS_ORDER.map((s) => ({ label: STATUS_LABELS[s], value: summary.byStatus[s] ?? 0, color: "#2563a8" }))
    : [];

  // (c) Tải trạm: thanh đã đặt / sức chứa; processing và utilization nằm trong nhãn.
  const loadData: BarChartDataPoint[] = (data?.load ?? []).map((s) => ({
    label: s.name,
    value: s.booked,
    max: s.capacity,
    color: s.utilization != null && s.utilization >= 0.9 ? "#c62828" : "#2e7d32",
    text: `${s.booked}/${s.capacity} chỗ · xử lý ${s.processing} kiện · sử dụng ${s.utilization == null ? NO_DATA : `${(s.utilization * 100).toFixed(1)}%`}`,
  }));
  const slot = data?.load[0];

  return (
    <div className="dashboard">
      <PageHeader
        title="Dashboard"
        subtitle="Tổng quan đơn hàng và tải trạm; số liệu do backend tổng hợp (API 5.1)."
        crumbs={["Trang chủ", "Dashboard"]}
        code="FR-09"
      />

      <section className="card dashboard-filters">
        <div className="dashboard-filter-row">
          <label>Từ ngày
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label>Đến ngày
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </label>
          <label>Mốc ngày
            <select value={dateBasis} onChange={(e) => setDateBasis(e.target.value as DateBasis)}>
              <option value="CREATED">Ngày tạo</option>
              <option value="COMPLETED">Ngày hoàn tất</option>
            </select>
          </label>
          <label>Trạm
            <select value={stationId} onChange={(e) => setStationId(e.target.value)}>
              <option value="">Toàn phạm vi được phân công</option>
              {stations.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
        </div>
        <div className="dashboard-filter-actions">
          <div className="dashboard-quick-filters">
            <button type="button" className="quick-filter" onClick={() => quickDays(7)} disabled={loading}>7 ngày</button>
            <button type="button" className="quick-filter" onClick={() => quickDays(30)} disabled={loading}>30 ngày</button>
            <button type="button" className="quick-filter" onClick={thisMonth} disabled={loading}>Tháng này</button>
          </div>
          <button type="button" onClick={apply} disabled={loading}>{loading ? "Đang tải…" : "Áp dụng"}</button>
        </div>
        <p className="muted dashboard-tz">Ngày tính theo múi giờ {ZONE}, tối đa {MAX_DAYS} ngày.</p>
      </section>

      {formError && <div className="alert error" role="alert">{formError}</div>}
      {error && (
        <div className="alert error" role="alert">
          {error}
          <button type="button" className="dashboard-retry" onClick={() => lastTried.current && void load(lastTried.current)}>
            Thử lại
          </button>
        </div>
      )}

      {loading && !data && <div className="dashboard-loading" role="status">Đang tải dữ liệu…</div>}

      {data && summary && applied && (
        <div className={loading ? "dashboard-body is-loading" : "dashboard-body"} aria-busy={loading}>
          <p className="muted dashboard-applied">
            Đang hiển thị: {applied.from} → {applied.to} · Mốc {applied.dateBasis === "CREATED" ? "ngày tạo" : "ngày hoàn tất"} ·{" "}
            {applied.stationId ? (stations.find((s) => s.id === applied.stationId)?.name ?? "Một trạm") : "Toàn phạm vi được phân công"}
          </p>

          {empty && (
            <div className="dashboard-empty">
              <strong>Không có đơn trong khoảng đã chọn</strong>
              <span className="muted">Thử đổi khoảng ngày, mốc ngày hoặc trạm.</span>
            </div>
          )}

          <section className="dashboard-stats">
            {cards.map((c) => (
              <div key={c.label} className="card stat-card">
                <span className="stat-label">{c.label}</span>
                <strong className="stat-value">{c.value}</strong>
                {c.note && <span className="stat-note">{c.note}</span>}
              </div>
            ))}
          </section>

          {!empty && (
            <>
              <section className="card">
                <BarChart
                  type="stacked"
                  title="Số đơn theo ngày"
                  unit="đơn"
                  data={dailyData}
                  ariaLabel={`Biểu đồ cột chồng số đơn theo ngày từ ${applied.from} đến ${applied.to}, tổng ${n(summary.orders.total)} đơn`}
                  height={340}
                />
                <p className="muted chart-note">Trục dọc: số đơn. Trục ngang: ngày (múi giờ {ZONE}). Ngày không có đơn hiển thị 0.</p>
              </section>

              <section className="card">
                <BarChart
                  type="vertical"
                  title="Phân bố theo trạng thái"
                  unit="đơn"
                  data={statusData}
                  ariaLabel={`Biểu đồ cột số đơn theo 12 trạng thái, tổng ${n(summary.orders.total)} đơn`}
                  height={320}
                />
                <p className="muted chart-note">Trục dọc: số đơn. Mỗi cột là một trạng thái hiện tại của đơn.</p>
              </section>
            </>
          )}

          <section className="card">
            <BarChart
              type="horizontal"
              title="Tải trạm trong một khung giờ"
              unit="chỗ"
              data={loadData}
              ariaLabel="Biểu đồ thanh ngang số chỗ đã đặt trên sức chứa của từng trạm"
            />
            {slot && (
              <p className="chart-note">
                Khung giờ: <strong>{fmtTime(slot.slotStart)}–{fmtTime(slot.slotEnd)}</strong> ({fmtDateTime(slot.slotStart)}, giờ {ZONE}).
              </p>
            )}
            <div className="dashboard-note">
              <p>Tải trạm tính trong <strong>một khung giờ</strong> (mặc định khung giờ hiện tại), <strong>không</strong> theo khoảng ngày đang lọc.</p>
              <p>"Xử lý" (processing) theo định nghĩa 5.1 = PREPARING ở trạm gửi + AWAITING_PICKUP ở trạm nhận. Số này có thể khác số kiện ở màn "Giám sát trạm" (4.6) vì hai nơi dùng công thức khác nhau; dashboard chỉ hiển thị số của API 5.1.</p>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
