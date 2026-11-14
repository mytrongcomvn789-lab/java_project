import { useEffect, useState, type FormEvent } from "react";
import PageHeader from "../../components/ui/PageHeader";
import { ApiRequestError } from "../../lib/apiClient";
import { stationService, type Station, type StationInput } from "../../lib/stationService";
import { useAuth } from "../auth/AuthContext";
import "./stations.css";

type FormValues = Record<keyof StationInput, string>;
type FormErrors = Partial<Record<keyof FormValues, string>>;
const EMPTY: FormValues = { name: "", latitude: "", longitude: "", capacity: "" };

function validate(values: FormValues): FormErrors {
  const errors: FormErrors = {};
  if (!values.name.trim()) errors.name = "Tên trạm không được để trống";
  else if (values.name.trim().length > 200) errors.name = "Tên trạm tối đa 200 ký tự";
  const latitude = Number(values.latitude);
  if (!values.latitude.trim() || !Number.isFinite(latitude) || latitude < -90 || latitude > 90)
    errors.latitude = "Vĩ độ phải là số từ -90 đến 90";
  const longitude = Number(values.longitude);
  if (!values.longitude.trim() || !Number.isFinite(longitude) || longitude < -180 || longitude > 180)
    errors.longitude = "Kinh độ phải là số từ -180 đến 180";
  const capacity = Number(values.capacity);
  if (!values.capacity.trim() || !Number.isInteger(capacity) || capacity < 1)
    errors.capacity = "Sức chứa phải là số nguyên dương";
  return errors;
}

export default function StationsPage() {
  const { user } = useAuth();
  const canManage = user?.role === "LOGISTICS_MANAGER";
  const isOperator = user?.role === "STATION_OPERATOR";
  const [stations, setStations] = useState<Station[] | null>(null);
  const [editing, setEditing] = useState<Station | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [values, setValues] = useState<FormValues>(EMPTY);
  const [fieldErrors, setFieldErrors] = useState<FormErrors>({});
  const [formError, setFormError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    setLoadError("");
    setStations(null);
    stationService.list().then(data => {
      if (active) setStations(data);
    }).catch(error => {
      if (active) setLoadError(error instanceof Error ? error.message : "Không thể tải danh sách trạm.");
    });
    return () => { active = false; };
  }, [revision]);

  function openForm(station: Station | null) {
    setEditing(station);
    setValues(station ? {
      name: station.name,
      latitude: String(station.latitude),
      longitude: String(station.longitude),
      capacity: String(station.capacity),
    } : { ...EMPTY });
    setFieldErrors({});
    setFormError("");
    setMessage("");
    setShowForm(true);
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const errors = validate(values);
    setFieldErrors(errors);
    setFormError("");
    setMessage("");
    if (Object.keys(errors).length) return;

    const input: StationInput = {
      name: values.name.trim(),
      latitude: Number(values.latitude),
      longitude: Number(values.longitude),
      capacity: Number(values.capacity),
    };
    setBusy(true);
    try {
      if (editing) await stationService.update(editing.id, input);
      else await stationService.create(input);
      setShowForm(false);
      setEditing(null);
      setMessage("Đã lưu thông tin trạm.");
      setRevision(value => value + 1);
    } catch (error) {
      setFormError(error instanceof Error ? error.message : "Không thể lưu trạm.");
      if (error instanceof ApiRequestError) {
        setFieldErrors(Object.fromEntries((error.errors ?? []).map(item => [item.field, item.message])));
      }
    } finally {
      setBusy(false);
    }
  }

  async function toggleStatus(station: Station) {
    if (busy || !canManage) return;
    setBusy(true);
    setFormError("");
    setMessage("");
    try {
      await stationService.changeStatus(station.id, station.status === "ACTIVE" ? "INACTIVE" : "ACTIVE");
      setMessage(station.status === "ACTIVE" ? "Đã ngừng hoạt động trạm." : "Đã kích hoạt trạm.");
      setRevision(value => value + 1);
    } catch (error) {
      setFormError(error instanceof Error ? error.message : "Không thể đổi trạng thái trạm.");
    } finally {
      setBusy(false);
    }
  }

  function field(name: keyof FormValues, label: string, type = "text", extra: Record<string, unknown> = {}) {
    const error = fieldErrors[name];
    return <label className="station-field" key={name} htmlFor={`station-${name}`}>
      <span>{label} *</span>
      <input id={`station-${name}`} name={name} type={type} required value={values[name]} disabled={busy}
        aria-invalid={!!error} aria-describedby={error ? `station-${name}-error` : undefined}
        onChange={event => setValues(current => ({ ...current, [name]: event.target.value }))} {...extra} />
      {error && <span id={`station-${name}-error`} className="station-error">{error}</span>}
    </label>;
  }

  return <div className="stations-page">
    <PageHeader title="Danh sách trạm" subtitle="Thông tin vị trí, sức chứa vật lý và trạng thái hoạt động."
      crumbs={["Trang chủ", "Danh sách trạm"]} />
    {canManage && <div className="station-actions">
      <button type="button" className="station-primary" disabled={busy} onClick={() => openForm(null)}>Thêm trạm</button>
    </div>}
    {isOperator && <p className="station-notice" role="note">
      Chức năng đổi trạng thái của nhân viên trạm đang khóa vì hệ thống chưa có dữ liệu phân công trạm.
      Cần chốt mô hình phân công trước khi cấp quyền cập nhật.
    </p>}
    {message && <p role="status" className="station-success">{message}</p>}
    {formError && !showForm && <p role="alert" className="station-error">{formError}</p>}
    {showForm && <form className="station-panel" onSubmit={save}>
      <h3>{editing ? "Sửa trạm" : "Thêm trạm"}</h3>
      <div className="station-form-grid">
        {field("name", "Tên trạm", "text", { maxLength: 200 })}
        {field("latitude", "Vĩ độ", "number", { min: -90, max: 90, step: "any" })}
        {field("longitude", "Kinh độ", "number", { min: -180, max: 180, step: "any" })}
        {field("capacity", "Sức chứa (số drone tối đa cùng lúc)", "number", { min: 1, step: 1 })}
      </div>
      <p className="station-hint">Sức chứa là số drone tối đa có thể đỗ đồng thời; chưa bao gồm lịch hoặc giữ chỗ theo khung giờ.</p>
      {formError && <p role="alert" className="station-error">{formError}</p>}
      <div className="station-actions">
        <button className="station-primary" disabled={busy}>{busy ? "Đang lưu…" : "Lưu trạm"}</button>
        <button type="button" disabled={busy} onClick={() => setShowForm(false)}>Hủy</button>
      </div>
    </form>}
    {loadError ? <div className="station-panel" role="alert">{loadError}
      <button type="button" onClick={() => setRevision(value => value + 1)}>Thử tải lại</button></div>
      : stations === null ? <p role="status">Đang tải danh sách trạm…</p>
      : stations.length === 0 ? <div className="station-panel"><h3>Chưa có trạm</h3><p>Danh sách hiện chưa có dữ liệu.</p></div>
      : <div className="station-grid">
        {stations.map(station => <article className="station-panel" key={station.id}>
          <div className="station-heading">
            <h3>{station.name}</h3>
            <span className={`station-status ${station.status.toLowerCase()}`}>
              {station.status === "ACTIVE" ? "Đang hoạt động" : "Ngừng hoạt động"}
            </span>
          </div>
          <p>Vĩ độ: {station.latitude} · Kinh độ: {station.longitude}</p>
          <p>Sức chứa: {station.capacity} drone cùng lúc</p>
          {canManage && <div className="station-actions">
            <button type="button" disabled={busy} onClick={() => openForm(station)}>Sửa thông tin</button>
            <button type="button" disabled={busy} onClick={() => void toggleStatus(station)}>
              {station.status === "ACTIVE" ? "Ngừng hoạt động" : "Kích hoạt"}
            </button>
          </div>}
        </article>)}
      </div>}
  </div>;
}
