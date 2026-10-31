import { NavLink, Outlet } from "react-router-dom";
import Brand from "../components/ui/Brand";

// Task 2.8 sẽ lọc menu theo vai trò (5 actor).
const ITEMS = [
  { to: "/", label: "Trang chủ", icon: "▦", end: true },
  { to: "/profile", label: "Hồ sơ", icon: "☰" },
];

export default function MainLayout() {
  return (
    <div className="app">
      <header className="header">
        <Brand />
        <NavLink className="btn" to="/login">
          Đăng nhập
        </NavLink>
      </header>

      <div className="body">
        <aside className="sidebar">
          <nav className="sidebar-nav">
            {ITEMS.map((i) => (
              <NavLink
                key={i.to}
                to={i.to}
                end={i.end}
                className={({ isActive }) => (isActive ? "active" : "")}
              >
                <span className="nav-icon">{i.icon}</span>
                {i.label}
              </NavLink>
            ))}
          </nav>
        </aside>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
