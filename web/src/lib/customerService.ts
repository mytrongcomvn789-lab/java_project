import apiClient from "./apiClient";

export interface Customer {
  id: string;
  email: string;
  fullName: string;
  phone: string | null;
  enabled: boolean;
  createdAt: string;
}

export interface CustomerPage {
  items: Customer[];
  page: number;
  pageSize: number;
  total: number;
}

export const customerService = {
  list: async (search: string, page: number, pageSize = 10) =>
    (await apiClient.get<CustomerPage>("/v1/customers", { params: { search, page, pageSize } })).data,
  update: async (id: string, fullName: string, phone: string) =>
    (await apiClient.patch<Customer>(`/v1/customers/${id}`, { fullName, phone })).data,
};
