import apiClient from "./apiClient";

// Khớp DispatchView / DispatchBoardView của backend. Mọi mốc thời gian là ƯỚC TÍNH theo công thức (source = FORMULA).
export type DispatchStage =
  | "NONE"
  | "PLANNED"
  | "ASSIGNED"
  | "IN_FLIGHT"
  | "COMPLETED"
  | "NO_DRONE"
  | "NO_CHARGER";

export interface DispatchView {
  orderId: string;
  orderStatus: string;
  stage: DispatchStage;
  assigned: boolean;
  waitMinutes: number | null;
  waitReason: string | null;
  message: string | null;
  plannedDepartureAt: string | null;
  plannedArrivalAt: string | null;
  // Chỉ nhân viên thấy; khách hàng nhận null.
  droneCode: string | null;
  originStationName: string | null;
  repositionKm: number | null;
  reservedChargerCode: string | null;
  source: string;
  generatedAt: string;
}

export interface FleetDrone {
  id: string;
  code: string;
  status: "IDLE" | "CHARGING" | "IN_FLIGHT" | "MAINTENANCE";
  batteryPercent: number;
  stationName: string | null;
}

export interface DispatchBoard {
  fleet: FleetDrone[];
  orders: {
    orderId: string;
    orderStatus: string;
    pickupStationName: string;
    dropoffStationName: string;
    dispatch: DispatchView;
  }[];
  source: string;
  generatedAt: string;
}

export const STAGE_LABEL: Record<DispatchStage, string> = {
  NONE: "Chưa tới bước điều phối",
  PLANNED: "Dự kiến (chưa phân công)",
  ASSIGNED: "Đã điều phối drone",
  IN_FLIGHT: "Drone đang bay",
  COMPLETED: "Drone đã đến trạm",
  NO_DRONE: "Chưa có drone phù hợp",
  NO_CHARGER: "Trạm nhận chưa có chỗ sạc",
};

export const DRONE_STATUS_LABEL: Record<FleetDrone["status"], string> = {
  IDLE: "Đang đỗ",
  CHARGING: "Đang sạc",
  IN_FLIGHT: "Đang bay",
  MAINTENANCE: "Bảo trì",
};

export const dispatchService = {
  forOrder: async (orderId: string, signal?: AbortSignal) =>
    (await apiClient.get<DispatchView>(`/v1/orders/${orderId}/dispatch`, { signal })).data,
  board: async (signal?: AbortSignal) =>
    (await apiClient.get<DispatchBoard>("/v1/dispatch/board", { signal })).data,
  assign: async (orderId: string) =>
    (await apiClient.post<DispatchView>(`/v1/orders/${orderId}/dispatch-plan`)).data,
};
