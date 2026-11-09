import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import { ApiRequestError } from "../../lib/apiClient";
import { addressService, type Address, type AddressInput, type AddressPage } from "../../lib/addressService";
import CustomerGate from "./CustomerGate";

type FormValues = Record<keyof AddressInput, string>;
const EMPTY: FormValues = { label: "", recipientName: "", phone: "", addressLine: "", latitude: "", longitude: "" };

export default function AddressesPage() {
  return <><PageHeader title="Địa chỉ giao hàng" subtitle="Lưu thông tin người nhận để sử dụng khi tạo yêu cầu giao hàng."
    crumbs={["Trang chủ", "Địa chỉ giao hàng"]} /><CustomerGate><AddressBook /></CustomerGate></>;
}
function AddressBook() {
  const [data, setData] = useState<AddressPage | null>(null);
  const [page, setPage] = useState(1);
  const [revision, setRevision] = useState(0);
  const [loadError, setLoadError] = useState("");
  const [formError, setFormError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState("");
  const [values, setValues] = useState<FormValues>(EMPTY);
  const [editing, setEditing] = useState<Address | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [deleting, setDeleting] = useState<Address | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true; setLoadError(""); setData(null);
    addressService.list(page, 10).then(result => {
      if (!active) return;
      if (page > 1 && result.items.length === 0) { setPage(p => p - 1); return; }
      setData(result);
    }).catch(e => { if (active) setLoadError(e.message); });
    return () => { active = false; };
  }, [page, revision]);

  function openForm(address: Address | null) {
    setEditing(address); setDeleting(null); setMessage(""); setFormError(""); setFieldErrors({});
    setValues(address ? {
      label: address.label, recipientName: address.recipientName, phone: address.phone,
      addressLine: address.addressLine, latitude: String(address.latitude), longitude: String(address.longitude),
    } : { ...EMPTY });
    setShowForm(true);
  }
  async function save(event: FormEvent) {
    event.preventDefault(); if (busy) return;
    setBusy(true); setFormError(""); setFieldErrors({}); setMessage("");
    const input: AddressInput = {
      label: values.label.trim(), recipientName: values.recipientName.trim(), phone: values.phone.trim(),
      addressLine: values.addressLine.trim(), latitude: Number(values.latitude), longitude: Number(values.longitude),
    };
    try {
      if (editing) await addressService.update(editing.id, input);
      else await addressService.create(input);
      setShowForm(false); setEditing(null); setMessage("Đã lưu địa chỉ.");
      setPage(1); setRevision(n => n + 1);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : "Không thể lưu địa chỉ.");
      if (e instanceof ApiRequestError) setFieldErrors(Object.fromEntries((e.errors ?? []).map(f => [f.field, f.message])));
    } finally { setBusy(false); }
  }
  async function remove() {
    if (!deleting || busy) return;
    setBusy(true); setFormError(""); setMessage("");
    try {
      await addressService.remove(deleting.id);
      setDeleting(null); setMessage("Đã xóa địa chỉ khỏi sổ."); setRevision(n => n + 1);
    } catch (e) { setFormError(e instanceof Error ? e.message : "Không thể xóa địa chỉ."); }
    finally { setBusy(false); }
  }
  function field(name: keyof FormValues, label: string, type = "text", extra = {}) {
    return <label className="customer-field" key={name} htmlFor={name}>
      <span>{label} <span aria-hidden="true">*</span></span>
      <input id={name} name={name} type={type} required value={values[name]} disabled={busy}
        aria-invalid={!!fieldErrors[name]} aria-describedby={fieldErrors[name] ? name + "-error" : undefined}
        onChange={e => setValues(v => ({ ...v, [name]: e.target.value }))} {...extra} />
      {fieldErrors[name] && <span id={name + "-error"} className="customer-error">{fieldErrors[name]}</span>}
    </label>;
  }
  return <div className="customer-area">
    <div className="customer-actions">
      <button type="button" className="customer-primary" disabled={busy} onClick={() => openForm(null)}>Thêm địa chỉ</button>
      <Link to="/deliveries/new">Chọn địa chỉ cho yêu cầu giao hàng →</Link>
    </div>
    {message && <p role="status" className="customer-success">{message}</p>}
    {showForm && <form className="card customer-panel" onSubmit={save}>
      <h3>{editing ? "Sửa địa chỉ" : "Thêm địa chỉ mới"}</h3>
      <p className="muted">Các trường có dấu * là bắt buộc.</p>
      <div className="customer-form-grid">
        {field("label", "Nhãn địa chỉ", "text", { maxLength: 500, placeholder: "Nhà riêng, văn phòng…" })}
        {field("recipientName", "Tên người nhận", "text", { maxLength: 500, autoComplete: "name" })}
        {field("phone", "Số điện thoại", "tel", { maxLength: 16, pattern: "\\+?[0-9]{9,15}", autoComplete: "tel", title: "9–15 chữ số, có thể bắt đầu bằng +" })}
        {field("addressLine", "Địa chỉ đầy đủ", "text", { maxLength: 500, autoComplete: "street-address" })}
        {field("latitude", "Vĩ độ", "number", { min: -90, max: 90, step: "any", placeholder: "10.762622" })}
        {field("longitude", "Kinh độ", "number", { min: -180, max: 180, step: "any", placeholder: "106.660172" })}
      </div>
      <p className="muted">Vĩ độ từ −90 đến 90, kinh độ từ −180 đến 180. Có thể lấy tọa độ tại vị trí trên bản đồ của bạn.</p>
      {formError && <p role="alert" className="customer-error">{formError}</p>}
      <div className="customer-actions">
        <button className="customer-primary" disabled={busy}>{busy ? "Đang lưu…" : "Lưu địa chỉ"}</button>
        <button type="button" disabled={busy} onClick={() => setShowForm(false)}>Hủy chỉnh sửa</button>
      </div>
    </form>}
    {deleting && <section className="card customer-panel" role="alertdialog" aria-modal="false" aria-labelledby="delete-address-title">
      <h3 id="delete-address-title">Xóa địa chỉ “{deleting.label}”?</h3>
      <p>Địa chỉ sẽ không còn trong sổ để chọn cho yêu cầu mới.</p>
      {formError && <p role="alert" className="customer-error">{formError}</p>}
      <div className="customer-actions">
        <button type="button" className="customer-danger" disabled={busy} onClick={remove}>{busy ? "Đang xóa…" : "Xác nhận xóa"}</button>
        <button type="button" disabled={busy} onClick={() => setDeleting(null)}>Giữ địa chỉ</button>
      </div>
    </section>}
    {loadError ? <div className="card customer-panel" role="alert">{loadError}
      <button type="button" onClick={() => setRevision(n => n + 1)}>Thử tải lại</button></div>
      : !data ? <p role="status">Đang tải sổ địa chỉ…</p>
      : <>
        <p className="muted">{data.total} địa chỉ trong sổ của bạn</p>
        {!data.total && <div className="card customer-panel"><h3>Chưa có địa chỉ</h3><p>Thêm địa chỉ đầu tiên để lưu thông tin người nhận.</p></div>}
        <div className="address-grid">
          {data.items.map(address => <article key={address.id} className="card customer-panel">
            <h3>{address.label}</h3>
            <p><strong>{address.recipientName}</strong> · {address.phone}</p>
            <p>{address.addressLine}</p>
            <p className="muted">Tọa độ: {address.latitude}, {address.longitude}</p>
            <div className="customer-actions">
              <button type="button" disabled={busy} aria-label={`Sửa ${address.label}`} onClick={() => openForm(address)}>Sửa</button>
              <button type="button" disabled={busy} aria-label={`Xóa ${address.label}`} onClick={() => {
                setShowForm(false); setFormError(""); setMessage(""); setDeleting(address);
              }}>Xóa</button>
            </div>
          </article>)}
        </div>
        {data.total > 10 && <nav aria-label="Phân trang địa chỉ" className="customer-actions">
          <button type="button" disabled={busy || page === 1} onClick={() => setPage(p => p - 1)}>Trang trước</button>
          <span>Trang {page} / {Math.ceil(data.total / 10)}</span>
          <button type="button" disabled={busy || page * 10 >= data.total} onClick={() => setPage(p => p + 1)}>Trang sau</button>
        </nav>}
      </>}
  </div>;
}
