import { useState } from "react";
import type { ChangeEvent, FormEvent, InputHTMLAttributes } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "./AuthContext";
import PasswordInput from "../../components/ui/PasswordInput";
import { authService } from "../../lib/authService";
import type { ApiRequestError } from "../../lib/apiClient";

type Field = "fullName" | "email" | "phone" | "password" | "confirm";
type FieldErrors = Partial<Record<Field, string>>;
type TextField = Exclude<Field, "password" | "confirm">;

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PHONE_RE = /^\+?[0-9]{9,15}$/;

// Kiểm tra phía client, khớp quy tắc của RegisterRequest ở backend.
function validate(v: Record<Field, string>): FieldErrors {
  const e: FieldErrors = {};
  if (!v.fullName.trim()) e.fullName = "Vui lòng nhập họ và tên";
  if (!v.email.trim()) e.email = "Vui lòng nhập email";
  else if (!EMAIL_RE.test(v.email.trim()))
    e.email = "Email không đúng định dạng";
  if (!v.phone.trim()) e.phone = "Vui lòng nhập số điện thoại";
  else if (!PHONE_RE.test(v.phone.trim()))
    e.phone = "Số điện thoại không hợp lệ";
  if (!v.password) e.password = "Vui lòng nhập mật khẩu";
  else if (v.password.length < 8) e.password = "Mật khẩu tối thiểu 8 ký tự";
  else if (!/[A-Za-z]/.test(v.password) || !/\d/.test(v.password))
    e.password = "Mật khẩu phải có ít nhất 1 chữ cái và 1 chữ số";
  if (!v.confirm) e.confirm = "Vui lòng nhập lại mật khẩu";
  else if (v.confirm !== v.password) e.confirm = "Mật khẩu nhập lại không khớp";
  return e;
}

export default function RegisterPage() {
  const { user, isDemo } = useAuth();
  const navigate = useNavigate();

  const [values, setValues] = useState<Record<Field, string>>({
    fullName: "",
    email: "",
    phone: "",
    password: "",
    confirm: "",
  });
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (user) return <Navigate to="/" replace />;

  const set = (f: Field) => (e: ChangeEvent<HTMLInputElement>) =>
    setValues((v) => ({ ...v, [f]: e.target.value }));

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const errs = validate(values);
    setFieldErrors(errs);
    if (Object.keys(errs).length > 0) return;

    setSubmitting(true);
    try {
      await authService.register({
        fullName: values.fullName.trim(),
        email: values.email.trim(),
        phone: values.phone.trim(),
        password: values.password,
      });
      navigate("/login", {
        replace: true,
        state: { notice: "Đăng ký thành công! Hãy đăng nhập để tiếp tục." },
      });
    } catch (err) {
      const apiErr = err as ApiRequestError;
      // Lỗi từng trường từ server hiện dưới ô tương ứng.
      const mapped: FieldErrors = {};
      for (const fe of apiErr.errors ?? []) {
        if (fe.field in values && !mapped[fe.field as Field])
          mapped[fe.field as Field] = fe.message;
      }
      if (apiErr.code === "EMAIL_ALREADY_EXISTS") mapped.email = apiErr.message;
      setFieldErrors(mapped);
      setError(apiErr.message);
    } finally {
      setSubmitting(false);
    }
  };

  const text = (
    f: TextField,
    label: string,
    props: InputHTMLAttributes<HTMLInputElement> = {},
  ) => (
    <div className="field">
      <label htmlFor={f}>{label}</label>
      <input
        id={f}
        value={values[f]}
        onChange={set(f)}
        aria-invalid={!!fieldErrors[f]}
        {...props}
      />
      {fieldErrors[f] && <span className="field-error">{fieldErrors[f]}</span>}
    </div>
  );

  return (
    <form className="login-box" onSubmit={handleSubmit} noValidate>
      <h2>Đăng ký</h2>
      <p className="muted">
        {isDemo
          ? "Chế độ demo không gọi API đăng ký thật."
          : "Tạo tài khoản khách hàng để gửi và theo dõi đơn giao hàng bằng drone."}
      </p>

      <div style={{ margin: "24px 0 8px" }}>
        {text("fullName", "Họ và tên", { autoComplete: "name" })}
        {text("email", "Email", { type: "email", autoComplete: "email" })}
        {text("phone", "Số điện thoại", { type: "tel", autoComplete: "tel" })}
        <div className="field">
          <label htmlFor="password">Mật khẩu</label>
          <PasswordInput
            id="password"
            autoComplete="new-password"
            value={values.password}
            onChange={set("password")}
            aria-invalid={!!fieldErrors.password}
          />
          {fieldErrors.password && (
            <span className="field-error">{fieldErrors.password}</span>
          )}
        </div>
        <div className="field">
          <label htmlFor="confirm">Nhập lại mật khẩu</label>
          <PasswordInput
            id="confirm"
            autoComplete="new-password"
            value={values.confirm}
            onChange={set("confirm")}
            aria-invalid={!!fieldErrors.confirm}
          />
          {fieldErrors.confirm && (
            <span className="field-error">{fieldErrors.confirm}</span>
          )}
        </div>
      </div>

      {error && (
        <div className="alert error" role="alert">
          {error}
        </div>
      )}

      <button
        className="btn-dark"
        type="submit"
        disabled={submitting || isDemo}
      >
        {submitting ? "Đang đăng ký..." : "Đăng ký"}
      </button>

      <p className="auth-switch">
        Đã có tài khoản? <Link to="/login">Đăng nhập</Link>
      </p>
    </form>
  );
}
