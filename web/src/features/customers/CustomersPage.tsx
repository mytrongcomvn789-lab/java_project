import { useEffect, useState, type FormEvent } from "react";
import PageHeader from "../../components/ui/PageHeader";
import { customerService, type Customer, type CustomerPage } from "../../lib/customerService";
import "./customers.css";

const PAGE_SIZE = 10;
const PHONE = /^\+?[0-9]{9,15}$/;

export default function CustomersPage() {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [revision, setRevision] = useState(0);
  const [data, setData] = useState<CustomerPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [editing, setEditing] = useState<Customer | null>(null);
  const [fullName, setFullName] = useState("");
  const [phone, setPhone] = useState("");
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const timer = window.setTimeout(() => { setSearch(searchInput.trim()); setPage(1); }, 350);
    return () => window.clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    customerService.list(search, page, PAGE_SIZE)
      .then((result) => { if (active) setData(result); })
      .catch((cause) => {
        if (active) { setData(null); setError(cause instanceof Error ? cause.message : "Không thể tải khách hàng."); }
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [search, page, revision]);

  function openEditor(customer: Customer) {
    setEditing(customer);
    setFullName(customer.fullName);
    setPhone(customer.phone ?? "");
    setFormError("");
    setMessage("");
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!editing) return;
    const name = fullName.trim();
    const normalizedPhone = phone.trim();
    if (!name || name.length > 150) { setFormError("Họ tên phải có từ 1 đến 150 ký tự."); return; }
    if (normalizedPhone && !PHONE.test(normalizedPhone)) {
      setFormError("Số điện thoại gồm 9–15 chữ số, có thể bắt đầu bằng +."); return;
    }
    setSaving(true);
    setFormError("");
    try {
      await customerService.update(editing.id, name, normalizedPhone);
      setEditing(null);
      setMessage("Đã cập nhật thông tin khách hàng.");
      setRevision((value) => value + 1);
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : "Không thể lưu thông tin khách hàng.");
    } finally { setSaving(false); }
  }

  const totalPages = Math.max(1, Math.ceil((data?.total ?? 0) / PAGE_SIZE));
  return <div className="customers-page">
    <PageHeader title="Khách hàng" subtitle="Tra cứu và cập nhật thông tin liên hệ của khách hàng." crumbs={["Trang chủ", "Khách hàng"]} code="FR-03" />
    <section className="card customers-toolbar">
      <label htmlFor="customer-search">Tìm theo tên, email hoặc số điện thoại</label>
      <div><input id="customer-search" type="search" value={searchInput} onChange={(event) => setSearchInput(event.target.value)} placeholder="Nhập thông tin khách hàng" />
      <button type="button" onClick={() => setRevision((value) => value + 1)} disabled={loading}>Tải lại</button></div>
    </section>
    {message && <div className="alert success" role="status">{message}</div>}
    {error && <div className="alert error" role="alert">{error} <button type="button" onClick={() => setRevision((value) => value + 1)}>Thử lại</button></div>}
    <section className="card customers-list" aria-busy={loading}>
      {loading && !data ? <p className="customers-state" role="status">Đang tải khách hàng…</p> : null}
      {data && <>
        <div className="customers-list-head"><h2>Danh sách khách hàng</h2><span>{data.total.toLocaleString("vi-VN")} khách hàng</span></div>
        {data.items.length === 0 ? <p className="customers-state">Không tìm thấy khách hàng phù hợp.</p> : <div className="customers-scroll"><table>
          <thead><tr><th>Họ tên</th><th>Email</th><th>Số điện thoại</th><th>Trạng thái</th><th>Thao tác</th></tr></thead>
          <tbody>{data.items.map((customer) => <tr key={customer.id}>
            <th scope="row">{customer.fullName}</th><td>{customer.email}</td>
            <td>{customer.phone || "Chưa có"}</td><td>{customer.enabled ? "Hoạt động" : "Đã khóa"}</td>
            <td><button type="button" onClick={() => openEditor(customer)}>Chỉnh sửa</button></td>
          </tr>)}</tbody>
        </table></div>}
        <div className="customers-pager"><span>Trang {page} / {totalPages}</span><div>
          <button type="button" onClick={() => setPage((value) => value - 1)} disabled={page <= 1 || loading}>Trước</button>
          <button type="button" onClick={() => setPage((value) => value + 1)} disabled={page >= totalPages || loading}>Sau</button>
        </div></div>
      </>}
    </section>
    {editing && <div className="customers-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setEditing(null); }}>
      <section className="customers-dialog" role="dialog" aria-modal="true" aria-labelledby="customer-edit-title">
        <h2 id="customer-edit-title">Chỉnh sửa khách hàng</h2>
        <p className="customers-hint">Email và quyền tài khoản không thể sửa tại đây.</p>
        <form onSubmit={(event) => void save(event)}>
          <label>Email<input value={editing.email} readOnly /></label>
          <label>Họ tên<input autoFocus value={fullName} maxLength={150} onChange={(event) => setFullName(event.target.value)} required /></label>
          <label>Số điện thoại<input value={phone} onChange={(event) => setPhone(event.target.value)} inputMode="tel" placeholder="Có thể để trống" /></label>
          {formError && <p className="alert error" role="alert">{formError}</p>}
          <div className="customers-dialog-actions"><button type="button" onClick={() => setEditing(null)} disabled={saving}>Hủy</button><button type="submit" disabled={saving}>{saving ? "Đang lưu…" : "Lưu thay đổi"}</button></div>
        </form>
      </section>
    </div>}
  </div>;
}
