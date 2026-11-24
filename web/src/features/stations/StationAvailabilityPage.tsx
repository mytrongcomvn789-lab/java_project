import { useEffect, useState } from "react";
import PageHeader from "../../components/ui/PageHeader";
import { stationService, type Station, type StationStatus } from "../../lib/stationService";
import {
  formatSlotTime,
  scheduleService,
  todayInVietnam,
  type StationAvailability,
} from "../../lib/scheduleService";
import "./stations.css";

const STATUS_LABELS: Record<StationStatus, string> = {
  ACTIVE: "Đang hoạt động",
  MAINTENANCE: "Đang bảo trì",
  INACTIVE: "Ngừng hoạt động",
};

// FR-07: nhân viên trạm xem trạm có sẵn sàng nhận lịch giao hay không, theo từng khung giờ trong ngày.
export default function StationAvailabilityPage() {
  const [stations, setStations] = useState<Station[] | null>(null);
  const [stationId, setStationId] = useState("");
  const [date, setDate] = useState(todayInVietnam());
  const [data, setData] = useState<StationAvailability | null>(null);
  const [loadError, setLoadError] = useState("");
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);

  // Danh sách trạm lấy từ /v1/stations (mọi vai trò xem được); trạm ngoài phạm vi sẽ bị backend trả 404 ở bước sau.
  useEffect(() => {
    let active = true;
    stationService
      .list()
      .then((list) => {
        if (!active) return;
        setStations(list);
        setStationId((current) => current || list[0]?.id || "");
      })
      .catch((error) => {
        if (active)
          setLoadError(error instanceof Error ? error.message : "Không thể tải danh sách trạm.");
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!stationId || !date) return;
    let active = true;
    setLoading(true);
    setLoadError("");
    setData(null);
    scheduleService
      .availability(stationId, date)
      .then((value) => {
        if (active) setData(value);
      })
      .catch((error) => {
        if (active)
          setLoadError(
            error instanceof Error
              ? error.message
              : "Không thể tải tình trạng sẵn sàng của trạm.",
          );
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [stationId, date, revision]);

  const selected = stations?.find((s) => s.id === stationId);
  const bookable = data?.slots.filter((s) => s.bookable).length ?? 0;

  return (
    <div className="stations-page">
      <PageHeader
        title="Tình trạng sẵn sàng"
        subtitle="Chỗ còn trống của trạm theo từng khung giờ trong ngày."
        crumbs={["Trang chủ", "Tình trạng sẵn sàng"]}
      />

      <div className="station-toolbar">
        <label className="station-filter" htmlFor="availability-station">
          <span>Trạm</span>
          <select
            id="availability-station"
            value={stationId}
            onChange={(event) => setStationId(event.target.value)}
          >
            {(stations ?? []).map((station) => (
              <option key={station.id} value={station.id}>
                {station.name}
              </option>
            ))}
          </select>
        </label>
        <label className="station-filter" htmlFor="availability-date">
          <span>Ngày (giờ Việt Nam)</span>
          <input
            id="availability-date"
            type="date"
            value={date}
            onChange={(event) => setDate(event.target.value)}
          />
        </label>
        <button
          type="button"
          className="station-reload"
          disabled={loading}
          onClick={() => setRevision((value) => value + 1)}
        >
          {loading ? "Đang tải…" : "Tải lại"}
        </button>
      </div>

      {loadError ? (
        <div className="station-panel" role="alert">
          <p className="station-error">{loadError}</p>
          <p className="station-hint">
            Bạn chỉ xem được trạm đã được phân công. Nếu danh sách trống, hãy nhờ quản trị viên
            gán trạm cho tài khoản của bạn.
          </p>
        </div>
      ) : stations !== null && stations.length === 0 ? (
        <div className="station-panel">
          <h3>Chưa có trạm</h3>
          <p>Hệ thống chưa có trạm nào.</p>
        </div>
      ) : !data ? (
        <p role="status">Đang tải tình trạng sẵn sàng…</p>
      ) : (
        <>
          <article className="station-panel">
            <div className="station-heading">
              <h3>{data.stationName}</h3>
              <span className={`station-status ${data.stationStatus.toLowerCase()}`}>
                {STATUS_LABELS[data.stationStatus as StationStatus] ?? data.stationStatus}
              </span>
            </div>
            {selected?.address && <p className="station-address">{selected.address}</p>}
            <p>
              Sức chứa mỗi khung giờ: <strong>{data.capacity}</strong> · Mỗi khung{" "}
              <strong>{data.slotMinutes}</strong> phút · Còn <strong>{bookable}</strong> /{" "}
              {data.slots.length} khung có thể đặt lịch
            </p>
            {data.stationStatus !== "ACTIVE" && (
              <p className="station-error" role="alert">
                Trạm không ở trạng thái hoạt động nên không nhận lịch giao mới.
              </p>
            )}
          </article>

          {data.slots.length === 0 ? (
            <div className="station-panel">
              <p>Không có khung giờ nào trong ngày này.</p>
            </div>
          ) : (
            <div className="slot-grid">
              {data.slots.map((slot) => (
                <div
                  key={String(slot.slotStart)}
                  className={`slot ${slot.bookable ? "open" : slot.free === 0 ? "full" : "closed"}`}
                >
                  <strong>
                    {formatSlotTime(slot.slotStart)} – {formatSlotTime(slot.slotEnd)}
                  </strong>
                  <span>
                    Đã giữ {slot.used} / {data.capacity}
                  </span>
                  <small>
                    {slot.bookable
                      ? `Còn ${slot.free} chỗ`
                      : slot.free === 0
                        ? "Đã đầy"
                        : "Ngoài thời gian đặt lịch"}
                  </small>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}
