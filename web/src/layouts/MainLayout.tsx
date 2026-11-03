import { NavLink, Outlet, useNavigate } from "react-router-dom";
import Brand from "../components/ui/Brand";
import { useAuth } from "../features/auth/AuthContext";
import { getMenuForRole } from "../lib/navigation";
import { ROLES, ROLE_LABELS, ROLE_SHORT_LABELS } from "../lib/roles";

const initials = (name: string) =>
  name
    .split(" ")
    .filter(Boolean)
    .slice(-2)
    .map((w) => w[0]?.toUpperCase())
    .join("");

// Task 2.8: layout theo vai trò - menu lọc theo role, header hiển thị người dùng + đăng xuất.
export default function MainLayout() {
  const { user, isDemo, demoLogin, logout } = useAuth();
  const navigate = useNavigate();

  if (!user) return null; // RequireAuth đã chặn, chỉ để thỏa kiểu TypeScript

  const menu = getMenuForRole(user.role);

  const handleLogout = () => {
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="app">
      <header className="header">
        <Brand />
        <div className="header-right">
          {isDemo && <span className="demo-tag">DEMO</span>}
          {isDemo && (
            <div className="role-switch">
              {ROLES.map((r) => (
                <button
                  key={r}
                  type="button"
                  className={user.role === r ? "on" : ""}
                  onClick={() => {
                    demoLogin(r);
                    navigate("/");
                  }}
                >
                  {ROLE_SHORT_LABELS[r]}
                </button>
              ))}
            </div>
          )}
          <div className="user">
            <span className="avatar">{initials(user.fullName)}</span>
            <span>{user.fullName}</span>
          </div>
          <button
            type="button"
            className="icon-btn"
            title="Đăng xuất"
            aria-label="Đăng xuất"
            onClick={handleLogout}
          >
            ⎋
          </button>
        </div>
      </header>

      <div className="body">
        <aside className="sidebar">
          <div className="sidebar-title">MENU</div>
          <nav className="sidebar-nav">
            {menu.map((i) => (
              <NavLink
                key={`${i.to}-${i.label}`}
                to={i.to}
                end={i.end}
                className={({ isActive }) => (isActive ? "active" : "")}
              >
                <span className="nav-icon">{i.icon}</span>
                {i.label}
              </NavLink>
            ))}
          </nav>
          <div className="sidebar-foot">
            <strong>{ROLE_LABELS[user.role]}</strong>
            <span>{user.email}</span>
          </div>
        </aside>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
