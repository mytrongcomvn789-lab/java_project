import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import PageHeader from "../../components/ui/PageHeader";
import PasswordInput from "../../components/ui/PasswordInput";
import { ApiRequestError } from "../../lib/apiClient";
import {
  adminService,
  type Account,
  type AccountPage,
} from "../../lib/adminService";
import { ROLES, ROLE_LABELS } from "../../lib/roles";
import type { RoleName } from "../../types/api";
import { useAuth } from "../auth/AuthContext";
import "./admin.css";

const PAGE_SIZE = 10;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_RE = /^\+?[0-9]{9,15}$/;

interface FormValues {
  fullName: string;
  email: string;
  phone: string;
  role: RoleName;
  password: string;
}
type FormErrors = Partial<Record<keyof FormValues, string>>;

const EMPTY: FormValues = {
  fullName: "",
  email: "",
  phone: "",
  role: "CUSTOMER",
  password: "",
};

function validate(v: FormValues, creating: boolean): FormErrors {
  const e: FormErrors = {};
  if (!v.fullName.trim()) e.fullName = "Vui lòng nhập họ và tên";
  if (creating) {
    if (!v.email.trim()) e.email = "Vui lòng nhập email";
    else if (!EMAIL_RE.test(v.email.trim()))
      e.email = "Email không đúng định dạng";
    if (!v.password) e.password = "Vui lòng nhập mật khẩu ban đầu";
    else if (v.password.length < 8) e.password = "Mật khẩu tối thiểu 8 ký tự";
    else if (!/[A-Za-z]/.test(v.password) || !/\d/.test(v.password))
      e.password = "Mật khẩu phải có ít nhất 1 chữ cái và 1 chữ số";
  }
  if (!v.phone.trim()) e.phone = "Vui lòng nhập số điện thoại";
  else if (!PHONE_RE.test(v.phone.trim()))
    e.phone = "Số điện thoại không hợp lệ";
  return e;
}

const formatDate = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString("vi-VN");
};

export default function AccountsPage() {
  const { user } = useAuth();
  const [data, setData] = useState<AccountPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [page, setPage] = useState(1);
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [role, setRole] = useState<RoleName | "">("");
  const [enabled, setEnabled] = useState<"true" | "false" | "">("");
  const [revision, setRevision] = useState(0);
  const [message, setMessage] = useState("");
  const [actionError, setActionError] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);

  // Modal thêm / sửa
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Account | null>(null);
  const [values, setValues] = useState<FormValues>(EMPTY);
  const [errors, setErrors] = useState<FormErrors>({});
  const [formError, setFormError] = useState("");
  const [saving, setSaving] = useState(false);

  // Hộp xác nhận khóa / mở khóa
  const [confirming, setConfirming] = useState<Account | null>(null);

  // Tìm kiếm có độ trễ để không gọi API mỗi lần gõ phím.
  useEffect(() => {
    const t = setTimeout(() => {
      setSearch(searchInput.trim());
      setPage(1);
    }, 350);
    return () => clearTimeout(t);
  }, [searchInput]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError("");
    adminService
      .listAccounts({ search, role, enabled, page, pageSize: PAGE_SIZE })
      .then((result) => {
        if (active) setData(result);
      })
      .catch((e: Error) => {
        if (!active) return;
        setData(null);
        setLoadError(e.message || "Không thể tải danh sách tài khoản.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [search, role, enabled, page, revision]);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  function openCreate() {
    setEditing(null);
    setValues(EMPTY);
    setErrors({});
    setFormError("");
    setFormOpen(true);
  }

  function openEdit(a: Account) {
    setEditing(a);
    setValues({
      fullName: a.fullName,
      email: a.email,
      phone: a.phone ?? "",
      role: a.role,
      password: "",
    });
    setErrors({});
    setFormError("");
    setFormOpen(true);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setFormError("");
    const errs = validate(values, !editing);
    setErrors(errs);
    if (Object.keys(errs).length) return;
    setSaving(true);
    try {
      if (editing) {
        await adminService.updateAccount(editing.id, {
          fullName: values.fullName.trim(),
          phone: values.phone.trim(),
          role: values.role,
        });
        setMessage("Đã cập nhật tài khoản.");
      } else {
        await adminService.createAccount({
          fullName: values.fullName.trim(),
          email: values.email.trim(),
          phone: values.phone.trim(),
          role: values.role,
          password: values.password,
        });
        setMessage("Đã tạo tài khoản mới.");
        setPage(1);
      }
      setActionError("");
      setFormOpen(false);
      setRevision((n) => n + 1);
    } catch (err) {
      const apiErr = err as ApiRequestError;
      const mapped: FormErrors = {};
      for (const fe of apiErr.errors ?? []) {
        if (fe.field in values && !mapped[fe.field as keyof FormValues])
          mapped[fe.field as keyof FormValues] = fe.message;
      }
      if (apiErr.code === "EMAIL_ALREADY_EXISTS") mapped.email = apiErr.message;
      setErrors(mapped);
      setFormError(apiErr.message);
    } finally {
      setSaving(false);
    }
  }

  async function toggle(a: Account) {
    setBusyId(a.id);
    setActionError("");
    setMessage("");
    try {
      await adminService.setAccountEnabled(a.id, !a.enabled);
      setMessage(
        a.enabled
          ? `Đã khóa tài khoản ${a.email}.`
          : `Đã mở khóa tài khoản ${a.email}.`,
      );
      setConfirming(null);
      setRevision((n) => n + 1);
    } catch (err) {
      setConfirming(null);
      setActionError((err as Error).message);
    } finally {
      setBusyId(null);
    }
  }

  const field = (
    key: keyof FormValues,
    label: string,
    props: React.InputHTMLAttributes<HTMLInputElement> = {},
  ) => (
    <div className="field">
      <label htmlFor={`acc-${key}`}>{label}</label>
      <input
        id={`acc-${key}`}
        value={values[key]}
        onChange={(e) => setValues((v) => ({ ...v, [key]: e.target.value }))}
        aria-invalid={!!errors[key]}
        {...props}
      />
      {errors[key] && <span className="field-error">{errors[key]}</span>}
    </div>
  );

  return (
    <>
      <PageHeader
        crumbs={["Trang chủ", "Quản lý tài khoản"]}
        title="Quản lý tài khoản"
        subtitle="Tạo, cập nhật, phân vai trò và khóa / mở khóa tài khoản người dùng."
        code="FR-02"
      />

      <div className="admin-toolbar">
        <input
          className="admin-search"
          type="search"
          placeholder="Tìm theo tên, email hoặc số điện thoại"
          aria-label="Tìm kiếm tài khoản"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
        />
        <select
          aria-label="Lọc theo vai trò"
          value={role}
          onChange={(e) => {
            setRole(e.target.value as RoleName | "");
            setPage(1);
          }}
        >
          <option value="">Tất cả vai trò</option>
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {ROLE_LABELS[r]}
            </option>
          ))}
        </select>
        <select
          aria-label="Lọc theo trạng thái"
          value={enabled}
          onChange={(e) => {
            setEnabled(e.target.value as "true" | "false" | "");
            setPage(1);
          }}
        >
          <option value="">Mọi trạng thái</option>
          <option value="true">Đang hoạt động</option>
          <option value="false">Đã khóa</option>
        </select>
        <button type="button" className="btn-dark inline admin-add" onClick={openCreate}>
          + Thêm tài khoản
        </button>
      </div>

      {message && (
        <div className="alert success" role="status">
          {message}
        </div>
      )}
      {actionError && (
        <div className="alert error" role="alert">
          {actionError}
        </div>
      )}

      <div className="card admin-card">
        {loading && !data ? (
          <p className="muted admin-state">Đang tải danh sách tài khoản...</p>
        ) : loadError ? (
          <div className="admin-state">
            <p className="field-error">{loadError}</p>
            <button
              type="button"
              className="admin-btn"
              onClick={() => setRevision((n) => n + 1)}
            >
              Thử lại
            </button>
          </div>
        ) : data && data.items.length === 0 ? (
          <p className="muted admin-state">
            Không có tài khoản nào khớp với bộ lọc.
          </p>
        ) : (
          data && (
            <div className="admin-table-wrap">
              <table className="admin-table">
                <thead>
                  <tr>
                    <th>Họ tên</th>
                    <th>Email</th>
                    <th>Số điện thoại</th>
                    <th>Vai trò</th>
                    <th>Trạng thái</th>
                    <th>Ngày tạo</th>
                    <th aria-label="Thao tác" />
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((a) => {
                    const self = a.id === user?.id;
                    return (
                      <tr key={a.id}>
                        <td>
                          {a.fullName}
                          {self && <span className="tag">Bạn</span>}
                        </td>
                        <td>{a.email}</td>
                        <td>{a.phone ?? "—"}</td>
                        <td>{ROLE_LABELS[a.role]}</td>
                        <td>
                          <span
                            className={`badge ${a.enabled ? "ok" : "st-failed"}`}
                          >
                            {a.enabled ? "Hoạt động" : "Đã khóa"}
                          </span>
                        </td>
                        <td>{formatDate(a.createdAt)}</td>
                        <td className="admin-actions">
                          <button
                            type="button"
                            className="admin-btn"
                            onClick={() => openEdit(a)}
                          >
                            Sửa
                          </button>
                          <button
                            type="button"
                            className="admin-btn"
                            disabled={self || busyId === a.id}
                            title={
                              self
                                ? "Không thể tự khóa tài khoản của mình"
                                : undefined
                            }
                            onClick={() => setConfirming(a)}
                          >
                            {a.enabled ? "Khóa" : "Mở khóa"}
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )
        )}

        {data && data.total > 0 && (
          <div className="admin-pager">
            <span className="muted">
              {data.total} tài khoản · Trang {page}/{totalPages}
            </span>
            <span>
              <button
                type="button"
                className="admin-btn"
                disabled={page <= 1 || loading}
                onClick={() => setPage((p) => p - 1)}
              >
                Trước
              </button>
              <button
                type="button"
                className="admin-btn"
                disabled={page >= totalPages || loading}
                onClick={() => setPage((p) => p + 1)}
              >
                Sau
              </button>
            </span>
          </div>
        )}
      </div>

      {formOpen && (
        <div className="modal-backdrop" role="presentation">
          <form
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-label={editing ? "Sửa tài khoản" : "Thêm tài khoản"}
            onSubmit={submit}
            noValidate
          >
            <h3>{editing ? "Sửa tài khoản" : "Thêm tài khoản"}</h3>
            {field("fullName", "Họ và tên", { autoComplete: "off" })}
            {field("email", "Email", {
              type: "email",
              autoComplete: "off",
              disabled: !!editing,
            })}
            {field("phone", "Số điện thoại", { type: "tel" })}
            <div className="field">
              <label htmlFor="acc-role">Vai trò</label>
              <select
                id="acc-role"
                value={values.role}
                disabled={editing?.id === user?.id}
                onChange={(e) =>
                  setValues((v) => ({ ...v, role: e.target.value as RoleName }))
                }
              >
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABELS[r]}
                  </option>
                ))}
              </select>
              {editing?.id === user?.id && (
                <span className="muted admin-hint">
                  Bạn không thể tự đổi vai trò của mình.
                </span>
              )}
            </div>
            {!editing && (
              <div className="field">
                <label htmlFor="acc-password">Mật khẩu ban đầu</label>
                <PasswordInput
                  id="acc-password"
                  autoComplete="new-password"
                  value={values.password}
                  onChange={(e) =>
                    setValues((v) => ({ ...v, password: e.target.value }))
                  }
                  aria-invalid={!!errors.password}
                />
                {errors.password && (
                  <span className="field-error">{errors.password}</span>
                )}
              </div>
            )}
            {formError && (
              <div className="alert error" role="alert">
                {formError}
              </div>
            )}
            <div className="modal-actions">
              <button
                type="button"
                className="admin-btn"
                onClick={() => setFormOpen(false)}
                disabled={saving}
              >
                Hủy
              </button>
              <button
                type="submit"
                className="btn-dark inline"
                disabled={saving}
              >
                {saving ? "Đang lưu..." : "Lưu"}
              </button>
            </div>
          </form>
        </div>
      )}

      {confirming && (
        <div className="modal-backdrop" role="presentation">
          <div
            className="modal"
            role="alertdialog"
            aria-modal="true"
            aria-label="Xác nhận"
          >
            <h3>{confirming.enabled ? "Khóa tài khoản?" : "Mở khóa tài khoản?"}</h3>
            <p className="muted">
              {confirming.enabled
                ? `${confirming.email} sẽ bị đăng xuất khỏi mọi thiết bị và không thể đăng nhập cho đến khi được mở khóa.`
                : `${confirming.email} sẽ có thể đăng nhập lại.`}
            </p>
            <div className="modal-actions">
              <button
                type="button"
                className="admin-btn"
                onClick={() => setConfirming(null)}
                disabled={busyId === confirming.id}
              >
                Hủy
              </button>
              <button
                type="button"
                className="btn-dark inline"
                disabled={busyId === confirming.id}
                onClick={() => toggle(confirming)}
              >
                {confirming.enabled ? "Khóa" : "Mở khóa"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
