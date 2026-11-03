import { useLocation } from "react-router-dom";
import PageHeader from "./PageHeader";
import { useAuth } from "../../features/auth/AuthContext";
import { findNavItem } from "../../lib/navigation";

// Trang tạm cho các mục menu chưa có chức năng thật: hiển thị các chức năng sẽ làm (theo SRS).
export default function PlaceholderPage() {
  const { pathname } = useLocation();
  const { user } = useAuth();
  const item = findNavItem(pathname, user?.role);

  return (
    <>
      <PageHeader
        crumbs={["Trang chủ", item?.label ?? "Trang"]}
        title={item?.label ?? "Trang"}
        subtitle={item?.summary}
        code={item?.code}
      />
      <div className="card placeholder">
        <p className="muted">Trang này sẽ được triển khai ở task sau.</p>
        {item?.features && (
          <>
            <p style={{ marginTop: 24 }}>
              <strong>Chức năng theo SRS</strong>
            </p>
            <ul className="feature-list">
              {item.features.map((f) => (
                <li key={f}>{f}</li>
              ))}
            </ul>
          </>
        )}
      </div>
    </>
  );
}
