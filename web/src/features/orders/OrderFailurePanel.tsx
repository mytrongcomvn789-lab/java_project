import { useEffect, useRef, useState } from 'react';
import { ApiRequestError } from '../../lib/apiClient';
import type { OrderDetail } from '../../lib/orderService';
import { failureReasonLabel, failureService, FAILURE_REASONS } from '../../lib/failureService';
import { trackingService, type Tracking } from '../../lib/trackingService';

interface Props {
  order: OrderDetail;
  onChanged: (order: OrderDetail, message: string) => void;
  onReload: () => void;
}

type Mode = 'fail' | 'return' | 'complete' | null;

const when = (v: string) => new Date(v).toLocaleString('vi-VN');
const ATTEMPT_STATUS: Record<string, string> = {
  ACTIVE: 'Đang giữ chỗ',
  RELEASED: 'Đã trả chỗ',
};

/**
 * 4.8: các lần giao của đơn, lý do thất bại và thao tác báo thất bại / hoàn hàng.
 * Nút chỉ hiện khi API tracking trả hành động tương ứng trong allowedActions (server vẫn kiểm lại
 * quyền, trạm và phiên bản khi ghi). Không hiển thị gì nếu đơn chưa có lần giao nào.
 */
export default function OrderFailurePanel({ order, onChanged, onReload }: Props) {
  const [tracking, setTracking] = useState<Tracking | null>(null);
  const [loadError, setLoadError] = useState('');
  const [mode, setMode] = useState<Mode>(null);
  const [code, setCode] = useState('');
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [mustReload, setMustReload] = useState(false);
  const inFlight = useRef(false);

  useEffect(() => {
    let active = true;
    trackingService
      .get(order.id)
      .then((t) => active && setTracking(t))
      .catch(() => active && setLoadError('Không tải được các lần giao của đơn.'));
    return () => {
      active = false;
    };
  }, [order.id, order.version]);

  if (loadError) return <p role="alert">{loadError}</p>;
  if (!tracking) return null;
  const allowed = tracking.allowedActions;
  const canFail = allowed.includes('FAIL');
  const canReturn = allowed.includes('START_RETURN');
  const canComplete = allowed.includes('COMPLETE_RETURN');
  const limitReached = order.status === 'FAILED' && !allowed.includes('SCHEDULE') && canReturn;
  const failures = tracking.timeline.filter((e) => e.action === 'FAIL');
  if (tracking.attempts.length === 0 && failures.length === 0) return null;

  function open(next: Mode) {
    setMode(next);
    setCode('');
    setText('');
    setError('');
  }

  async function submit() {
    if (!mode || inFlight.current || mustReload) return;
    if (mode === 'fail' && !code) return setError('Vui lòng chọn lý do thất bại.');
    if (mode === 'fail' && code === 'OTHER' && !text.trim()) {
      return setError('Lý do khác phải kèm ghi chú.');
    }
    if (mode === 'return' && !text.trim()) return setError('Vui lòng nhập lý do hoàn hàng.');
    if (text.length > 500) return setError('Tối đa 500 ký tự.');
    if (!order.etag) {
      setError('Chưa lấy được phiên bản đơn. Hãy tải lại trước khi xử lý.');
      setMustReload(true);
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setError('');
    try {
      if (mode === 'fail') {
        onChanged(await failureService.fail(order.id, order.etag, code, text), 'Đã ghi nhận giao thất bại.');
      } else if (mode === 'return') {
        onChanged(await failureService.startReturn(order.id, order.etag, text), 'Đã bắt đầu hoàn hàng.');
      } else {
        onChanged(await failureService.completeReturn(order.id, order.etag, text), 'Đã xác nhận nhận hàng hoàn.');
      }
    } catch (e) {
      const status = e instanceof ApiRequestError ? e.status : undefined;
      if (status === 400) {
        setError(e instanceof Error ? e.message : 'Dữ liệu chưa hợp lệ.');
      } else if (status === 409 || status === 412 || status === 428) {
        setError('Đơn đã thay đổi hoặc không còn ở trạng thái cho phép. Hãy tải lại để xem kết quả mới nhất.');
        setMustReload(true);
      } else if (status === 403 || status === 404) {
        setError('Bạn không còn quyền xử lý đơn này (hoặc không đúng trạm). Hãy tải lại.');
        setMustReload(true);
      } else {
        // Mất mạng / 5xx: chưa biết lệnh đã được ghi hay chưa → bắt tải lại, không gửi lặp.
        setError('Chưa xác nhận được kết quả. Hãy tải lại đơn trước khi thử tiếp để tránh xử lý lặp.');
        setMustReload(true);
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  const title =
    mode === 'fail' ? 'Báo giao thất bại' : mode === 'return' ? 'Bắt đầu hoàn hàng' : 'Xác nhận đã nhận hàng hoàn';

  return (
    <section className="card customer-panel" aria-label="Các lần giao và xử lý thất bại">
      <h3>Các lần giao ({tracking.attempts.length}/{tracking.maxAttempts})</h3>
      {tracking.attempts.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', textAlign: 'left', borderSpacing: '12px 6px' }}>
            <thead>
              <tr>
                <th>Lần</th>
                <th>Khung giờ</th>
                <th>Trạng thái chỗ</th>
                <th>Kết quả</th>
              </tr>
            </thead>
            <tbody>
              {tracking.attempts.map((a) => {
                const fail = failures.find((f) => f.attemptNo === a.attemptNo);
                return (
                  <tr key={a.id}>
                    <td>{a.attemptNo}</td>
                    <td>
                      {when(a.slotStart)} – {new Date(a.slotEnd).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}
                    </td>
                    <td>{ATTEMPT_STATUS[a.status] ?? a.status}</td>
                    <td>
                      {fail ? (
                        <>
                          Thất bại: {failureReasonLabel(fail.reasonCode) || fail.reason}
                          {fail.reasonCode && fail.reason && fail.reason !== failureReasonLabel(fail.reasonCode) && (
                            <>
                              <br />
                              <small>{fail.reason}</small>
                            </>
                          )}
                        </>
                      ) : a.status === 'ACTIVE' ? (
                        'Đang thực hiện'
                      ) : (
                        '—'
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {limitReached && (
        <p role="status">Đã hết số lần giao lại cho phép. Chỉ có thể hoàn hàng về trạm gửi.</p>
      )}
      {error && <p role="alert">{error}</p>}
      {mustReload ? (
        <button type="button" onClick={onReload}>Tải lại đơn</button>
      ) : !mode ? (
        (canFail || canReturn || canComplete) && (
          <div className="customer-actions">
            {canFail && <button type="button" onClick={() => open('fail')}>Báo giao thất bại</button>}
            {canReturn && <button type="button" onClick={() => open('return')}>Hoàn hàng về trạm gửi</button>}
            {canComplete && (
              <button type="button" className="customer-primary" onClick={() => open('complete')}>
                Xác nhận đã nhận hàng hoàn
              </button>
            )}
          </div>
        )
      ) : (
        <form onSubmit={(e) => { e.preventDefault(); void submit(); }}>
          <fieldset disabled={busy} style={{ border: 0, padding: 0, margin: 0 }}>
            <legend>{title}</legend>
            {mode === 'fail' && (
              <label className="customer-field">
                <span>Lý do thất bại (bắt buộc)</span>
                <select value={code} onChange={(e) => setCode(e.target.value)} required>
                  <option value="">— Chọn lý do —</option>
                  {FAILURE_REASONS.map((r) => (
                    <option key={r.code} value={r.code}>{r.label}</option>
                  ))}
                </select>
              </label>
            )}
            <label className="customer-field">
              <span>
                {mode === 'return'
                  ? 'Lý do hoàn hàng (bắt buộc, tối đa 500 ký tự)'
                  : mode === 'fail'
                    ? `Ghi chú (${code === 'OTHER' ? 'bắt buộc' : 'tùy chọn'}, tối đa 500 ký tự)`
                    : 'Ghi chú (tùy chọn, tối đa 500 ký tự)'}
              </span>
              <textarea
                value={text}
                maxLength={500}
                rows={3}
                style={{ width: '100%', boxSizing: 'border-box', font: 'inherit', padding: 10 }}
                onChange={(e) => setText(e.target.value)}
              />
            </label>
            <div className="customer-actions">
              <button type="submit" className="customer-primary">{busy ? 'Đang xử lý…' : 'Xác nhận'}</button>
              <button type="button" onClick={() => open(null)}>Quay lại</button>
            </div>
          </fieldset>
        </form>
      )}
    </section>
  );
}
