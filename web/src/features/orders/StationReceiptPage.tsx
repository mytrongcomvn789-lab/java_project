import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import PageHeader from '../../components/ui/PageHeader';
import PackageSummary from '../packages/PackageSummary';
import { useAuth } from '../auth/AuthContext';
import { ApiRequestError } from '../../lib/apiClient';
import { receiptService, type ReceiptContext } from '../../lib/receiptService';

const when = (v: string) => new Date(v).toLocaleString('vi-VN');

export default function StationReceiptPage() {
  const { id = '' } = useParams();
  const { user, isDemo } = useAuth();
  const [data, setData] = useState<ReceiptContext | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [note, setNote] = useState('');
  const [checked, setChecked] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [mustReload, setMustReload] = useState(false);
  const [reload, setReload] = useState(0);
  const inFlight = useRef(false);
  const allowed = user?.role === 'STATION_OPERATOR';

  useEffect(() => {
    if (!allowed || isDemo) return;
    let active = true;
    setData(null); setError(''); setNotice(''); setNote(''); setChecked(false);
    setConfirming(false); setMustReload(false);
    receiptService.context(id).then(value => { if (active) setData(value); }).catch(e => {
      if (active) setError(e instanceof ApiRequestError && [403, 404].includes(e.status ?? 0)
        ? 'Không tìm thấy đơn hoặc bạn không được phân công tại trạm gửi.'
        : 'Không tải được thông tin tiếp nhận. Hãy thử lại.');
    });
    return () => { active = false; };
  }, [id, reload, allowed, isDemo]);

  async function receive() {
    if (!data || !checked || !confirming || inFlight.current || mustReload) return;
    inFlight.current = true; setBusy(true); setError('');
    try {
      const order = await receiptService.receive(data, note.trim());
      setData({ ...data, order }); setConfirming(false); setChecked(false);
      setNotice('Đã nhận kiện tại trạm gửi. Đơn chuyển sang Đang chuẩn bị.');
    } catch (e) {
      const status = e instanceof ApiRequestError ? e.status : undefined;
      setError(e instanceof Error ? e.message : 'Không thể xác nhận nhận kiện.');
      if (!status || status >= 500 || [403, 404, 409, 412, 428].includes(status)) {
        setMustReload(true);
        setError('Chưa thể xác nhận thao tác hoặc đơn đã thay đổi. Hãy tải lại đơn để kiểm tra kết quả trước khi thử tiếp.');
      }
    } finally { inFlight.current = false; setBusy(false); }
  }

  return <>
    <PageHeader title="Tiếp nhận kiện hàng" subtitle="Kiểm tra kiện tại trạm gửi trước khi chuẩn bị giao."
      crumbs={['Trang chủ', 'Tiếp nhận kiện hàng', 'Chi tiết']} />
    <div className="customer-area">
      <Link to="/station/parcels">← Danh sách kiện chờ nhận</Link>
      {!allowed || isDemo ? <p role="alert">Hãy đăng nhập tài khoản nhân viên trạm để tiếp nhận kiện.</p> : <>
        {error && <div className="card customer-panel" role="alert">{error}{' '}
          <button disabled={busy} onClick={() => setReload(n => n + 1)}>Tải lại đơn</button>
        </div>}
        {notice && <p role="status">{notice}</p>}
        {!data ? !error && <p role="status">Đang tải thông tin kiện…</p> : <>
          <section className="card customer-panel">
            <h3>Đơn {data.order.id}</h3>
            <p><span className={`badge st-${data.order.status.toLowerCase()}`}>{data.order.statusLabel}</span></p>
            <p>Trạm gửi: {data.order.pickupStationId}<br />Trạm đích: {data.order.dropoffStationId}</p>
            <p>Người gửi: {data.senderName || 'Chưa có thông tin'}</p>
            <p>Mã kiện: {data.packageId ?? 'Chưa có kiện'}</p>
            {data.attempt && <p>Lần giao {data.attempt.attemptNo} · {data.attempt.id}<br />
              Khung giao: {when(data.attempt.slotStart)} – {when(data.attempt.slotEnd)}</p>}
            <Link to={`/deliveries/${data.order.id}`}>Xem đầy đủ thông tin đơn</Link>
          </section>
          {data.order.package && <PackageSummary value={data.order.package} />}
          {data.order.status === 'SCHEDULED' ? !data.attempt || !data.packageId ?
            <p className="card customer-panel" role="alert">Đơn thiếu kiện hoặc chưa có một lần giao đang hiệu lực. Liên hệ điều phối viên để kiểm tra lịch.</p> :
            <section className="card customer-panel">
              <h3>Xác nhận kiện thực tế</h3>
              <p>Đối chiếu mã đơn, mã kiện, mô tả và khối lượng trước khi nhận. Đây là nhận kiện để chuẩn bị giao.</p>
              <form onSubmit={e => { e.preventDefault(); if (!confirming) setConfirming(true); else void receive(); }}>
                <fieldset disabled={busy || mustReload} style={{ border: 0, padding: 0 }}>
                  <label className="customer-field"><span>Ghi chú tiếp nhận (tối đa 500 ký tự)</span>
                    <textarea value={note} maxLength={500} rows={3} onChange={e => { setNote(e.target.value); setConfirming(false); }}
                      style={{ width: '100%', font: 'inherit', padding: 10, boxSizing: 'border-box' }} />
                  </label>
                  <label><input type="checkbox" required checked={checked}
                    onChange={e => { setChecked(e.target.checked); setConfirming(false); }} /> Tôi đã kiểm tra đúng kiện và nhận kiện thực tế tại trạm gửi.</label>
                  {confirming && <p role="status">Xác nhận nhận kiện này? Hệ thống sẽ lưu người nhận, thời gian và chuyển đơn sang Đang chuẩn bị.</p>}
                  <div className="customer-actions">
                    <button className="customer-primary" type="submit" disabled={!checked}>
                      {busy ? 'Đang lưu…' : confirming ? 'Xác nhận nhận kiện' : 'Tiếp tục nhận kiện'}
                    </button>
                    {confirming && <button type="button" onClick={() => setConfirming(false)}>Quay lại</button>}
                  </div>
                </fieldset>
              </form>
            </section> : <p className="card customer-panel">Đơn không còn ở bước chờ nhận. Không thể xác nhận thêm.</p>}
          <section className="card customer-panel">
            <h3>Lịch sử tiếp nhận</h3>
            {data.order.history.filter(h => h.action === 'RECEIVE').length === 0 ? <p>Chưa có xác nhận nhận kiện.</p> :
              data.order.history.filter(h => h.action === 'RECEIVE').map(h => <div key={h.orderVersion}>
                <p><strong>Đã nhận kiện</strong> · {when(h.occurredAt)}</p>
                <p>Nhân viên: {h.actorId}<br />Ghi chú: {h.reason || 'Không có'}</p>
              </div>)}
          </section>
        </>}
      </>}
    </div>
  </>;
}
