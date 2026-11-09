import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import { profileService } from "../../lib/profileService";
import { ApiRequestError } from "../../lib/apiClient";
import type { UserProfile } from "../../types/api";
import { useAuth } from "../auth/AuthContext";

export default function ProfilePage() {
  const { isDemo } = useAuth();
  return <><PageHeader title="Hồ sơ của tôi" subtitle="Xem và cập nhật thông tin cá nhân." />
    {isDemo ? <div className="card customer-notice">Hãy đăng nhập bằng tài khoản thật để cập nhật hồ sơ.</div> : <ProfileForm />}</>;
}
function ProfileForm() {
  const { updateProfile } = useAuth();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [fullName, setFullName] = useState("");
  const [phone, setPhone] = useState("");
  const [error, setError] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    setError(""); setProfile(null);
    profileService.get().then(p => {
      if (active) { setProfile(p); setFullName(p.fullName); setPhone(p.phone ?? ""); updateProfile(p); }
    }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [retry, updateProfile]);
  async function save(event: FormEvent) {
    event.preventDefault(); if (busy) return;
    setBusy(true); setError(""); setMessage(""); setFields({});
    try {
      const p = await profileService.update(fullName.trim(), phone.trim());
      setProfile(p); setFullName(p.fullName); setPhone(p.phone ?? "");
      updateProfile(p); setMessage("Đã cập nhật hồ sơ.");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không thể lưu hồ sơ.");
      if (e instanceof ApiRequestError) setFields(Object.fromEntries((e.errors ?? []).map(f => [f.field, f.message])));
    } finally { setBusy(false); }
  }
  if (!profile) return <div className="card customer-panel">
    {error ? <div role="alert">{error} <button onClick={() => setRetry(n => n + 1)}>Thử lại</button></div> : <p role="status">Đang tải hồ sơ…</p>}
  </div>;
  return <form className="card customer-panel customer-area" onSubmit={save}>
    <h3>Thông tin cá nhân</h3>
    <div className="customer-form-grid">
      <label className="customer-field"><span>Email</span><input value={profile.email} readOnly /></label>
      <label className="customer-field"><span>Họ tên *</span>
        <input required maxLength={150} autoComplete="name" value={fullName} disabled={busy}
          aria-invalid={!!fields.fullName} onChange={e => setFullName(e.target.value)} />
        {fields.fullName && <span className="customer-error">{fields.fullName}</span>}
      </label>
      <label className="customer-field"><span>Số điện thoại</span>
        <input type="tel" maxLength={16} pattern={"\\+?[0-9]{9,15}"} autoComplete="tel" value={phone} disabled={busy}
          aria-invalid={!!fields.phone} onChange={e => setPhone(e.target.value)} />
        {fields.phone && <span className="customer-error">{fields.phone}</span>}
      </label>
    </div>
    {error && <p role="alert" className="customer-error">{error}</p>}
    {message && <p role="status" className="customer-success">{message}</p>}
    <div className="customer-actions">
      <button className="customer-primary" disabled={busy}>{busy ? "Đang lưu…" : "Lưu hồ sơ"}</button>
      {profile.role === "CUSTOMER" && <Link to="/addresses">Quản lý sổ địa chỉ →</Link>}
    </div>
  </form>;
}
