import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import { stationService, type Station } from "../../lib/stationService";
import { reportService, type DailyOrderStats, type OrderStatsSummary, type ReportFilters } from "../../lib/reportService";
import "./reports.css";

function todayRange() {
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - 29);
  const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  return { from: iso(from), to: iso(to) };
}

function percent(value: number | null) {
  return value == null ? "Chưa có dữ liệu" : `${(value * 100).toFixed(2)}%`;
}

export default function ReportsPage() {
  const defaults = todayRange();
  const [from, setFrom] = useState(defaults.from);
  const [to, setTo] = useState(defaults.to);
  const [dateBasis, setDateBasis] = useState<"CREATED" | "COMPLETED">("CREATED");
  const [stationId, setStationId] = useState("");
  const [stations, setStations] = useState<Station[]>([]);
  const [summary, setSummary] = useState<OrderStatsSummary | null>(null);
  const [daily, setDaily] = useState<DailyOrderStats[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    stationService.list().then(setStations).catch(() => setStations([]));
  }, []);

  async function load() {
    setLoading(true); setError("");
    const filters: ReportFilters = { from, to, dateBasis, stationId: stationId || undefined };
    try {
      const [nextSummary, nextDaily] = await Promise.all([reportService.summary(filters), reportService.daily(filters)]);
      setSummary(nextSummary); setDaily(nextDaily);
    } catch (e) {
      setSummary(null); setDaily([]);
      setError(e instanceof Error ? e.message : "Không thể tải báo cáo.");
    } finally { setLoading(false); }
  }

  useEffect(() => { void load(); }, []);

  async function download(format: "xlsx" | "pdf") {
    setError("");
    const filters: ReportFilters = { from, to, dateBasis, stationId: stationId || undefined };
    try { await reportService.exportFile(format, filters); }
    catch (e) { setError(e instanceof Error ? e.message : `Không thể tải báo cáo ${format.toUpperCase()}.`); }
  }

  const cards = summary ? [
    ["Tổng đơn", summary.orders.total.toLocaleString("vi-VN")],
    ["Đã giao", summary.orders.delivered.toLocaleString("vi-VN")],
    ["Đang xử lý", summary.orders.inProgress.toLocaleString("vi-VN")],
    ["Giao thất bại", summary.orders.failed.toLocaleString("vi-VN")],
  ] : [];

  return (
    <div>
      <PageHeader title="Thống kê & báo cáo" subtitle="Báo cáo theo bộ lọc chung của thống kê; dữ liệu do backend tổng hợp." crumbs={["Trang chủ", "Thống kê & báo cáo"]} code="FR-09" />
      <p><Link to="/ai-analytics">Phân tích vận hành bằng AI →</Link></p>
      <section className="card report-filters">
        <div className="report-filter-grid">
          <label>Từ ngày<input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></label>
          <label>Đến ngày<input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></label>
          <label>Mốc ngày<select value={dateBasis} onChange={(e) => setDateBasis(e.target.value as "CREATED" | "COMPLETED")}><option value="CREATED">Ngày tạo</option><option value="COMPLETED">Ngày hoàn tất</option></select></label>
          <label>Trạm<select value={stationId} onChange={(e) => setStationId(e.target.value)}><option value="">Toàn phạm vi được phân công</option>{stations.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
        </div>
        <div className="report-actions"><button type="button" className="secondary" onClick={() => void load()} disabled={loading}>{loading ? "Đang tải…" : "Xem báo cáo"}</button><button type="button" onClick={() => void download("xlsx")}>Xuất Excel</button><button type="button" onClick={() => void download("pdf")}>Xuất PDF</button></div>
      </section>
      {error && <div className="alert error">{error}</div>}
      {summary && <>
        <section className="report-summary">{cards.map(([label, value]) => <div className="card report-stat" key={label}><span>{label}</span><strong>{value}</strong></div>)}</section>
        <section className="card report-table-card">
          <div className="report-meta"><strong>Múi giờ:</strong> {summary.timezone} · <strong>Mốc:</strong> {summary.dateBasis === "CREATED" ? "Ngày tạo" : "Ngày hoàn tất"} · <strong>Tỷ lệ giao:</strong> {percent(summary.rates.successRate)} · <strong>Tỷ lệ hoàn:</strong> {percent(summary.rates.returnRate)}</div>
          <div className="table-wrap"><table className="report-table"><thead><tr><th>Ngày</th><th>Tổng</th><th>Chờ</th><th>Đang xử lý</th><th>Đã giao</th><th>Thất bại</th><th>Hoàn</th><th>Hủy</th></tr></thead><tbody>{daily.map((d) => <tr key={d.date}><td>{d.date}</td><td>{d.orders.total}</td><td>{d.orders.pending}</td><td>{d.orders.inProgress}</td><td>{d.orders.delivered}</td><td>{d.orders.failed}</td><td>{d.orders.returning + d.orders.returned}</td><td>{d.orders.cancelled}</td></tr>)}</tbody></table></div>
        </section>
      </>}
    </div>
  );
}
