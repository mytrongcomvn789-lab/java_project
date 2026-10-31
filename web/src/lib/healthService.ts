import apiClient from "./apiClient";
import type { HealthResponse } from "../types/api";

// Gọi thử API backend (endpoint /health do backend cung cấp)
export const checkHealth = async (): Promise<HealthResponse> => {
  const { data } = await apiClient.get<HealthResponse>("/health");
  return data;
};
