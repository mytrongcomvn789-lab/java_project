import { useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import PageHeader from '../../components/ui/PageHeader';
import { useAuth } from '../auth/AuthContext';
import { orderService } from '../../lib/orderService';
import { newIdempotencyKey, type OrderRequestBody } from '../../lib/orderForm';
import OrderForm from './OrderForm';

// FR-04: khách tạo yêu cầu giao hàng = chọn địa chỉ (3.1) + trạm gửi/nhận (3.2) + gói hàng (3.4) rồi gửi POST /v1/orders (3.6).
export default function CreateOrderPage() {
  const { user, isDemo } = useAuth();
  const header = (
    <PageHeader
      crumbs={['Trang chủ', 'Tạo yêu cầu giao hàng']}
      title="Tạo yêu cầu giao hàng"
      subtitle="Gửi yêu cầu giao hàng mới bằng drone."
      code="FR-04"
    />
  );
  if (isDemo)
    return (
      <>
        {header}
        <div className="card customer-notice" role="status">
          Hãy đăng nhập bằng tài khoản thật để tạo yêu cầu giao hàng.
        </div>
      </>
    );
  if (user?.role !== 'CUSTOMER')
    return (
      <>
        {header}
        <div className="card customer-notice" role="alert">
          Chỉ tài khoản khách hàng mới tạo được yêu cầu giao hàng.
        </div>
      </>
    );
  return (
    <>
      {header}
      <CreateForm />
    </>
  );
}

function CreateForm() {
  const navigate = useNavigate();
  // Cùng nội dung -> cùng key (gửi lại sau lỗi mạng không tạo đơn thứ hai); đổi nội dung -> key mới
  // (cùng key khác nội dung bị server từ chối IDEMPOTENCY_KEY_CONFLICT).
  const attempt = useRef<{ key: string; body: string } | null>(null);

  function keyFor(body: OrderRequestBody): string {
    const json = JSON.stringify(body);
    if (!attempt.current || attempt.current.body !== json)
      attempt.current = { key: newIdempotencyKey(), body: json };
    return attempt.current.key;
  }

  return (
    <OrderForm
      submitLabel="Xác nhận và gửi yêu cầu"
      onSubmit={async (body) => {
        const order = await orderService.create(body, keyFor(body));
        navigate(`/deliveries/${order.id}`, {
          state: { notice: 'Đã gửi yêu cầu giao hàng. Đơn đang chờ duyệt.' },
        });
      }}
    />
  );
}