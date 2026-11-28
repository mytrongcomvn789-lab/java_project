import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import {
  stationService,
  type Station,
  type StationChargers,
  type StationStatus,
} from "../../lib/stationService";
import {
  formatSlotTime,
  scheduleService,
  toMillis,
  todayInVietnam,
  type AvailabilitySlot,
  type StationAvailability,
} from "../../lib/scheduleService";
import { useAuth } from "../auth/AuthContext";
import ChargerIcon from "./ChargerIcon";
import "./stations.css";
import "./chargers.css";

const STATUS_LABELS: Record<StationStatus, string> = {
  ACTIVE: "Đang hoạt động",
  MAINTENANCE: "Đang bảo trì",
  INACTIVE: "Ngừng hoạt động",
};

// FR-07: nhân viên trạm xem trạm có sẵn sàng nhận lịch giao hay không, theo từng khung giờ trong ngày.
export default function StationAvailabilityPage() {
  const { user } = useAuth();
  const isOperator = user?.role === "STATION_OPERATOR";
  const [chargers, setChargers] = useState<StationChargers | null>(null);
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

  // Nhân viên trạm xem thêm tình trạng thiết bị sạc của trạm đang chọn (lỗi thì bỏ qua, không chặn trang).
  useEffect(() => {
    setChargers(null);
    if (!isOperator || !stationId) return;
    const controller = new AbortController();
    stationService
      .chargers(stationId, controller.signal)
      .then(setChargers)
      .catch(() => undefined);
    return () => controller.abort();
  }, [isOperator, stationId, revision]);

  const selected = stations?.find((s) => s.id === stationId);
  const bookable = data?.slots.filter((s) => s.bookable).length ?? 0;
  const nowMs = Date.now();
  const kindOf = (slot: AvailabilitySlot): "now" | "past" | "open" | "full" | "closed" => {
    if (toMillis(slot.slotEnd) <= nowMs) return "past";
    if (toMillis(slot.slotStart) <= nowMs) return "now";
    if (slot.bookable) return "open";
    return slot.free === 0 ? "full" : "closed";
  };
  const slotsOf = data?.slots ?? [];
  const current = slotsOf.find((s) => kindOf(s) === "now");
  const upcoming = slotsOf.filter((s) => ["open", "full"].includes(kindOf(s)));
  const others = slotsOf.filter((s) => ["past", "closed"].includes(kindOf(s)));
  const fullCount = upcoming.filter((s) => kindOf(s) === "full").length;
  const renderSlot = (slot: AvailabilitySlot) => {
    const kind = kindOf(slot);
    return (
      <div
        key={String(slot.slotStart)}
        className={`slot ${kind === "now" ? (slot.free === 0 ? "full" : "open") : kind} ${kind === "now" ? "now" : ""}`}
      >
        <strong>
          {formatSlotTime(slot.slotStart)} – {formatSlotTime(slot.slotEnd)}
        </strong>
        <span>
          Đã giữ {slot.used} / {data?.capacity}
        </span>
        <small>
          {kind === "now"
            ? "Đang diễn ra"
            : kind === "past"
              ? "Đã qua"
              : kind === "closed"
                ? "Ngoài thời gian đặt lịch"
                : kind === "full"
                  ? "Đã đầy"
                  : `Còn ${slot.free} chỗ`}
        </small>
      </div>
    );
  };

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

          {isOperator && chargers && (
            <article className="station-panel">
              <div className="station-heading">
                <h3>Thiết bị sạc</h3>
                <Link className="station-link" to={`/stations/${stationId}/chargers`}>
                  Chi tiết sạc và drone
                </Link>
              </div>
              <p>
                <strong>{chargers.summary.empty}</strong> trống · <strong>{chargers.summary.charging}</strong> đang
                sạc · <strong>{chargers.summary.full}</strong> đã đầy · <strong>{chargers.summary.maintenance}</strong>{" "}
                bảo trì. Số khung giờ ở trên là chỗ để đặt lịch giao; số thiết bị sạc quyết định trạm nhận được bao
                nhiêu drone cùng lúc.
              </p>
              <div className="charger-mini">
                {chargers.chargers.map((c) => (
                  <ChargerIcon key={c.id} code={c.code} state={c.state} percent={c.batteryPercent} size={34} />
                ))}
              </div>
            </article>
          )}

          {data.slots.length === 0 ? (
            <div className="station-panel">
              <p>Không có khung giờ nào trong ngày này.</p>
            </div>
          ) : (
            <>
              <ul className="slot-legend" aria-label="Chú giải khung giờ">
                <li>Xanh: còn chỗ</li>
                <li>Đỏ: đã đầy</li>
                <li>Viền đậm: đang diễn ra</li>
                <li>Mờ: đã qua hoặc ngoài thời gian đặt lịch</li>
              </ul>

              {current && (
                <section className="slot-section">
                  <h4>Khung giờ hiện tại</h4>
                  <div className="slot-grid">{renderSlot(current)}</div>
                </section>
              )}

              <section className="slot-section">
                <h4>
                  Có thể đặt lịch ({upcoming.length - fullCount} còn chỗ
                  {fullCount > 0 ? `, ${fullCount} đã đầy` : ""})
                </h4>
                {upcoming.length === 0 ? (
                  <p className="station-hint">
                    Không còn khung giờ nào trong thời gian đặt lịch của ngày này.
                  </p>
                ) : (
                  <div className="slot-grid">{upcoming.map(renderSlot)}</div>
                )}
              </section>

              {others.length > 0 && (
                <details className="slot-collapsed">
                  <summary>Khung giờ đã qua hoặc ngoài thời gian đặt lịch ({others.length})</summary>
                  <div className="slot-grid">{others.map(renderSlot)}</div>
                </details>
              )}
            </>
          )}
        </>
      )}
    </div>
  );
}
