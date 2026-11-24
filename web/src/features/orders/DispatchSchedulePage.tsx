import { useAuth } from "../auth/AuthContext";
import OrdersPage from "./OrdersPage";

// Điều phối lên lịch cho đơn đã duyệt: mở đơn APPROVED rồi chọn khung giờ trong trang chi tiết.
export default function DispatchSchedulePage() {
  const { user } = useAuth();
  if (user?.role !== "DISPATCHER") {
    return (
      <div className="card customer-panel" role="alert">
        Chỉ điều phối viên được lên lịch giao hàng.
      </div>
    );
  }
  return (
    <OrdersPage
      fixedStatus="APPROVED"
      title="Lịch giao hàng"
      subtitle="Các đơn đã duyệt đang chờ lên lịch. Mở đơn để chọn khung giờ giao."
      crumb="Lịch giao hàng"
    />
  );
}
