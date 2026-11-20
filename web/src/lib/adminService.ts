import apiClient from "./apiClient";
import type { RoleName } from "../types/api";

// ---- FR-02: API quản trị (chỉ SYSTEM_ADMIN). Khớp AdminController của backend. ----

export interface Account {
  id: string;
  email: string;
  fullName: string;
  phone: string | null;
  role: RoleName;
  enabled: boolean;
  createdAt: string;
}

export interface AccountPage {
  items: Account[];
  page: number;
  pageSize: number;
  total: number;
}

export interface AccountQuery {
  search?: string;
  role?: RoleName | "";
  enabled?: "true" | "false" | "";
  page: number;
  pageSize: number;
}

export interface CreateAccountInput {
  fullName: string;
  email: string;
  phone: string;
  role: RoleName;
  password: string;
}

export interface UpdateAccountInput {
  fullName: string;
  phone: string;
  role: RoleName;
}

export interface PermissionInfo {
  code: string;
  group: string;
  label: string;
}

export interface RoleInfo {
  role: RoleName;
  displayName: string;
  description: string;
  isDefault: boolean;
  locked: boolean;
  userCount: number;
  permissions: string[];
}

export interface RoleCatalog {
  permissions: PermissionInfo[];
  roles: RoleInfo[];
}

export interface SettingInfo {
  key: string;
  group: string;
  label: string;
  description: string;
  type: "INT" | "BOOLEAN" | "STRING";
  value: string;
  defaultValue: string;
  min: number | null;
  max: number | null;
  updatedBy: string | null;
  updatedAt: string | null;
}

export const adminService = {
  listAccounts: async (q: AccountQuery) =>
    (
      await apiClient.get<AccountPage>("/v1/admin/accounts", {
        params: {
          search: q.search || undefined,
          role: q.role || undefined,
          enabled: q.enabled || undefined,
          page: q.page,
          pageSize: q.pageSize,
        },
      })
    ).data,
  createAccount: async (input: CreateAccountInput) =>
    (await apiClient.post<Account>("/v1/admin/accounts", input)).data,
  updateAccount: async (id: string, input: UpdateAccountInput) =>
    (await apiClient.put<Account>(`/v1/admin/accounts/${id}`, input)).data,
  setAccountEnabled: async (id: string, enabled: boolean) =>
    (
      await apiClient.patch<Account>(`/v1/admin/accounts/${id}/status`, {
        enabled,
      })
    ).data,

  roles: async () =>
    (await apiClient.get<RoleCatalog>("/v1/admin/roles")).data,
  updateRolePermissions: async (role: RoleName, permissions: string[]) =>
    (
      await apiClient.put<RoleInfo>(`/v1/admin/roles/${role}/permissions`, {
        permissions,
      })
    ).data,

  settings: async () =>
    (
      await apiClient.get<{ settings: SettingInfo[] }>("/v1/admin/settings")
    ).data.settings,
  updateSettings: async (values: Record<string, string>) =>
    (
      await apiClient.put<{ settings: SettingInfo[] }>("/v1/admin/settings", {
        values,
      })
    ).data.settings,
};
