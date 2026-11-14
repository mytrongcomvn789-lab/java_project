import apiClient from "./apiClient";

export type StationStatus = "ACTIVE" | "INACTIVE";

export interface StationInput {
  name: string;
  latitude: number;
  longitude: number;
  capacity: number;
}

export interface Station extends StationInput {
  id: string;
  status: StationStatus;
}

export const stationService = {
  list: async () => (await apiClient.get<Station[]>("/v1/stations")).data,
  create: async (input: StationInput) =>
    (await apiClient.post<Station>("/v1/stations", input)).data,
  update: async (id: string, input: StationInput) =>
    (await apiClient.put<Station>(`/v1/stations/${id}`, input)).data,
  changeStatus: async (id: string, status: StationStatus) =>
    (await apiClient.patch<Station>(`/v1/stations/${id}/status`, { status })).data,
};
