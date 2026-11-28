import type { ChargerState } from "../../lib/stationService";

export const CHARGER_STATE_LABEL: Record<ChargerState, string> = {
  EMPTY: "Trống",
  CHARGING: "Đang sạc",
  FULL: "Đã đầy",
  MAINTENANCE: "Bảo trì",
};

/**
 * Biểu tượng một thiết bị sạc. Không màu = trống, vàng = đang sạc, xanh lá = đầy 100%,
 * đỏ có dấu chấm than = bảo trì. Trạng thái luôn có chữ (aria-label), không chỉ dựa vào màu.
 */
export default function ChargerIcon({
  code,
  state,
  percent,
  size = 44,
}: {
  code: string;
  state: ChargerState;
  percent?: number | null;
  size?: number;
}) {
  const label = `${code}: ${CHARGER_STATE_LABEL[state]}${
    percent != null && state !== "MAINTENANCE" ? `, pin ${percent}%` : ""
  }`;
  return (
    <span className={`charger-icon ${state.toLowerCase()}`} title={label} role="img" aria-label={label}>
      <svg width={size} height={size} viewBox="0 0 48 48" aria-hidden="true">
        <rect className="charger-body" x="6" y="6" width="36" height="36" rx="9" />
        {state === "MAINTENANCE" ? (
          <>
            <rect className="charger-mark" x="22" y="13" width="4" height="15" rx="2" />
            <circle className="charger-mark" cx="24" cy="34" r="2.6" />
          </>
        ) : (
          <path className="charger-bolt" d="M26 11 15 26h8l-2 11 12-16h-8z" />
        )}
      </svg>
      <small className="charger-code">{code}</small>
      {percent != null && state !== "MAINTENANCE" && <small className="charger-percent">{percent}%</small>}
    </span>
  );
}

export function ChargerLegend() {
  const items: ChargerState[] = ["EMPTY", "CHARGING", "FULL", "MAINTENANCE"];
  return (
    <ul className="charger-legend" aria-label="Chú giải màu thiết bị sạc">
      {items.map((s) => (
        <li key={s}>
          <span className={`charger-dot ${s.toLowerCase()}`} aria-hidden="true" />
          {s === "EMPTY" && "Trống"}
          {s === "CHARGING" && "Đang sạc"}
          {s === "FULL" && "Đã sạc đầy (100%)"}
          {s === "MAINTENANCE" && "Bảo trì"}
        </li>
      ))}
    </ul>
  );
}
