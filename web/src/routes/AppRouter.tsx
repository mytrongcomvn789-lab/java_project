import { Routes, Route } from "react-router-dom";
import AuthLayout from "../layouts/AuthLayout";
import StationMonitoringPage from "../features/stations/StationMonitoringPage";
import MainLayout from "../layouts/MainLayout";
import StationParcelsPage from "../features/orders/StationParcelsPage";
import StationReceiptPage from "../features/orders/StationReceiptPage";
import OrdersPage from "../features/orders/OrdersPage";
import DispatchRequestsPage from "../features/orders/DispatchRequestsPage";
import DispatchSchedulePage from "../features/orders/DispatchSchedulePage";
import DispatchFailuresPage from "../features/orders/DispatchFailuresPage";
import StationAvailabilityPage from "../features/stations/StationAvailabilityPage";
import OrderDetailPage from "../features/orders/OrderDetailPage";
import CreateOrderPage from "../features/orders/CreateOrderPage";
import EditOrderPage from "../features/orders/EditOrderPage";
import NotFoundPage from "./NotFoundPage";
import LoginPage from "../features/auth/LoginPage";
import RegisterPage from "../features/auth/RegisterPage";
import RequireAuth from "../features/auth/RequireAuth";
import RequireRole from "../features/auth/RequireRole";
import AccountsPage from "../features/admin/AccountsPage";
import RolesPage from "../features/admin/RolesPage";
import SettingsPage from "../features/admin/SettingsPage";
import AuditLogPage from "../features/admin/AuditLogPage";
import HomePage from "../features/home/HomePage";
import ProfilePage from "../features/profile/ProfilePage";
import PlaceholderPage from "../components/ui/PlaceholderPage";
import { PLACEHOLDER_PATHS } from "../lib/navigation";
import AddressesPage from "../features/addresses/AddressesPage";
import "../features/addresses/customer.css";
import PackageFormPreviewPage from "../features/packages/PackageFormPreviewPage";
import StationsPage from "../features/stations/StationsPage";
import ReportsPage from "../features/reports/ReportsPage";
import DashboardPage from "../features/dashboard/DashboardPage";
import PerformancePage from "../features/reports/PerformancePage";
import AssistantPage from "../features/assistant/AssistantPage";
import OperationsAnalyticsPage from "../features/reports/OperationsAnalyticsPage";
// Task 2.6 sẽ bọc thêm RoleRoute (chặn theo vai trò) bên trong nhóm RequireAuth.
export default function AppRouter() {
  return (
    <Routes>
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
      </Route>

      <Route element={<RequireAuth />}>
        <Route element={<MainLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/deliveries" element={<OrdersPage key="all-orders" />} />
          <Route path="/dispatch/requests" element={<DispatchRequestsPage />} />
          <Route path="/dispatch/schedule" element={<DispatchSchedulePage />} />
          <Route path="/dispatch/failures" element={<DispatchFailuresPage />} />
          <Route path="/station/availability" element={<StationAvailabilityPage />} />
          <Route path="/deliveries/new" element={<CreateOrderPage />} />
          <Route path="/deliveries/:id" element={<OrderDetailPage />} />
          <Route path="/deliveries/:id/edit" element={<EditOrderPage />} />
          <Route path="/addresses" element={<AddressesPage />} />
          <Route path="/stations" element={<StationsPage />} />
          <Route path="/station/parcels" element={<StationParcelsPage />} />
          <Route path="/station/parcels/:id" element={<StationReceiptPage />} />
          <Route
            path="/stations/monitoring"
            element={<StationMonitoringPage />}
          />
          {/* 7.6: chat AI chỉ cho khách hàng (backend cũng chặn vai trò khác bằng 403). */}
          <Route element={<RequireRole roles={["CUSTOMER"]} />}>
            <Route path="/assistant" element={<AssistantPage />} />
          </Route>
          <Route element={<RequireRole roles={["LOGISTICS_MANAGER"]} />}>
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/reports" element={<ReportsPage />} />
            <Route path="/performance" element={<PerformancePage />} />
            <Route path="/ai-analytics" element={<OperationsAnalyticsPage />} />
          </Route>
          {/* FR-02: chỉ SYSTEM_ADMIN. Backend vẫn kiểm tra quyền ở mọi API. */}
          <Route element={<RequireRole roles={["SYSTEM_ADMIN"]} />}>
            <Route path="/admin/users" element={<AccountsPage />} />
            <Route path="/admin/roles" element={<RolesPage />} />
            <Route path="/admin/settings" element={<SettingsPage />} />
          </Route>
          {/* NFR-07 (task 2.4): admin và quản lý hậu cần chỉ xem. */}
          <Route element={<RequireRole roles={["SYSTEM_ADMIN", "LOGISTICS_MANAGER"]} />}>
            <Route path="/admin/audit-log" element={<AuditLogPage />} />
          </Route>
          {import.meta.env.DEV && (
            <Route
              path="/dev/package-form"
              element={<PackageFormPreviewPage />}
            />
          )}
          {PLACEHOLDER_PATHS.filter((p) => p !== "/deliveries/new" && p !== "/performance").map((p) => (
            <Route key={p} path={p} element={<PlaceholderPage />} />
          ))}
        </Route>
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
