const ACCESS_KEY = "sd_access_token";
const REFRESH_KEY = "sd_refresh_token";

// Lưu ý bảo mật: localStorage đọc được bởi mọi script trong trang, nên XSS có thể lấy token.
// Chấp nhận cho đồ án; bản production nên cân nhắc refresh token trong cookie HttpOnly.
export const tokenStorage = {
  get: () => localStorage.getItem(ACCESS_KEY),
  getRefresh: () => localStorage.getItem(REFRESH_KEY),
  set: (token: string) => localStorage.setItem(ACCESS_KEY, token),
  setTokens: (accessToken: string, refreshToken: string) => {
    localStorage.setItem(ACCESS_KEY, accessToken);
    localStorage.setItem(REFRESH_KEY, refreshToken);
  },
  // Xóa cả hai token (đăng xuất / phiên hết hạn).
  clear: () => {
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  },
  exists: () => !!localStorage.getItem(ACCESS_KEY),
};
