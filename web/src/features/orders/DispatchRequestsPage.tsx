import { useAuth } from '../auth/AuthContext';
import OrdersPage from './OrdersPage';

export default function DispatchRequestsPage() {
  const { user } = useAuth();
  if (user?.role !== 'DISPATCHER') {
    return <div className="card customer-panel" role="alert">Chỉ điều phối viên được truy cập yêu cầu chờ duyệt.</div>;
  }
  return <OrdersPage pendingOnly />;
}
