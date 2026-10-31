import { useEffect, useState } from "react";
import { checkHealth } from "../../lib/healthService";

type Status = { state: "loading" | "ok" | "error"; message: string };

export default function HomePage() {
  const [status, setStatus] = useState<Status>({
    state: "loading",
    message: "",
  });

  useEffect(() => {
    checkHealth()
      .then(() => setStatus({ state: "ok", message: "Kết nối API thành công" }))
      .catch((e: Error) => setStatus({ state: "error", message: e.message }));
  }, []);

  return (
    <>
      <h2>Trang chủ</h2>
      <p className="muted">
        Project web SmartDroneDelivery đã khởi tạo xong (task 2.2).
      </p>
      <div className="card api-status">
        Trạng thái API:{" "}
        <span className={`badge ${status.state}`}>
          {status.state === "loading" ? "Đang kiểm tra..." : status.message}
        </span>
      </div>
    </>
  );
}
