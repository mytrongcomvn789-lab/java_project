import apiClient from "./apiClient";
import { tokenStorage } from "./token";
import type {
  LoginRequest,
  RegisterRequest,
  TokenResponse,
  UserProfile,
} from "../types/api";

// Gọi API xác thực của backend (task 2.5). Form đăng nhập/đăng ký làm ở task 2.8.
export const authService = {
  // Đăng ký: backend luôn tạo tài khoản CUSTOMER, không tự đăng nhập.
  register: async (payload: RegisterRequest): Promise<UserProfile> => {
    const { data } = await apiClient.post<UserProfile>(
      "/v1/auth/register",
      payload,
    );
    return data;
  },

  // Đăng nhập: lưu cặp token, trả về thông tin người dùng (có role).
  login: async (payload: LoginRequest): Promise<UserProfile> => {
    const { data } = await apiClient.post<TokenResponse>(
      "/v1/auth/login",
      payload,
    );
    tokenStorage.setTokens(data.accessToken, data.refreshToken);
    return data.user;
  },

  // Đăng xuất: báo backend thu hồi refresh token, dù lỗi mạng vẫn xóa token cục bộ.
  logout: async (): Promise<void> => {
    const refreshToken = tokenStorage.getRefresh();
    try {
      if (refreshToken) {
        await apiClient.post("/v1/auth/logout", { refreshToken });
      }
    } finally {
      tokenStorage.clear();
    }
  },
};
