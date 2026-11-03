import axios, { type InternalAxiosRequestConfig } from "axios";
import { API_URL, IS_DEMO_AUTH } from "./env";
import { tokenStorage } from "./token";
import { userStorage } from "./session";
import type { ApiError, TokenResponse } from "../types/api";

// Lỗi đã chuẩn hoá: vẫn là Error(message) như trước, thêm status/code để form xử lý.
export class ApiRequestError extends Error {
  status?: number;
  code?: string;
  traceId?: string;
  errors?: ApiError["errors"];

  constructor(message: string, init: Partial<ApiRequestError> = {}) {
    super(message);
    this.name = "ApiRequestError";
    this.status = init.status;
    this.code = init.code;
    this.traceId = init.traceId;
    this.errors = init.errors;
  }
}

const apiClient = axios.create({
  baseURL: API_URL,
  timeout: 10000,
  headers: { "Content-Type": "application/json" },
});

// Các endpoint xác thực không được tự refresh (tránh lặp vô hạn, và 401 ở đây là sai mật khẩu).
const AUTH_PATHS = [
  "/v1/auth/login",
  "/v1/auth/register",
  "/v1/auth/refresh",
  "/v1/auth/logout",
];

// Tự động gắn JWT vào mọi request
apiClient.interceptors.request.use((config) => {
  const token = tokenStorage.get();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// Nhiều request cùng gặp 401 chỉ gọi refresh MỘT lần (refresh token dùng một lần,
// gọi song song sẽ bị backend coi là dùng lại token và đăng xuất mọi thiết bị).
let refreshing: Promise<string> | null = null;

async function refreshAccessToken(): Promise<string> {
  const refreshToken = tokenStorage.getRefresh();
  if (!refreshToken) throw new Error("Không có refresh token");
  // Dùng axios gốc (không qua interceptor của apiClient).
  const res = await axios.post<TokenResponse>(
    `${API_URL}/v1/auth/refresh`,
    { refreshToken },
    { timeout: 10000 },
  );
  tokenStorage.setTokens(res.data.accessToken, res.data.refreshToken);
  return res.data.accessToken;
}

function redirectToLogin() {
  tokenStorage.clear();
  userStorage.clear();
  // Báo cho AuthContext (task 2.8) đăng xuất trạng thái người dùng.
  window.dispatchEvent(new Event("sd:unauthorized"));
  if (window.location.pathname !== "/login") {
    window.location.assign("/login");
  }
}

apiClient.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config as
      | (InternalAxiosRequestConfig & { _retried?: boolean })
      | undefined;
    const status: number | undefined = error.response?.status;
    const isAuthCall = AUTH_PATHS.some((p) => original?.url?.includes(p));

    // 401 ở API thường: thử làm mới token một lần rồi gọi lại request ban đầu.
    if (
      !IS_DEMO_AUTH && // chế độ demo không có token thật nên không refresh
      status === 401 &&
      original &&
      !original._retried &&
      !isAuthCall
    ) {
      if (tokenStorage.getRefresh()) {
        original._retried = true;
        try {
          refreshing ??= refreshAccessToken().finally(() => {
            refreshing = null;
          });
          const newToken = await refreshing;
          original.headers.Authorization = `Bearer ${newToken}`;
          return apiClient(original);
        } catch {
          redirectToLogin();
        }
      } else {
        redirectToLogin();
      }
    }

    // Chuẩn hóa lỗi: ApiRequestError(message, { status, code, traceId, errors })
    const data = error.response?.data as ApiError | undefined;
    const message =
      data?.message || error.message || "Không thể kết nối tới máy chủ";
    return Promise.reject(
      new ApiRequestError(message, {
        status,
        code: data?.code,
        traceId: data?.traceId,
        errors: data?.errors,
      }),
    );
  },
);

export default apiClient;
