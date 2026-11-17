import { useAuth } from '../auth/AuthContext';
import OrdersPage from './OrdersPage';

export default function StationParcelsPage() {
  const { user } = useAuth();
  if (user?.role !== 'STATION_OPERATOR')
    return <div className="card customer-panel" role="alert">Chỉ nhân viên trạm được tiếp nhận kiện hàng.</div>;
  return <OrdersPage receiptOnly />;
}
