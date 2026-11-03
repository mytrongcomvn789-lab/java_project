// Lỗi backend trả về (JSON chuẩn của APDDMP-9 + trường "message" cho web).
export interface ApiError {
  message: string;
  code?: string;
  status?: number;
  traceId?: string;
  // Có khi code = VALIDATION_ERROR: lỗi từng trường của form.
  errors?: { field: string; message: string }[];
}

export interface HealthResponse {
  status: string;
}

// ---- Auth (task 2.5) — khớp docs/05-api/auth-api.md ----

export type RoleName =
  | "CUSTOMER"
  | "DISPATCHER"
  | "STATION_OPERATOR"
  | "LOGISTICS_MANAGER"
  | "SYSTEM_ADMIN";

export interface UserProfile {
  id: string;
  email: string;
  fullName: string;
  phone: string | null;
  role: RoleName;
  createdAt: string; // ISO-8601 UTC
}

export interface RegisterRequest {
  email: string;
  password: string;
  fullName: string;
  phone?: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: "Bearer";
  expiresIn: number; // giây
  user: UserProfile;
}

// ---- Task 2.8: người dùng đang đăng nhập trên giao diện ----
// Chỉ lấy phần cần hiển thị (tên, email, vai trò) từ UserProfile.
export type AuthUser = Pick<UserProfile, "id" | "email" | "fullName" | "role">;
