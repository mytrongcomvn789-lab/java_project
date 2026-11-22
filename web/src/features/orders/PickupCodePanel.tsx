import { useCallback, useEffect, useState } from "react";
import { QRCodeSVG } from "qrcode.react";
import { ApiRequestError } from "../../lib/apiClient";
import {
  deliveryConfirmationService,
  type PickupCode,
} from "../../lib/deliveryConfirmationService";

interface Props {
  orderId: string;
}

const pad = (n: number) => String(n).padStart(2, "0");

// Khách xem mã OTP + QR khi đơn ở AWAITING_PICKUP. Mã chỉ nằm trong state, không lưu ở đâu khác.
// Hết hạn thì KHÔNG tự gọi lại: khách bấm "Lấy mã mới" (backend cấp mã mới khi mã cũ hết hạn).
export default function PickupCodePanel({ orderId }: Props) {
  const [code, setCode] = useState<PickupCode | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [now, setNow] = useState(() => Date.now());

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setCode(await deliveryConfirmationService.pickupCode(orderId));
    } catch (e) {
      setCode(null);
      setError(
        e instanceof ApiRequestError && e.status === 409
          ? "Đơn chưa ở trạng thái chờ nhận hàng nên chưa có mã."
          : "Không lấy được mã nhận hàng. Hãy thử lại.",
      );
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);

  const remainingMs = code ? new Date(code.expiresAt).getTime() - now : 0;
  const expired = !!code && remainingMs <= 0;
  const seconds = Math.max(0, Math.floor(remainingMs / 1000));

  return (
    <section className="card customer-panel" aria-label="Mã nhận hàng">
      <h3>Mã nhận hàng</h3>
      <p>Đưa mã này cho nhân viên trạm khi nhận hàng. Không chia sẻ mã cho người khác.</p>

      {loading && !code && <p role="status">Đang lấy mã…</p>}

      {error && (
        <p role="alert" className="customer-error">
          {error}{" "}
          <button type="button" onClick={() => void load()}>
            Thử lại
          </button>
        </p>
      )}

      {code && !expired && (
        <div style={{ display: "flex", gap: 24, flexWrap: "wrap", alignItems: "center" }}>
          <QRCodeSVG value={code.qrPayload} size={168} marginSize={2} title="Mã QR nhận hàng" />
          <div>
            <p className="muted" style={{ margin: 0 }}>
              Mã OTP
            </p>
            <p
              aria-label={`Mã OTP ${code.otp.split("").join(" ")}`}
              style={{ fontSize: 32, fontWeight: 700, letterSpacing: 4, margin: "4px 0" }}
            >
              {code.otp.length === 6 ? `${code.otp.slice(0, 3)} ${code.otp.slice(3)}` : code.otp}
            </p>
            <p className="muted" role="timer">
              Còn hiệu lực {pad(Math.floor(seconds / 60))}:{pad(seconds % 60)}
            </p>
          </div>
        </div>
      )}

      {code && expired && (
        <p role="alert">
          Mã đã hết hạn.{" "}
          <button type="button" className="customer-primary" onClick={() => void load()}>
            Lấy mã mới
          </button>
        </p>
      )}
    </section>
  );
}