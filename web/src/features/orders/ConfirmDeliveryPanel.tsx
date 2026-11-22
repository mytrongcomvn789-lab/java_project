import { useRef, useState } from "react";
import ImageUploader from "../../components/ui/ImageUploader";
import { explainConfirmError } from "../../lib/deliveryConfirmationErrors";
import { deliveryConfirmationService } from "../../lib/deliveryConfirmationService";
import type { OrderDetail } from "../../lib/orderService";

interface Props {
  order: OrderDetail;
  onConfirmed: (message: string) => void; // trang cha tải lại đơn (đơn đã sang DELIVERED)
  onReload: () => void;
}

type Mode = "otp" | "qr";

// Nhân viên trạm nhận: nhập OTP (hoặc dán nội dung QR) + tải ảnh bằng chứng → CONFIRM.
// Backend kiểm tra lại quyền, trạm, mã, ảnh và phiên bản (If-Match); UI chỉ chặn gửi thiếu.
export default function ConfirmDeliveryPanel({ order, onConfirmed, onReload }: Props) {
  const [mode, setMode] = useState<Mode>("otp");
  const [otp, setOtp] = useState("");
  const [qrToken, setQrToken] = useState("");
  const [note, setNote] = useState("");
  const [photoFileId, setPhotoFileId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [mustReload, setMustReload] = useState(false);
  const inFlight = useRef(false);

  const credentialReady = mode === "otp" ? otp.trim().length >= 4 : qrToken.trim().length > 0;
  const canSubmit = credentialReady && !!photoFileId && !busy && !mustReload;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit || inFlight.current || !photoFileId) return;
    inFlight.current = true;
    setBusy(true);
    setError("");
    try {
      await deliveryConfirmationService.confirm(
        order,
        mode === "otp" ? { otp: otp.trim() } : { qrToken: qrToken.trim() },
        photoFileId,
        note,
      );
      onConfirmed("Đã xác nhận giao hàng. Đơn chuyển sang Đã giao.");
    } catch (err) {
      const info = explainConfirmError(err);
      setError(info.message);
      setMustReload(info.mustReload);
      if (info.clearCode) {
        setOtp("");
        setQrToken("");
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <section className="card customer-panel" aria-label="Xác nhận giao hàng">
      <h3>Xác nhận giao hàng</h3>
      <p>
        Nhập mã khách đưa và chụp ảnh lúc giao. Nhập sai nhiều lần sẽ làm mã bị khóa.
      </p>

      {error && (
        <p role="alert" className="customer-error">
          {error}
        </p>
      )}

      {mustReload ? (
        <button type="button" onClick={onReload}>
          Tải lại đơn
        </button>
      ) : (
        <form onSubmit={(e) => void submit(e)} style={{ display: "grid", gap: 12, maxWidth: 420 }}>
          <div role="radiogroup" aria-label="Cách xác nhận" className="customer-actions">
            <label>
              <input
                type="radio"
                name="confirm-mode"
                checked={mode === "otp"}
                onChange={() => setMode("otp")}
                disabled={busy}
              />{" "}
              Nhập OTP
            </label>
            <label>
              <input
                type="radio"
                name="confirm-mode"
                checked={mode === "qr"}
                onChange={() => setMode("qr")}
                disabled={busy}
              />{" "}
              Dán nội dung QR
            </label>
          </div>

          {mode === "otp" ? (
            <label>
              Mã OTP
              <input
                value={otp}
                onChange={(e) => setOtp(e.target.value.replace(/\D/g, "").slice(0, 8))}
                inputMode="numeric"
                autoComplete="off"
                maxLength={8}
                disabled={busy}
                style={{ display: "block", width: "100%", fontSize: 20, letterSpacing: 3 }}
              />
            </label>
          ) : (
            <label>
              Nội dung mã QR
              <input
                value={qrToken}
                onChange={(e) => setQrToken(e.target.value)}
                autoComplete="off"
                maxLength={200}
                disabled={busy}
                style={{ display: "block", width: "100%" }}
              />
            </label>
          )}

          <div>
            <p style={{ margin: "0 0 4px" }}>Ảnh xác nhận (JPEG, PNG hoặc WebP)</p>
            <ImageUploader purpose="CONFIRMATION_PHOTO" onUploaded={setPhotoFileId} />
          </div>

          <label>
            Ghi chú (không bắt buộc)
            <textarea
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={500}
              rows={2}
              disabled={busy}
              style={{ display: "block", width: "100%" }}
            />
          </label>

          <div className="customer-actions">
            <button type="submit" className="customer-primary" disabled={!canSubmit}>
              {busy ? "Đang xác nhận…" : "Xác nhận đã giao"}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}