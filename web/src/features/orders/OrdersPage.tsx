import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import { ApiRequestError } from "../../lib/apiClient";
import {
  orderService,
  ORDER_STATUS_OPTIONS,
  type OrderFilters,
  type OrderPage,
} from "../../lib/orderService";
import { useAuth } from "../auth/AuthContext";

const PAGE_SIZE = 20;
const EMPTY: OrderFilters = {
  code: "",
  status: "",
  from: "",
  to: "",
  stationId: "",
  sort: "createdAt,desc",
};

// Ô ngày (giờ địa phương) → ISO UTC. "Đến ngày" tính hết ngày đó (mốc loại trừ = 00:00 hôm sau).
const startOfDay = (d: string) =>
  d ? new Date(`${d}T00:00:00`).toISOString() : "";
const nextDay = (d: string) => {
  if (!d) return "";
  const t = new Date(`${d}T00:00:00`);
  t.setDate(t.getDate() + 1);
  return t.toISOString();
};

export default function OrdersPage() {
  const { isDemo } = useAuth();
  const [form, setForm] = useState({ ...EMPTY }); // giá trị đang gõ
  const [applied, setApplied] = useState({ ...EMPTY }); // giá trị đã áp dụng (gửi lên server)
  const [page, setPage] = useState(1);
  const [data, setData] = useState<OrderPage | null>(null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    if (isDemo) return;
    let active = true;
    setData(null);
    setError("");
    const f = {
      ...applied,
      from: startOfDay(applied.from),
      to: nextDay(applied.to),
    };
    orderService
      .list(f, page, PAGE_SIZE)
      .then((r) => {
        if (active) setData(r);
      })
      .catch((e) => {
        if (active)
          setError(
            e instanceof ApiRequestError
              ? e.message
              : "Không tải được danh sách đơn.",
          );
      });
    return () => {
      active = false;
    };
  }, [applied, page, retry, isDemo]);

  function submit(e: FormEvent) {
    e.preventDefault();
    setPage(1);
    setApplied({ ...form });
  }
  function reset() {
    setForm({ ...EMPTY });
    setApplied({ ...EMPTY });
    setPage(1);
  }
  const set = (k: keyof OrderFilters, v: string) =>
    setForm((s) => ({ ...s, [k]: v }));
  const pages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;

  return (
    <>
      <PageHeader
        title="Đơn giao hàng"
        subtitle="Tìm kiếm, lọc và xem chi tiết đơn trong phạm vi của bạn."
        crumbs={["Trang chủ", "Đơn giao hàng"]}
      />
      {isDemo ? (
        <div className="card customer-notice">
          Hãy đăng nhập bằng tài khoản thật để xem đơn.
        </div>
      ) : (
        <div className="customer-area">
          <form className="card customer-panel" onSubmit={submit}>
            <div className="customer-form-grid">
              <label className="customer-field">
                <span>Mã đơn</span>
                <input
                  value={form.code}
                  maxLength={36}
                  placeholder="UUID của đơn"
                  onChange={(e) => set("code", e.target.value.trim())}
                />
              </label>
              <label className="customer-field">
                <span>Trạng thái</span>
                <select
                  value={form.status}
                  onChange={(e) => set("status", e.target.value)}
                >
                  <option value="">Tất cả</option>
                  {ORDER_STATUS_OPTIONS.map(([v, l]) => (
                    <option key={v} value={v}>
                      {l}
                    </option>
                  ))}
                </select>
              </label>
              <label className="customer-field">
                <span>Từ ngày</span>
                <input
                  type="date"
                  value={form.from}
                  max={form.to || undefined}
                  onChange={(e) => set("from", e.target.value)}
                />
              </label>
              <label className="customer-field">
                <span>Đến ngày</span>
                <input
                  type="date"
                  value={form.to}
                  min={form.from || undefined}
                  onChange={(e) => set("to", e.target.value)}
                />
              </label>
              <label className="customer-field">
                <span>Trạm</span>
                <input
                  value={form.stationId}
                  maxLength={36}
                  placeholder="UUID của trạm"
                  onChange={(e) => set("stationId", e.target.value.trim())}
                />
              </label>
              <label className="customer-field">
                <span>Sắp xếp</span>
                <select
                  value={form.sort}
                  onChange={(e) => set("sort", e.target.value)}
                >
                  <option value="createdAt,desc">Mới nhất trước</option>
                  <option value="createdAt,asc">Cũ nhất trước</option>
                  <option value="updatedAt,desc">Cập nhật gần đây</option>
                  <option value="status,asc">Theo trạng thái</option>
                </select>
              </label>
            </div>
            <div className="customer-actions">
              <button className="customer-primary">Tìm kiếm</button>
              <button type="button" onClick={reset}>
                Xóa bộ lọc
              </button>
            </div>
          </form>

          {error ? (
            <div className="card customer-panel" role="alert">
              {error}{" "}
              <button type="button" onClick={() => setRetry((n) => n + 1)}>
                Thử lại
              </button>
            </div>
          ) : !data ? (
            <p role="status">Đang tải danh sách đơn…</p>
          ) : data.total === 0 ? (
            <div className="card customer-panel">
              <h3>Không có đơn nào</h3>
              <p className="muted">
                Chưa có đơn phù hợp với bộ lọc hoặc phạm vi xem của bạn.
              </p>
            </div>
          ) : (
            <>
              <p className="muted">{data.total} đơn</p>
              <div
                className="card customer-panel"
                style={{ overflowX: "auto" }}
              >
                <table>
                  <thead>
                    <tr>
                      <th>Mã đơn</th>
                      <th>Trạng thái</th>
                      <th>Người nhận</th>
                      <th>Gói hàng</th>
                      <th>Ngày tạo</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((o) => (
                      <tr key={o.id}>
                        <td>
                          <Link to={`/deliveries/${o.id}`}>
                            {o.id.slice(0, 8)}…
                          </Link>
                        </td>
                        <td>
                          <span
                            className={`badge st-${o.status.toLowerCase()}`}
                          >
                            {o.statusLabel}
                          </span>
                        </td>
                        <td>
                          {o.recipientName}
                          <br />
                          <small className="muted">
                            {o.recipientAddressLine}
                          </small>
                        </td>
                        <td>
                          {o.packageDescription ?? "—"}
                          {o.packageWeightGrams != null &&
                            ` · ${o.packageWeightGrams} g`}
                        </td>
                        <td>{new Date(o.createdAt).toLocaleString("vi-VN")}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <nav aria-label="Phân trang đơn" className="customer-actions">
                <button
                  type="button"
                  disabled={page === 1}
                  onClick={() => setPage((p) => p - 1)}
                >
                  Trang trước
                </button>
                <span>
                  Trang {page} / {pages}
                </span>
                <button
                  type="button"
                  disabled={page >= pages}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Trang sau
                </button>
              </nav>
            </>
          )}
        </div>
      )}
    </>
  );
}
