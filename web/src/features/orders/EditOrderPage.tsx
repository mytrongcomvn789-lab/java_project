import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import PageHeader from '../../components/ui/PageHeader';
import { useAuth } from '../auth/AuthContext';
import { ApiRequestError } from '../../lib/apiClient';
import { ifMatchOf, orderService, type OrderDetail } from '../../lib/orderService';
import { EMPTY_PACKAGE_FORM, fromPackageView } from '../../lib/packageService';
import OrderForm from './OrderForm';

// Khách sửa đơn của mình khi còn PENDING: PUT /v1/orders/{id} kèm If-Match (3.6).
export default function EditOrderPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { user, isDemo } = useAuth();
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const allowed = !isDemo && user?.role === 'CUSTOMER';

  useEffect(() => {
    if (!allowed) return;
    let active = true;
    setOrder(null);
    setError('');
    orderService
      .get(id)
      .then((o) => {
        if (active) setOrder(o);
      })
      .catch((e) => {
        if (!active) return;
        setError(
          e instanceof ApiRequestError && e.status === 404
            ? 'Không tìm thấy đơn hoặc bạn không có quyền sửa đơn này.'
            : 'Không tải được đơn.',
        );
      });
    return () => {
      active = false;
    };
  }, [id, retry, allowed]);

  const header = (
    <PageHeader
      crumbs={['Trang chủ', 'Đơn giao hàng', 'Sửa đơn']}
      title="Sửa yêu cầu giao hàng"
      subtitle="Chỉ sửa được khi đơn còn đang chờ duyệt."
    />
  );
  const back = <Link to={`/deliveries/${id}`}>← Về chi tiết đơn</Link>;

  let body;
  if (!allowed) {
    body = (
      <div className="card customer-notice" role="alert">
        Chỉ tài khoản khách hàng (đăng nhập thật) mới sửa được đơn của mình.
      </div>
    );
  } else if (error) {
    body = (
      <div className="card customer-panel" role="alert">
        {error}{' '}
        <button type="button" onClick={() => setRetry((n) => n + 1)}>
          Thử lại
        </button>
      </div>
    );
  } else if (!order) {
    body = <p role="status">Đang tải đơn…</p>;
  } else if (order.status !== 'PENDING') {
    body = (
      <div className="card customer-notice" role="alert">
        Đơn đang ở trạng thái “{order.statusLabel}” nên không thể sửa.
      </div>
    );
  } else {
    body = (
      <OrderForm
        key={`${order.id}:${order.version}`}
        initial={{
          pickupStationId: order.pickupStationId,
          dropoffStationId: order.dropoffStationId,
          pkg: order.package ? fromPackageView(order.package) : EMPTY_PACKAGE_FORM,
        }}
        currentRecipient={order.recipient}
        submitLabel="Xác nhận lưu thay đổi"
        onSubmit={async (req) => {
          await orderService.update(order.id, ifMatchOf(order), req);
          navigate(`/deliveries/${order.id}`, { state: { notice: 'Đã lưu thay đổi đơn.' } });
        }}
        onCancel={() => navigate(`/deliveries/${order.id}`)}
        onReload={() => setRetry((n) => n + 1)}
      />
    );
  }

  return (
    <>
      {header}
      <div className="customer-area">
        {back}
        {body}
      </div>
    </>
  );
}