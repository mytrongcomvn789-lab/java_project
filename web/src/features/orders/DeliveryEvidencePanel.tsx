import { useEffect, useState } from "react";
import SecureImage from "../../components/ui/SecureImage";
import {
  deliveryConfirmationService,
  type DeliveryConfirmation,
} from "../../lib/deliveryConfirmationService";

interface Props {
  orderId: string;
}

const METHOD_LABEL: Record<DeliveryConfirmation["method"], string> = {
  OTP: "Mã OTP",
  QR: "Mã QR",
};

// Bằng chứng giao hàng của đơn DELIVERED: thời điểm, cách xác nhận, người xác nhận, ghi chú, ảnh.
export default function DeliveryEvidencePanel({ orderId }: Props) {
  const [data, setData] = useState<DeliveryConfirmation | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "missing" | "error">("loading");
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    let active = true;
    setState("loading");
    deliveryConfirmationService
      .get(orderId)
      .then((c) => {
        if (!active) return;
        setData(c);
        setState(c ? "ready" : "missing");
      })
      .catch(() => {
        if (active) setState("error");
      });
    return () => {
      active = false;
    };
  }, [orderId, retry]);

  return (
    <section className="card customer-panel" aria-label="Bằng chứng giao hàng">
      <h3>Bằng chứng giao hàng</h3>
      {state === "loading" && <p role="status">Đang tải bằng chứng…</p>}
      {state === "missing" && <p className="muted">Chưa có bằng chứng giao hàng cho đơn này.</p>}
      {state === "error" && (
        <p role="alert" className="customer-error">
          Không tải được bằng chứng.{" "}
          <button type="button" onClick={() => setRetry((n) => n + 1)}>
            Thử lại
          </button>
        </p>
      )}
      {state === "ready" && data && (
        <div style={{ display: "flex", gap: 24, flexWrap: "wrap" }}>
          <SecureImage
            fileId={data.photoFileId}
            alt="Ảnh xác nhận giao hàng"
            className="delivery-evidence-photo"
          />
          <dl style={{ margin: 0 }}>
            <dt className="muted">Thời điểm</dt>
            <dd style={{ margin: "0 0 8px" }}>{new Date(data.confirmedAt).toLocaleString("vi-VN")}</dd>
            <dt className="muted">Xác nhận bằng</dt>
            <dd style={{ margin: "0 0 8px" }}>{METHOD_LABEL[data.method] ?? data.method}</dd>
            <dt className="muted">Nhân viên xác nhận</dt>
            <dd style={{ margin: "0 0 8px" }}>
              <small>{data.confirmedBy}</small>
            </dd>
            {data.note && (
              <>
                <dt className="muted">Ghi chú</dt>
                <dd style={{ margin: 0 }}>{data.note}</dd>
              </>
            )}
          </dl>
        </div>
      )}
    </section>
  );
}