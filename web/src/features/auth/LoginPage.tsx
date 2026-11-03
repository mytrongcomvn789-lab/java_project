import { useState } from "react";
import type { FormEvent } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "./AuthContext";
import { ROLES, ROLE_DESCRIPTIONS, ROLE_LABELS } from "../../lib/roles";
import type { RoleName } from "../../types/api";

export default function LoginPage() {
  const { user, isDemo, login, demoLogin } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? "/";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<RoleName>("CUSTOMER");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (user) return <Navigate to={from} replace />;

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);

    if (isDemo) {
      demoLogin(role);
      navigate(from, { replace: true });
      return;
    }

    if (!email.trim()) return setError("Vui lòng nhập email");
    if (!password) return setError("Vui lòng nhập mật khẩu");

    setSubmitting(true);
    try {
      await login(email.trim(), password);
      navigate(from, { replace: true });
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form className="login-box" onSubmit={handleSubmit} noValidate>
      <h2>Đăng nhập</h2>
      <p className="muted">
        {isDemo
          ? "Chọn một vai trò để xem giao diện tương ứng."
          : "Đăng nhập bằng tài khoản được cấp để vào hệ thống."}
      </p>

      {isDemo ? (
        <div className="role-cards">
          {ROLES.map((r) => (
            <label
              key={r}
              className={`role-card ${role === r ? "selected" : ""}`}
            >
              <span>
                <strong>{ROLE_LABELS[r]}</strong>
                <small>{ROLE_DESCRIPTIONS[r]}</small>
              </span>
              <input
                type="radio"
                name="role"
                checked={role === r}
                onChange={() => setRole(r)}
              />
            </label>
          ))}
        </div>
      ) : (
        <div style={{ margin: "24px 0 8px" }}>
          <div className="field">
            <label htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="password">Mật khẩu</label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
        </div>
      )}

      {error && (
        <div className="alert error" role="alert">
          {error}
        </div>
      )}

      <button className="btn-dark" type="submit" disabled={submitting}>
        {submitting
          ? "Đang đăng nhập..."
          : isDemo
            ? "Vào hệ thống"
            : "Đăng nhập"}
      </button>

      {isDemo && (
        <p className="note">
          Chế độ demo (VITE_DEMO_AUTH=true): không gọi API đăng nhập thật.
        </p>
      )}
    </form>
  );
}
