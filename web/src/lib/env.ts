export const API_URL: string =
  import.meta.env.VITE_API_URL || "http://localhost:5000/api";

// Chế độ demo (task 2.8): đăng nhập giả theo vai trò để xem giao diện khi backend 2.5 chưa xong.
// Bật bằng VITE_DEMO_AUTH=true trong web/.env. Khi nộp bản thật thì đặt false hoặc xóa dòng này.
export const IS_DEMO_AUTH: boolean = import.meta.env.VITE_DEMO_AUTH === "true";

// WebSocket/STOMP (task 4.3): mặc định suy ra từ API_URL, ví dụ http://localhost:5000/api → ws://localhost:5000/api/ws
export const WS_URL: string =
  import.meta.env.VITE_WS_URL || `${API_URL.replace(/^http/i, "ws")}/ws`;
