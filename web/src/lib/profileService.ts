import apiClient from "./apiClient";
import type { UserProfile } from "../types/api";

export const profileService = {
  get: async () => (await apiClient.get<UserProfile>("/v1/me")).data,
  update: async (fullName: string, phone: string) =>
    (await apiClient.patch<UserProfile>("/v1/me", { fullName, phone })).data,
};
