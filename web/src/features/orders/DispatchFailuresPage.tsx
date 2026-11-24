import { useAuth } from "../auth/AuthContext";
import OrdersPage from "./OrdersPage";

// Điều phối xử lý đơn giao thất bại: mở đơn FAILED để giao lại (lên lịch lại) hoặc hoàn / hủy đơn.
export default function DispatchFailuresPage() {
  const { user } = useAuth();
  if (user?.role !== "DISPATCHER") {
    return (
      <div className="card customer-panel" role="alert">
        Chỉ điều phối viên được xử lý đơn giao thất bại.
      </div>
    );
  }
  return (
    <OrdersPage
      fixedStatus="FAILED"
      title="Giao hàng thất bại"
      subtitle="Các đơn giao thất bại cần xử lý. Mở đơn để giao lại hoặc hoàn đơn."
      crumb="Giao hàng thất bại"
    />
  );
}
