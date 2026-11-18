import { useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import PageHeader from '../../components/ui/PageHeader';
import PackageSummary from '../packages/PackageSummary';
import { ApiRequestError } from '../../lib/apiClient';
import { orderService, type OrderDetail } from '../../lib/orderService';
import { useAuth } from '../auth/AuthContext';
import OrderReviewActions from './OrderReviewActions';
import OrderCustomerActions from './OrderCustomerActions';
import OrderScheduleActions from './OrderScheduleActions';
import OrderTrackingPanel from './OrderTrackingPanel';

const when = (v: string) => new Date(v).toLocaleString('vi-VN');

export default function OrderDetailPage() {
  const { id = '' } = useParams();
  const { isDemo, user } = useAuth();
  const location = useLocation();
  const [notice, setNotice] = useState(
    () => (location.state as { notice?: string } | null)?.notice ?? '',
  );
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    if (isDemo) return;
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
            ? 'Không tìm thấy đơn hoặc bạn không có quyền xem đơn này.'
            : 'Không tải được chi tiết đơn.',
        );
      });
    return () => {
      active = false;
    };
  }, [id, retry, isDemo]);

  return (
    <>
      <PageHeader
        title="Chi tiết đơn"
        crumbs={['Trang chủ', 'Đơn giao hàng', 'Chi tiết']}
      />
      <div className="customer-area">
        <Link to="/deliveries">← Về danh sách đơn</Link>
        {isDemo ? (
          <div className="card customer-notice">
            Hãy đăng nhập bằng tài khoản thật để xem đơn.
          </div>
        ) : error ? (
          <div className="card customer-panel" role="alert">
            {error}{' '}
            <button type="button" onClick={() => setRetry((n) => n + 1)}>
              Thử lại
            </button>
          </div>
        ) : !order ? (
          <p role="status">Đang tải chi tiết đơn…</p>
        ) : (
          <>
            {notice && <p role="status">{notice}</p>}
            {user?.role === 'DISPATCHER' && order.status === 'PENDING' && (
              <OrderReviewActions
                key={`${order.id}:${order.version}`}
                order={order}
                onReviewed={(updated, message) => {
                  setOrder(updated);
                  setNotice(message);
                }}
                onReload={() => setRetry((n) => n + 1)}
              />
            )}
            {user?.role === 'DISPATCHER' &&
              (order.status === 'APPROVED' || order.status === 'FAILED') && (
                <OrderScheduleActions
                  key={`${order.id}:${order.version}`}
                  order={order}
                  onScheduled={(updated, message) => {
                    setOrder(updated);
                    setNotice(message);
                  }}
                  onReload={() => setRetry((n) => n + 1)}
                />
              )}
            {user?.role === 'CUSTOMER' && order.status === 'PENDING' && (
              <OrderCustomerActions
                key={`${order.id}:${order.version}`}
                order={order}
                onChanged={(updated, message) => {
                  setOrder(updated);
                  setNotice(message);
                }}
                onReload={() => setRetry((n) => n + 1)}
              />
            )}
            <OrderTrackingPanel
              orderId={order.id}
              onVersionChange={() => setRetry((n) => n + 1)}
            />
            <section className="card customer-panel">
              <h3>Đơn {order.id}</h3>
              <p>
                <span className={`badge st-${order.status.toLowerCase()}`}>
                  {order.statusLabel}
                </span>
              </p>
              <p className="muted">
                Tạo lúc {when(order.createdAt)} · Cập nhật{' '}
                {when(order.updatedAt)}
              </p>
              <p>
                Trạm gửi: {order.pickupStationId}
                <br />
                Trạm nhận: {order.dropoffStationId}
              </p>
            </section>
            <section className="card customer-panel">
              <h3>Người nhận</h3>
              <p>
                <strong>{order.recipient.name}</strong> ·{' '}
                {order.recipient.phone}
              </p>
              <p>{order.recipient.addressLine}</p>
            </section>
            {order.package && <PackageSummary value={order.package} />}
            <section
              className="card customer-panel"
              style={{ overflowX: 'auto' }}
            >
              <h3>Lịch sử trạng thái</h3>
              <table
                style={{
                  minWidth: 760,
                  width: '100%',
                  borderSpacing: '12px 8px',
                  textAlign: 'left',
                }}
              >
                <thead>
                  <tr>
                    <th>Thời điểm</th>
                    <th>Từ</th>
                    <th>Sang</th>
                    <th>Hành động</th>
                    <th>Người thực hiện</th>
                    <th>Lý do</th>
                  </tr>
                </thead>
                <tbody>
                  {order.history.map((h) => (
                    <tr key={h.orderVersion}>
                      <td>{when(h.occurredAt)}</td>
                      <td>{h.fromStatus ?? '—'}</td>
                      <td>{h.toStatus}</td>
                      <td>{h.action ?? 'Tạo đơn'}</td>
                      <td>
                        {h.actorRole ?? 'Hệ thống'}
                        {h.actorId && (
                          <>
                            <br />
                            <small>{h.actorId}</small>
                          </>
                        )}
                      </td>
                      <td>{h.reason ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          </>
        )}
      </div>
    </>
  );
}
