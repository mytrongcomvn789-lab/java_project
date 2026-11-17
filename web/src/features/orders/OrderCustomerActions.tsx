import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { explainOrderError } from '../../lib/orderErrors';
import { ifMatchOf, orderService, type OrderDetail } from '../../lib/orderService';

interface Props {
  order: OrderDetail;
  onChanged: (order: OrderDetail, message: string) => void;
  onReload: () => void;
}

// Khách sửa hoặc hủy đơn của mình khi còn PENDING. Backend vẫn kiểm tra lại quyền, trạng thái và phiên bản (If-Match).
export default function OrderCustomerActions({ order, onChanged, onReload }: Props) {
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [mustReload, setMustReload] = useState(false);
  const inFlight = useRef(false);

  async function cancel() {
    if (inFlight.current || mustReload) return;
    inFlight.current = true;
    setBusy(true);
    setError('');
    let cancelled = false;
    try {
      await orderService.cancel(order.id, ifMatchOf(order));
      cancelled = true;
      onChanged(await orderService.get(order.id), 'Đã hủy đơn.');
    } catch (e) {
      if (cancelled) {
        onReload(); // đã hủy thành công nhưng chưa tải được dữ liệu mới
      } else {
        const info = explainOrderError(e);
        setError(info.message);
        setMustReload(info.mustReload);
        setConfirming(false);
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <section className="card customer-panel" aria-label="Thao tác với đơn của bạn">
      <h3>Đơn đang chờ duyệt</h3>
      <p>Bạn có thể sửa hoặc hủy đơn cho tới khi điều phối viên duyệt.</p>
      {error && (
        <p role="alert" className="customer-error">
          {error}
        </p>
      )}
      {mustReload ? (
        <button type="button" onClick={onReload}>
          Tải lại đơn
        </button>
      ) : confirming ? (
        <div className="customer-actions">
          <span>Hủy đơn này? Thao tác không thể hoàn tác.</span>
          <button type="button" className="customer-danger" disabled={busy} onClick={() => void cancel()}>
            {busy ? 'Đang hủy…' : 'Xác nhận hủy'}
          </button>
          <button type="button" disabled={busy} onClick={() => setConfirming(false)}>
            Không hủy
          </button>
        </div>
      ) : (
        <div className="customer-actions">
          <button
            type="button"
            className="customer-primary"
            onClick={() => navigate(`/deliveries/${order.id}/edit`)}
          >
            Sửa đơn
          </button>
          <button type="button" onClick={() => setConfirming(true)}>
            Hủy đơn
          </button>
        </div>
      )}
    </section>
  );
}