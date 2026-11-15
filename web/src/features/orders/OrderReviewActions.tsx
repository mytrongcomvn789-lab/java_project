import { useRef, useState } from 'react';
import { ApiRequestError } from '../../lib/apiClient';
import { orderService, type OrderDetail } from '../../lib/orderService';

interface Props {
  order: OrderDetail;
  onReviewed: (order: OrderDetail, message: string) => void;
  onReload: () => void;
}

export default function OrderReviewActions({ order, onReviewed, onReload }: Props) {
  const [choice, setChoice] = useState<'approve' | 'reject' | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [mustReload, setMustReload] = useState(false);
  const inFlight = useRef(false);

  async function confirm() {
    if (!choice || inFlight.current || mustReload) return;
    if (choice === 'reject' && (!reason.trim() || reason.length > 500)) {
      setError('Vui lòng nhập lý do từ chối từ 1 đến 500 ký tự.');
      return;
    }
    if (!order.etag) {
      setError('Chưa lấy được phiên bản đơn. Hãy tải lại trước khi xử lý.');
      setMustReload(true);
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setError('');
    try {
      const updated = await orderService.review(order.id, choice, order.etag, reason.trim());
      onReviewed(updated, choice === 'approve' ? 'Đã duyệt đơn thành công.' : 'Đã từ chối đơn và lưu lý do.');
    } catch (e) {
      const status = e instanceof ApiRequestError ? e.status : undefined;
      if (status === 409 || status === 412) {
        setError('Đơn đã thay đổi hoặc đã được xử lý. Hãy tải lại để xem kết quả mới nhất.');
        setMustReload(true);
      } else if (status === 403 || status === 404) {
        setError('Bạn không còn quyền xử lý đơn này. Hãy tải lại.');
        setMustReload(true);
      } else if (!status || status >= 500) {
        setError('Chưa xác nhận được kết quả. Hãy tải lại đơn trước khi thử tiếp để tránh xử lý lặp.');
        setMustReload(true);
      } else {
        setError(e instanceof Error ? e.message : 'Không thể xử lý đơn.');
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <section className="card customer-panel" aria-label="Xử lý yêu cầu giao hàng">
      <h3>Duyệt yêu cầu giao hàng</h3>
      <p>Kiểm tra thông tin người nhận và gói hàng trước khi quyết định.</p>
      {error && <p role="alert">{error}</p>}
      {mustReload ? (
        <button type="button" onClick={onReload}>Tải lại đơn</button>
      ) : !choice ? (
        <div className="customer-actions">
          <button type="button" className="customer-primary" onClick={() => setChoice('approve')}>Duyệt đơn</button>
          <button type="button" onClick={() => setChoice('reject')}>Từ chối đơn</button>
        </div>
      ) : (
        <form onSubmit={(e) => { e.preventDefault(); void confirm(); }}>
          <fieldset disabled={busy} style={{ border: 0, padding: 0, margin: 0 }}>
            <legend>{choice === 'approve' ? 'Xác nhận duyệt yêu cầu này?' : 'Lý do từ chối'}</legend>
            {choice === 'reject' && (
              <label className="customer-field">
                <span>Lý do từ chối (bắt buộc, tối đa 500 ký tự)</span>
                <textarea value={reason} maxLength={500} rows={3} required
                  style={{ width: '100%', boxSizing: 'border-box', font: 'inherit', padding: 10 }}
                  onChange={(e) => setReason(e.target.value)} />
              </label>
            )}
            <div className="customer-actions">
              <button type="submit" className="customer-primary">
                {busy ? 'Đang xử lý…' : choice === 'approve' ? 'Xác nhận duyệt' : 'Xác nhận từ chối'}
              </button>
              <button type="button" onClick={() => { setChoice(null); setError(''); }}>Quay lại</button>
            </div>
          </fieldset>
        </form>
      )}
    </section>
  );
}
