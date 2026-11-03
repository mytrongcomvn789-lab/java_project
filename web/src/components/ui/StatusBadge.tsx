import { STATUS_LABELS } from "../../lib/deliveryStatus";
import type { DeliveryStatus } from "../../lib/deliveryStatus";

// Nhãn trạng thái đơn giao hàng (dùng lại ở danh sách / chi tiết đơn)
export default function StatusBadge({ status }: { status: DeliveryStatus }) {
  return (
    <span className={`badge st-${status.toLowerCase()}`}>
      {STATUS_LABELS[status]}
    </span>
  );
}
