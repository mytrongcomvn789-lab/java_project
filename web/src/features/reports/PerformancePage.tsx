import { useCallback, useEffect, useRef, useState } from "react";
import PageHeader from "../../components/ui/PageHeader";
import { reportService, type StationLoadStat } from "../../lib/reportService";
import { stationService, type StationMonitoring, type StationStatus } from "../../lib/stationService";
import "./performance.css";

const ZONE = "Asia/Ho_Chi_Minh";
const STATUS: Record<StationStatus, string> = {
  ACTIVE: "Đang hoạt động",
  MAINTENANCE: "Bảo trì",
  INACTIVE: "Ngừng hoạt động",
};

type Row = StationLoadStat & { monitoring: StationMonitoring | undefined };

function number(value: number) { return value.toLocaleString("vi-VN"); }
function time(value: string) {
  return new Date(value).toLocaleString("vi-VN", { timeZone: ZONE, dateStyle: "short", timeStyle: "short" });
}

export default function PerformancePage() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [status, setStatus] = useState<StationStatus | "">("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError("");
    try {
      const [loads, monitoring] = await Promise.all([
        reportService.stationLoad(),
        stationService.monitoring(),
      ]);
      if (id !== requestId.current) return;
      const byId = new Map(monitoring.map((item) => [item.id, item]));
      setRows(loads.map((item) => ({ ...item, monitoring: byId.get(item.stationId) })));
    } catch (cause) {
      if (id !== requestId.current) return;
      setRows(null);
      setError(cause instanceof Error ? cause.message : "Không thể tải hiệu suất trạm.");
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return () => { requestId.current += 1; };
  }, [load]);

  const visible = (rows ?? []).filter((row) => !status || row.status === status);
  const capacity = visible.reduce((sum, row) => sum + row.capacity, 0);
  const booked = visible.reduce((sum, row) => sum + row.booked, 0);
  const processing = visible.reduce((sum, row) => sum + row.processing, 0);
  const utilization = capacity > 0 ? `${((booked / capacity) * 100).toFixed(1)}%` : "Chưa có dữ liệu";

  return (
    <div className="performance-page">
      <PageHeader title="Hiệu suất hoạt động" subtitle="Tải và số kiện đang xử lý tại từng trạm trong khung giờ hiện tại." crumbs={["Trang chủ", "Hiệu suất hoạt động"]} code="FR-09" />
      <section className="card performance-toolbar">
        <label>Trạng thái trạm
          <select value={status} onChange={(event) => setStatus(event.target.value as StationStatus | "")}>
            <option value="">Tất cả</option>
            {Object.entries(STATUS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
          </select>
        </label>
        <button type="button" onClick={() => void load()} disabled={loading}>{loading ? "Đang tải…" : "Tải lại"}</button>
      </section>
      {error && <div className="alert error" role="alert">{error} <button type="button" onClick={() => void load()}>Thử lại</button></div>}
      {loading && !rows && <p role="status">Đang tải hiệu suất trạm…</p>}
      {rows && <>
        <p className="performance-note">Số liệu tải trạm lấy từ lịch đặt chỗ và trạng thái đơn hàng trong hệ thống. Đây là ảnh chụp của <strong>một khung giờ</strong>, không phải tỷ lệ hoạt động cả ngày. Trạng thái kết nối thiết bị chưa được dùng để tính các chỉ số này.</p>
        <section className="performance-stats" aria-label="Tổng quan hiệu suất trạm">
          <div className="card"><span>Trạm hiển thị</span><strong>{number(visible.length)}</strong></div>
          <div className="card"><span>Chỗ đã đặt / tổng sức chứa</span><strong>{number(booked)} / {number(capacity)}</strong></div>
          <div className="card"><span>Tỷ lệ sử dụng chỗ</span><strong>{utilization}</strong></div>
          <div className="card"><span>Kiện đang xử lý</span><strong>{number(processing)}</strong></div>
        </section>
        {visible.length === 0 ? <div className="card performance-empty">Không có trạm phù hợp với bộ lọc.</div> :
          <section className="card performance-table-wrap">
            <h2>So sánh các trạm</h2>
            <p className="performance-note">Khung giờ: {time(visible[0].slotStart)} – {time(visible[0].slotEnd)} ({ZONE})</p>
            <div className="performance-scroll"><table>
              <thead><tr><th>Trạm</th><th>Trạng thái</th><th>Đã đặt / sức chứa</th><th>Còn trống</th><th>Đang xử lý</th><th>Tỷ lệ sử dụng</th></tr></thead>
              <tbody>{visible.map((row) => <tr key={row.stationId}>
                <th scope="row">{row.name}<small>{row.monitoring?.address || "Chưa có địa chỉ"}</small></th>
                <td>{STATUS[row.status]}</td>
                <td>{number(row.booked)} / {number(row.capacity)}</td>
                <td>{number(row.free)}</td>
                <td>{number(row.processing)}</td>
                <td><div className="performance-meter"><span style={{ width: `${Math.min(100, Math.max(0, (row.utilization ?? 0) * 100))}%` }} /></div><strong>{row.utilization == null ? "Chưa có dữ liệu" : `${(row.utilization * 100).toFixed(1)}%`}</strong></td>
              </tr>)}</tbody>
            </table></div>
          </section>}
      </>}
    </div>
  );
}
