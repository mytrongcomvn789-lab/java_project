import { useEffect, useMemo, useRef, useState } from 'react';
import { ApiRequestError } from '../../lib/apiClient';
import type { OrderDetail } from '../../lib/orderService';
import {
  formatSlotTime,
  scheduleService,
  todayInVietnam,
  toMillis,
  type AvailabilitySlot,
  type StationAvailability,
} from '../../lib/scheduleService';

interface Props {
  order: OrderDetail;
  onScheduled: (order: OrderDetail, message: string) => void;
  onReload: () => void;
}

interface SlotChoice {
  iso: string;
  label: string;
  free: number;
}

export default function OrderScheduleActions({ order, onScheduled, onReload }: Props) {
  const today = useMemo(() => todayInVietnam(), []);
  const [date, setDate] = useState(today);
  const [stations, setStations] = useState<StationAvailability[] | null>(null);
  const [loadError, setLoadError] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);
  const [selected, setSelected] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [mustReload, setMustReload] = useState(false);
  const inFlight = useRef(false);
  const retrying = order.status === 'FAILED';

  useEffect(() => {
    let active = true;
    setStations(null);
    setLoadError('');
    const ids =
      order.pickupStationId === order.dropoffStationId
        ? [order.pickupStationId]
        : [order.pickupStationId, order.dropoffStationId];
    Promise.all(ids.map((id) => scheduleService.availability(id, date)))
      .then((result) => {
        if (active) setStations(result);
      })
      .catch((e) => {
        if (!active) return;
        const status = e instanceof ApiRequestError ? e.status : undefined;
        setLoadError(
          status === 403
            ? 'Bạn không có quyền xem chỗ trống của trạm này.'
            : 'Không tải được chỗ trống của trạm.',
        );
      });
    return () => {
      active = false;
    };
  }, [order.pickupStationId, order.dropoffStationId, date, refreshKey]);

  // Chỉ giữ slot còn chỗ ở mọi trạm liên quan; số chỗ hiển thị là số nhỏ nhất giữa các trạm.
  const choices: SlotChoice[] = useMemo(() => {
    if (!stations) return [];
    const first = stations[0];
    if (!first) return [];
    const others = stations.slice(1).map(
      (s) =>
        new Map<number, AvailabilitySlot>(
          s.slots.map((x) => [toMillis(x.slotStart), x] as [number, AvailabilitySlot]),
        ),
    );
    return first.slots.flatMap((slot) => {
      const ms = toMillis(slot.slotStart);
      let ok = slot.bookable;
      let free = slot.free;
      for (const m of others) {
        const o = m.get(ms);
        if (!o) {
          ok = false;
          break;
        }
        ok = ok && o.bookable;
        free = Math.min(free, o.free);
      }
      if (!ok) return [];
      const iso = typeof slot.slotStart === 'string' ? slot.slotStart : new Date(ms).toISOString();
      return [{ iso, label: `${formatSlotTime(slot.slotStart)} – ${formatSlotTime(slot.slotEnd)}`, free }];
    });
  }, [stations]);

  const chosen = choices.find((c) => c.iso === selected);
  const stationNames = stations ? Array.from(new Set(stations.map((s) => s.stationName))).join(' và ') : '';

  async function confirm() {
    if (!chosen || inFlight.current || mustReload) return;
    if (!order.etag) {
      setError('Chưa lấy được phiên bản đơn. Hãy tải lại trước khi lên lịch.');
      setMustReload(true);
      return;
    }
    inFlight.current = true;
    setBusy(true);
    setError('');
    try {
      const updated = await scheduleService.schedule(order.id, order.etag, chosen.iso);
      onScheduled(
        updated,
        retrying ? 'Đã đặt lịch giao lại thành công.' : 'Đã lên lịch giao hàng thành công.',
      );
    } catch (e) {
      const status = e instanceof ApiRequestError ? e.status : undefined;
      const code = e instanceof ApiRequestError ? e.code : undefined;
      if (code === 'SLOT_FULL' || code === 'STATION_UNAVAILABLE') {
        setError(
          code === 'SLOT_FULL'
            ? 'Khung giờ này vừa đủ chỗ. Hãy chọn khung giờ khác.'
            : 'Một trong hai trạm hiện không hoạt động nên chưa thể lên lịch.',
        );
        setSelected('');
        setRefreshKey((n) => n + 1);
      } else if (code === 'RETRY_LIMIT_REACHED') {
        setError('Đơn đã hết số lần giao lại cho phép. Hãy tải lại và chọn hoàn hàng về trạm gửi.');
        setMustReload(true);
      } else if (status === 409 || status === 412 || status === 428) {
        setError('Đơn đã thay đổi hoặc không còn ở trạng thái cho phép lên lịch. Hãy tải lại để xem kết quả mới nhất.');
        setMustReload(true);
      } else if (status === 403 || status === 404) {
        setError('Bạn không còn quyền xử lý đơn này. Hãy tải lại.');
        setMustReload(true);
      } else if (!status || status >= 500) {
        setError('Chưa xác nhận được kết quả. Hãy tải lại đơn trước khi thử tiếp để tránh đặt lịch lặp.');
        setMustReload(true);
      } else {
        setError(e instanceof Error ? e.message : 'Không thể lên lịch cho đơn.');
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <section className="card customer-panel" aria-label="Lên lịch giao hàng">
      <h3>{retrying ? 'Giao lại đơn thất bại' : 'Lên lịch giao hàng'}</h3>
      <p>Chọn khung giờ còn chỗ ở cả trạm gửi và trạm nhận (giờ Việt Nam).</p>
      {error && <p role="alert">{error}</p>}
      {mustReload ? (
        <button type="button" onClick={onReload}>Tải lại đơn</button>
      ) : (
        <>
          <label className="customer-field">
            <span>Ngày giao</span>
            <input
              type="date"
              value={date}
              min={today}
              disabled={busy}
              onChange={(e) => {
                if (e.target.value) {
                  setDate(e.target.value);
                  setSelected('');
                }
              }}
            />
          </label>
          {loadError ? (
            <p role="alert">
              {loadError}{' '}
              <button type="button" onClick={() => setRefreshKey((n) => n + 1)}>Thử lại</button>
            </p>
          ) : !stations ? (
            <p role="status">Đang tải chỗ trống…</p>
          ) : (
            <>
              <p className="muted">Trạm: {stationNames}</p>
              {choices.length === 0 ? (
                <p>Không còn khung giờ trống trong ngày này. Hãy chọn ngày khác.</p>
              ) : (
                <div
                  role="group"
                  aria-label="Khung giờ còn chỗ"
                  style={{ display: 'flex', flexWrap: 'wrap', gap: 8, margin: '8px 0' }}
                >
                  {choices.map((c) => (
                    <button
                      key={c.iso}
                      type="button"
                      aria-pressed={c.iso === selected}
                      disabled={busy}
                      onClick={() => setSelected(c.iso)}
                      style={{
                        padding: '8px 12px',
                        borderRadius: 8,
                        border: c.iso === selected ? '2px solid currentColor' : '1px solid #999',
                        fontWeight: c.iso === selected ? 600 : 400,
                      }}
                    >
                      {c.label}
                      <br />
                      <small>còn {c.free} chỗ</small>
                    </button>
                  ))}
                </div>
              )}
              <div className="customer-actions">
                <button
                  type="button"
                  className="customer-primary"
                  disabled={!chosen || busy}
                  onClick={() => void confirm()}
                >
                  {busy ? 'Đang xử lý…' : chosen ? `Xác nhận lịch ${chosen.label}` : 'Chọn khung giờ'}
                </button>
              </div>
            </>
          )}
        </>
      )}
    </section>
  );
}