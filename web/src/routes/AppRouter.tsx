import { Routes, Route } from "react-router-dom";
import AuthLayout from "../layouts/AuthLayout";
import MainLayout from "../layouts/MainLayout";
import NotFoundPage from "./NotFoundPage";
import LoginPage from "../features/auth/LoginPage";
import HomePage from "../features/home/HomePage";
import ProfilePage from "../features/profile/ProfilePage";

// Task 2.6 sẽ bọc thêm ProtectedRoute/RoleRoute quanh nhóm MainLayout.
export default function AppRouter() {
  return (
    <Routes>
      <Route element={<AuthLayout />}>
        <Route path="/login" element={<LoginPage />} />
      </Route>

      <Route element={<MainLayout />}>
        <Route path="/" element={<HomePage />} />
        <Route path="/profile" element={<ProfilePage />} />
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
