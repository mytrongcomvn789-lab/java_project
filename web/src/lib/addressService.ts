import apiClient from "./apiClient";

export interface AddressInput {
  label: string;
  recipientName: string;
  phone: string;
  addressLine: string;
  latitude: number;
  longitude: number;
}
export interface Address extends AddressInput { id: string }
export interface AddressPage {
  items: Address[];
  page: number;
  pageSize: number;
  total: number;
}
export const addressService = {
  list: async (page = 1, pageSize = 20) =>
    (await apiClient.get<AddressPage>("/v1/addresses", { params: { page, pageSize } })).data,
  create: async (input: AddressInput) =>
    (await apiClient.post<Address>("/v1/addresses", input)).data,
  update: async (id: string, input: AddressInput) =>
    (await apiClient.put<Address>(`/v1/addresses/${id}`, input)).data,
  remove: async (id: string) => { await apiClient.delete(`/v1/addresses/${id}`); },
};
