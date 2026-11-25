import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiRequestError } from '../../lib/apiClient';
import {
  mergeTimeline,
  trackingService,
  type StationPoint,
  type Tracking,
} from '../../lib/trackingService';

const POLL_MS = 10000; // Tạm dùng REST định kỳ; thay bằng kênh realtime 4.3 khi nhánh đó được merge.
const when = (v: string) => new Date(v).toLocaleString('vi-VN');
const has = (p: StationPoint) => p.latitude !== null && p.longitude !== null;

/** Sơ đồ đơn giản (không phải bản đồ nền): hai trạm theo toạ độ thật, vị trí drone chỉ là mô phỏng. */
function RouteMap({ t }: { t: Tracking }) {
  const W = 360,
    H = 140,
    pad = 36;
  if (!has(t.pickup) || !has(t.dropoff)) {
    return (
      <p role="alert">
        Thiếu toạ độ {!has(t.pickup) ? 'trạm gửi' : ''}
        {!has(t.pickup) && !has(t.dropoff) ? ' và ' : ''}
        {!has(t.dropoff) ? 'trạm nhận' : ''}, chưa thể vẽ tuyến.
      </p>
    );
  }
  const lats = [t.pickup.latitude!, t.dropoff.latitude!],
    lngs = [t.pickup.longitude!, t.dropoff.longitude!];
  const span = (a: number[]) => Math.max(Math.max(...a) - Math.min(...a), 1e-6);
  const pt = (s: StationPoint) => ({
    x: pad + ((s.longitude! - Math.min(...lngs)) / span(lngs)) * (W - 2 * pad),
    y:
      H -
      pad -
      ((s.latitude! - Math.min(...lats)) / span(lats)) * (H - 2 * pad),
  });
  const a = pt(t.pickup),
    b = pt(t.dropoff);
  const phase = t.position.phase;
  const drone =
    phase === 'AT_PICKUP'
      ? a
      : phase === 'AT_DROPOFF'
        ? b
        : phase === 'IN_FLIGHT'
          ? { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
          : null;
  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      role="img"
      aria-label="Sơ đồ tuyến giữa trạm gửi và trạm nhận"
      style={{ width: '100%', maxWidth: 480 }}
    >
      <line
        x1={a.x}
        y1={a.y}
        x2={b.x}
        y2={b.y}
        stroke="currentColor"
        strokeDasharray="6 4"
      />
      <circle cx={a.x} cy={a.y} r={7} fill="#2563eb" />
      <circle cx={b.x} cy={b.y} r={7} fill="#16a34a" />
      <text
        x={a.x < W / 2 ? a.x - 8 : a.x + 8}
        y={a.y - 12}
        textAnchor={a.x < W / 2 ? 'start' : 'end'}
        fontSize={11}
      >
        Gửi: {t.pickup.name ?? '—'}
      </text>
      <text
        x={b.x < W / 2 ? b.x - 8 : b.x + 8}
        y={b.y + 22}
        textAnchor={b.x < W / 2 ? 'start' : 'end'}
        fontSize={11}
      >
        Nhận: {t.dropoff.name ?? '—'}
      </text>
      {drone && (
        <rect
          x={drone.x - 6}
          y={drone.y - 6}
          width={12}
          height={12}
          fill="#f59e0b"
          stroke="#000"
        />
      )}
    </svg>
  );
}

interface Props {
  orderId: string;
  /** Gọi khi phiên bản đơn đổi, để trang cha tải lại dữ liệu đơn. */
  onVersionChange?: (version: number) => void;
}

export default function OrderTrackingPanel({
  orderId,
  onVersionChange,
}: Props) {
  const [t, setT] = useState<Tracking | null>(null);
  const [offline, setOffline] = useState(false);
  const [error, setError] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const lastVersion = useRef<number | null>(null);
  // Giữ callback của trang cha trong ref: nếu để trong deps của `load`, mỗi lần trang cha render lại
  // (callback mới) thì `load` đổi, effect chạy lại và gọi API ngay, gây vòng lặp tải lại liên tục.
  const onVersionChangeRef = useRef(onVersionChange);
  onVersionChangeRef.current = onVersionChange;
  const inFlight = useRef(false);

  const load = useCallback(async () => {
    try {
      const next = await trackingService.get(orderId);
      setT((prev) => {
        // Timeline hợp nhất theo orderVersion; nếu có khoảng trống thì dùng nguyên bản REST (đầy đủ).
        if (!prev) return next;
        const { merged, hasGap } = mergeTimeline(prev.timeline, next.timeline);
        return { ...next, timeline: hasGap ? next.timeline : merged };
      });
      setOffline(false);
      setError('');
      if (lastVersion.current !== null && lastVersion.current !== next.version)
        onVersionChangeRef.current?.(next.version);
      lastVersion.current = next.version;
    } catch (e) {
      const status = e instanceof ApiRequestError ? e.status : undefined;
      if (status === 404)
        setError(
          'Không tìm thấy đơn hoặc bạn không có quyền theo dõi đơn này.',
        );
      else setOffline(true); // mất kết nối/5xx: giữ dữ liệu cũ, báo rõ trạng thái
    }
  }, [orderId]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), POLL_MS);
    const onOnline = () => void load(); // reconnect: lấy lại lịch sử REST, không mất bước
    window.addEventListener('online', onOnline);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('online', onOnline);
    };
  }, [load]);

  async function act(action: 'dispatch' | 'arrive') {
    if (!t?.etag || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError('');
    try {
      await trackingService.advance(orderId, action, t.etag, note);
      setNote('');
      await load();
    } catch (e) {
      const status = e instanceof ApiRequestError ? e.status : undefined;
      setError(
        status === 409 ||
          status === 412 ||
          status === 428 ||
          status === 404 ||
          status === 403
          ? 'Đơn đã thay đổi hoặc bạn không còn quyền thao tác. Đã tải lại để kiểm tra.'
          : 'Chưa xác nhận được kết quả. Hãy kiểm tra lại đơn trước khi thử tiếp.',
      );
      await load();
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  if (error && !t)
    return (
      <section className="card customer-panel" role="alert">
        {error}
      </section>
    );
  if (!t) return <p role="status">Đang tải tiến độ giao hàng…</p>;
  const canDispatch = t.allowedActions.includes('DISPATCH'),
    canArrive = t.allowedActions.includes('ARRIVE');

  return (
    <section className="card customer-panel" aria-label="Tiến độ giao hàng">
      <h3>Tiến độ giao hàng</h3>
      {offline && (
        <p role="alert">
          ⚠ Mất kết nối tới máy chủ. Đang hiển thị dữ liệu lúc{' '}
          {when(t.generatedAt)}; sẽ tự tải lại khi có mạng.
        </p>
      )}
      {error && <p role="alert">{error}</p>}
      <p className="muted">
        Cập nhật định kỳ 10 giây qua REST (chưa phải realtime). Tải lúc{' '}
        {when(t.generatedAt)}.
      </p>
      <RouteMap t={t} />
      <p className="muted">
        {t.position.simulated
          ? 'Vị trí drone trên sơ đồ là MÔ PHỎNG (điểm giữa tuyến), không phải GPS thật.'
          : t.map.note}
      </p>
      {(canDispatch || canArrive) && (
        <div className="customer-actions">
          <label className="customer-field">
            <span>Ghi chú (tùy chọn, tối đa 500 ký tự)</span>
            <input
              value={note}
              maxLength={500}
              disabled={busy}
              onChange={(e) => setNote(e.target.value)}
            />
          </label>
          {canDispatch && (
            <button
              className="customer-primary"
              disabled={busy}
              onClick={() => void act('dispatch')}
            >
              {busy ? 'Đang xử lý…' : 'Xác nhận xuất phát'}
            </button>
          )}
          {canArrive && (
            <button
              className="customer-primary"
              disabled={busy}
              onClick={() => void act('arrive')}
            >
              {busy ? 'Đang xử lý…' : 'Xác nhận drone đã đến trạm'}
            </button>
          )}
        </div>
      )}
      <ol style={{ paddingLeft: 18 }}>
        {t.timeline.map((e) => (
          <li key={e.orderVersion}>
            <strong>{e.toStatusLabel}</strong> · {when(e.occurredAt)}
            {e.attemptNo !== null && <> · lần giao {e.attemptNo}</>}
            {e.reason && (
              <>
                <br />
                <small>{e.reason}</small>
              </>
            )}
          </li>
        ))}
      </ol>
    </section>
  );
}
