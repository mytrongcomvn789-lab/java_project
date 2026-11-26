import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import AiSourceBadge from "../../components/ui/AiSourceBadge";
import { aiService, type AnalyticsRequest, type AnalyticsResult } from "../../lib/aiService";
import type { DateBasis } from "../../lib/reportService";
import { stationService, type Station } from "../../lib/stationService";
import { useAiTask } from "../../lib/useAiTask";
import "./reports.css";
import "./analytics.css";

const MAX_RANGE_DAYS = 366;

const WARNING_LABELS: Record<string, string> = {
  CURRENT_STATUS_NOT_HISTORICAL_SNAPSHOT: "Các nhóm trạng thái là trạng thái hiện tại của tập đơn, không phải ảnh chụp trạng thái trong quá khứ.",
  STATION_LOAD_IS_CURRENT_SLOT_NOT_PERIOD_TOTAL: "Tải trạm là của khung giờ hiện tại, không phải tổng cả kỳ.",
  READ_COMMITTED_NOT_FROZEN_SNAPSHOT: "Dữ liệu được đọc ở nhiều truy vấn, có thể thay đổi nhẹ giữa các truy vấn khi đang có đơn mới.",
  NO_ORDERS_IN_SCOPE: "Không có đơn nào trong phạm vi đã chọn.",
  LOW_DELIVERY_SAMPLE: "Số đơn đã có kết quả giao còn ít nên các tỷ lệ chưa đáng tin.",
  NO_DELIVERY_DURATION_SAMPLE: "Chưa có đơn nào đủ mốc thời gian để tính thời gian giao.",
  MISSING_DELIVERY_TIMESTAMPS: "Một số đơn đã giao thiếu mốc lịch sử nên bị loại khỏi thời gian giao.",
  NO_VISIBLE_STATIONS: "Không có trạm nào trong phạm vi được phân công cho bạn.",
  UNDEFINED_STATION_UTILIZATION: "Có trạm chưa xác định được mức sử dụng (sức chứa không hợp lệ).",
  TREND_REQUIRES_TWO_DAYS: "Cần khoảng ít nhất 2 ngày để so sánh xu hướng.",
  TREND_ZERO_BASELINE: "Cửa sổ đầu không có đơn nên không tính được tỷ lệ thay đổi.",
};

const isoDay = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const defaultRange = () => {
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - 29);
  return { from: isoDay(from), to: isoDay(to) };
};
const percent = (v: number | null | undefined) =>
  v == null ? "Chưa có dữ liệu" : `${(v * 100).toFixed(2)}%`;
const minutes = (sec: number | null) => (sec == null ? "—" : `${(sec / 60).toFixed(1)} phút`);
const when = (v: string) => new Date(v).toLocaleString("vi-VN");

function validate(from: string, to: string): string {
  if (!from || !to) return "Hãy chọn đủ ngày bắt đầu và kết thúc.";
  if (from > to) return "Ngày bắt đầu không được sau ngày kết thúc.";
  const days = (Date.parse(to) - Date.parse(from)) / 86_400_000 + 1;
  if (days > MAX_RANGE_DAYS) return `Khoảng ngày tối đa ${MAX_RANGE_DAYS} ngày.`;
  return "";
}

function Result({ data }: { data: AnalyticsResult }) {
  const { report, analysis } = data;
  const s = report.summary;
  const excluded = s.deliveryTime.excludedOrders;
  return (
    <>
      <section className="card analytics-block">
        <h3>Số liệu báo cáo</h3>
        <p className="muted">
          Số liệu do hệ thống tổng hợp, AI không tạo hay sửa các con số này. Dữ liệu lấy lúc{" "}
          <strong>{when(report.collectedAt)}</strong> · {s.from} đến {s.to} · múi giờ {s.timezone} · mốc{" "}
          {s.dateBasis === "CREATED" ? "ngày tạo" : "ngày hoàn tất"}.
        </p>
        <div className="report-summary">
          {[
            ["Tổng đơn", s.orders.total],
            ["Đã giao", s.orders.delivered],
            ["Đang xử lý", s.orders.inProgress],
            ["Giao thất bại", s.orders.failed],
          ].map(([label, value]) => (
            <div className="card report-stat" key={label as string}>
              <span>{label}</span>
              <strong>{(value as number).toLocaleString("vi-VN")}</strong>
            </div>
          ))}
        </div>
        <ul className="analytics-facts">
          <li>Tỷ lệ giao thành công: {percent(s.rates.successRate)} (mẫu {s.rates.deliverySample} đơn)</li>
          <li>
            Tỷ lệ giao thất bại: {percent(analysis.failureRate)} (mẫu {analysis.failureSample} đơn). Không bằng 1 trừ
            tỷ lệ thành công vì còn đơn hoàn.
          </li>
          <li>
            Thời gian giao trung bình: {minutes(s.deliveryTime.avgSeconds)} (tính trên {s.deliveryTime.count} đơn
            {excluded > 0 ? `, loại ${excluded} đơn thiếu mốc thời gian` : ""})
          </li>
          {analysis.trend && (
            <li>
              Xu hướng: {analysis.trend.firstOrders} đơn ({analysis.trend.firstFrom} – {analysis.trend.firstTo}) so với{" "}
              {analysis.trend.lastOrders} đơn ({analysis.trend.lastFrom} – {analysis.trend.lastTo}); chênh{" "}
              {analysis.trend.delta >= 0 ? "+" : ""}
              {analysis.trend.delta}
              {analysis.trend.changeRate != null && ` (${percent(analysis.trend.changeRate)})`}. Đây là biến động
              lượng đơn, không dự báo và không chứng minh nguyên nhân.
            </li>
          )}
        </ul>
      </section>

      <section className="card analytics-block">
        <h3>Tải trạm: khung giờ hiện tại</h3>
        <p className="muted">
          Tại thời điểm {when(report.collectedAt)}, không phải tải cả kỳ. Tổng lượt chỗ có thể đếm một đơn ở hai trạm.
        </p>
        {report.stationLoad.length === 0 ? (
          <p>Không có trạm trong phạm vi.</p>
        ) : (
          <div className="table-wrap">
            <table className="report-table">
              <thead>
                <tr>
                  <th>Trạm</th>
                  <th>Khung giờ</th>
                  <th>Sức chứa</th>
                  <th>Đã đặt</th>
                  <th>Còn trống</th>
                  <th>Mức sử dụng</th>
                </tr>
              </thead>
              <tbody>
                {report.stationLoad.map((st) => (
                  <tr key={st.stationId}>
                    <td>{st.name}</td>
                    <td>
                      {new Date(st.slotStart).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" })} –{" "}
                      {new Date(st.slotEnd).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" })}
                    </td>
                    <td>{st.capacity}</td>
                    <td>{st.booked}</td>
                    <td>{st.free}</td>
                    <td>{percent(st.utilization)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card analytics-block">
        <h3>Nhận định và khuyến nghị</h3>
        {data.insights.length === 0 ? (
          <p className="muted">Chưa có nhận định.</p>
        ) : (
          <ul>
            {data.insights.map((t, i) => (
              <li key={data.insightIds[i] ?? i}>{t}</li>
            ))}
          </ul>
        )}
        {data.recommendations.length > 0 && (
          <>
            <h4>Khuyến nghị (chỉ để tham khảo, hệ thống không tự thực hiện)</h4>
            <ul>
              {data.recommendations.map((t, i) => (
                <li key={data.recommendationIds[i] ?? i}>{t}</li>
              ))}
            </ul>
          </>
        )}
        <p className="muted">
          AI chỉ chọn và sắp xếp các nhận định có căn cứ do hệ thống tạo (quy tắc {data.rulesVersion}); các ngưỡng cảnh
          báo là quy tắc của phiên bản này, chưa phải cam kết dịch vụ được duyệt.
        </p>
      </section>

      <section className="card analytics-block">
        <h3>Toàn bộ căn cứ và cảnh báo dữ liệu</h3>
        <p className="muted">Hiển thị đầy đủ, kể cả những mục AI không chọn.</p>
        {analysis.warnings.length > 0 && (
          <ul className="analytics-warnings">
            {analysis.warnings.map((w) => (
              <li key={w}>{WARNING_LABELS[w] ?? w}</li>
            ))}
          </ul>
        )}
        <ul>
          {analysis.evidence.map((e) => (
            <li key={e.id}>
              {e.text}
              {e.recommendation && <small className="muted"> · Gợi ý: {e.recommendation}</small>}
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}

/** Phân tích vận hành bằng AI (7.5/7.6), chỉ LOGISTICS_MANAGER. Chạy khi bấm nút vì có thể tốn hạn mức AI. */
export default function OperationsAnalyticsPage() {
  const defaults = defaultRange();
  const [from, setFrom] = useState(defaults.from);
  const [to, setTo] = useState(defaults.to);
  const [dateBasis, setDateBasis] = useState<DateBasis>("CREATED");
  const [stationId, setStationId] = useState("");
  const [stations, setStations] = useState<Station[]>([]);
  const [formError, setFormError] = useState("");
  const requestRef = useRef<AnalyticsRequest>({});

  const { state, start, canRetry } = useAiTask<AnalyticsResult>(
    (signal) => aiService.analytics(requestRef.current, signal),
    "analytics",
    false,
  );

  useEffect(() => {
    stationService.list().then(setStations).catch(() => setStations([]));
  }, []);

  function run() {
    const problem = validate(from, to);
    setFormError(problem);
    if (problem) return;
    requestRef.current = { from, to, dateBasis, stationId: stationId || undefined };
    start();
  }

  const data = state.status === "success" ? state.envelope.result : null;

  return (
    <div>
      <PageHeader
        title="Phân tích vận hành bằng AI"
        subtitle="Số liệu lấy từ thống kê chính thức; AI chỉ chọn nhận định có căn cứ, không tự tạo số."
        crumbs={["Trang chủ", "Phân tích vận hành (AI)"]}
        code="FR-09"
      />
      <p>
        <Link to="/reports">← Về thống kê & báo cáo</Link>
      </p>
      <section className="card report-filters">
        <div className="report-filter-grid">
          <label>
            Từ ngày
            <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label>
            Đến ngày
            <input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} />
          </label>
          <label>
            Mốc ngày
            <select value={dateBasis} onChange={(e) => setDateBasis(e.target.value as DateBasis)}>
              <option value="CREATED">Ngày tạo</option>
              <option value="COMPLETED">Ngày hoàn tất</option>
            </select>
          </label>
          <label>
            Trạm
            <select value={stationId} onChange={(e) => setStationId(e.target.value)}>
              <option value="">Toàn phạm vi được phân công</option>
              {stations.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
        </div>
        {formError && (
          <div className="alert error" role="alert">
            {formError}
          </div>
        )}
        <div className="report-actions">
          <button type="button" className="secondary" onClick={run} disabled={state.status === "loading"}>
            {state.status === "loading" ? "Đang phân tích…" : "Phân tích"}
          </button>
        </div>
      </section>

      <div aria-live="polite">
        {state.status === "error" && (
          <div className="alert error" role="alert">
            <p>{state.error.message}</p>
            {state.error.kind === "forbidden" && (
              <p>Chỉ tài khoản Quản lý hậu cần mới dùng được tính năng này.</p>
            )}
            {state.error.traceId && <small>Mã tra cứu: {state.error.traceId}</small>}
            {canRetry && (
              <p>
                <button type="button" onClick={start}>
                  Thử lại
                </button>
              </p>
            )}
            {state.error.retryable && !canRetry && <p>Đã thử nhiều lần không được. Hãy thử lại sau ít phút.</p>}
          </div>
        )}
        {state.status === "success" && (
          <div className="analytics-source">
            <AiSourceBadge envelope={state.envelope} />
            {data && data.narrativeMode === "DETERMINISTIC_FALLBACK" && (
              <small className="muted">Báo cáo xác định do hệ thống tạo; phần số liệu vẫn đầy đủ.</small>
            )}
          </div>
        )}
        {state.status === "success" && !data && <div className="alert error">Không có nội dung phân tích.</div>}
        {data && <Result data={data} />}
      </div>
    </div>
  );
}
