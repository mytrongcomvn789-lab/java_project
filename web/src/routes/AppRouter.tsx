import { Routes, Route } from "react-router-dom";
import AuthLayout from "../layouts/AuthLayout";
import MainLayout from "../layouts/MainLayout";
import OrdersPage from "../features/orders/OrdersPage";
import OrderDetailPage from "../features/orders/OrderDetailPage";
import NotFoundPage from "./NotFoundPage";
import LoginPage from "../features/auth/LoginPage";
import RequireAuth from "../features/auth/RequireAuth";
import HomePage from "../features/home/HomePage";
import ProfilePage from "../features/profile/ProfilePage";
import PlaceholderPage from "../components/ui/PlaceholderPage";
import { PLACEHOLDER_PATHS } from "../lib/navigation";
import AddressesPage from "../features/addresses/AddressesPage";
import "../features/addresses/customer.css";
import PackageFormPreviewPage from "../features/packages/PackageFormPreviewPage";
// Task 2.6 sẽ bọc thêm RoleRoute (chặn theo vai trò) bên trong nhóm RequireAuth.
export default function AppRouter() {
  return (
    <Routes>
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<LoginPage />} />
      </Route>

      <Route element={<RequireAuth />}>
        <Route element={<MainLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/deliveries" element={<OrdersPage />} />
          <Route path="/deliveries/:id" element={<OrderDetailPage />} />
          <Route path="/addresses" element={<AddressesPage />} />
          {import.meta.env.DEV && (
            <Route
              path="/dev/package-form"
              element={<PackageFormPreviewPage />}
            />
          )}
          {PLACEHOLDER_PATHS.map((p) => (
            <Route key={p} path={p} element={<PlaceholderPage />} />
          ))}
        </Route>
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
