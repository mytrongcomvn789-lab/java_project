import axios from "axios";
import { API_URL } from "./env";
import { tokenStorage } from "./token";
import type { ApiError } from "../types/api";

const apiClient = axios.create({
  baseURL: API_URL,
  timeout: 10000,
  headers: { "Content-Type": "application/json" },
});

// Tự động gắn JWT vào mọi request (task 2.5/2.8 sẽ lưu token sau khi đăng nhập)
apiClient.interceptors.request.use((config) => {
  const token = tokenStorage.get();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// Chuẩn hoá lỗi về Error(message)
apiClient.interceptors.response.use(
  (res) => res,
  (error) => {
    // 401: task 2.5 sẽ xử lý refresh token / chuyển về /login tại đây
    const data = error.response?.data as ApiError | undefined;
    const message =
      data?.message || error.message || "Không thể kết nối tới máy chủ";
    return Promise.reject(new Error(message));
  },
);

export default apiClient;
