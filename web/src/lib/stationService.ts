import apiClient from "./apiClient";

export type StationStatus = "ACTIVE" | "MAINTENANCE" | "INACTIVE";

export interface StationInput {
  name: string;
  address: string;
  capacity: number;
}

export interface Station {
  id: string;
  name: string;
  address: string | null; // trạm tạo trước khi có cột địa chỉ thì trống
  capacity: number;
  status: StationStatus;
}

// ---- 4.6: giám sát trạm. Khớp StationMonitoringView của backend, web chỉ hiển thị, không tự tính. ----

export interface StationConnection {
  state: string; // UNKNOWN (chưa có thiết bị sạc), ONLINE, OFFLINE
  lastSeenAt: string | null;
  source: string; // NONE hoặc CHARGER_HEARTBEAT (heartbeat thiết bị sạc, hiện là giả lập)
}

// ---- Thiết bị sạc và drone. Số liệu sạc do backend tính theo công thức giả lập (source = SIMULATED). ----

export type ChargerState = "EMPTY" | "CHARGING" | "FULL" | "MAINTENANCE";

export interface ChargerIcon {
  code: string;
  state: ChargerState;
  batteryPercent: number | null;
}

export interface ChargerDetail {
  id: string;
  code: string;
  state: ChargerState;
  online: boolean;
  lastHeartbeatAt: string | null;
  drone: { id: string; code: string } | null;
  batteryPercent: number | null;
  startPercent: number | null;
  targetPercent: number | null;
  chargedMinutes: number | null;
  remainingMinutes: number | null;
  readyAt: string | null;
  temperatureC: number | null;
  canRelease: boolean;
}

export interface StationChargers {
  stationId: string;
  stationName: string;
  stationStatus: StationStatus;
  summary: {
    total: number;
    empty: number;
    charging: number;
    full: number;
    maintenance: number;
    online: number;
  };
  chargers: ChargerDetail[];
  idleDrones: { id: string; code: string; batteryPercent: number }[];
  nextSlotAt: string | null; // null: đang còn thiết bị trống
  source: string;
  generatedAt: string;
}

export interface StationSlotUsage {
  start: string; // ISO-8601, đầu khung giờ hiện tại
  end: string;
  used: number; // chỗ đang giữ (nguồn 4.2)
  free: number;
}

export interface StationPackageLoad {
  scheduled: number;
  preparing: number;
  inbound: number;
  waitingPickup: number;
  atStation: number;
  processingTotal: number;
}

export interface StationMonitoring {
  id: string;
  name: string;
  address: string | null;
  status: StationStatus;
  capacity: number;
  connection: StationConnection;
  slot: StationSlotUsage;
  packages: StationPackageLoad;
  chargers: ChargerIcon[];
}

export const stationService = {
  list: async () => (await apiClient.get<Station[]>("/v1/stations")).data,
  create: async (input: StationInput) =>
    (await apiClient.post<Station>("/v1/stations", input)).data,
  update: async (id: string, input: StationInput) =>
    (await apiClient.put<Station>(`/v1/stations/${id}`, input)).data,
  monitoring: async (status?: StationStatus) =>
    (
      await apiClient.get<StationMonitoring[]>("/v1/stations/monitoring", {
        params: status ? { status } : undefined,
      })
    ).data,
  chargers: async (stationId: string, signal?: AbortSignal) =>
    (await apiClient.get<StationChargers>(`/v1/stations/${stationId}/chargers`, { signal })).data,
  releaseCharger: async (stationId: string, chargerId: string) =>
    (
      await apiClient.post<StationChargers>(
        `/v1/stations/${stationId}/chargers/${chargerId}/release`,
      )
    ).data,
  setChargerMaintenance: async (stationId: string, chargerId: string, maintenance: boolean) =>
    (
      await apiClient.patch<StationChargers>(
        `/v1/stations/${stationId}/chargers/${chargerId}/maintenance`,
        { maintenance },
      )
    ).data,
  changeStatus: async (id: string, status: StationStatus) =>
    (await apiClient.patch<Station>(`/v1/stations/${id}/status`, { status }))
      .data,
};
