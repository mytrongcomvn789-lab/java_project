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
  state: string; // hiện backend trả "UNKNOWN" vì chưa có nguồn heartbeat
  lastSeenAt: string | null;
  source: string; // hiện backend trả "NONE"
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
  changeStatus: async (id: string, status: StationStatus) =>
    (await apiClient.patch<Station>(`/v1/stations/${id}/status`, { status }))
      .data,
};
