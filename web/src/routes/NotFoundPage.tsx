import { Link } from "react-router-dom";

export default function NotFoundPage() {
  return (
    <div style={{ padding: 40, textAlign: "center" }}>
      <h2>404 - Không tìm thấy trang</h2>
      <Link to="/">Về trang chủ</Link>
    </div>
  );
}
