// Màn tạo đơn (FR-04): kiểm tra form và dựng yêu cầu gửi lên POST /v1/orders.
// Tên trường khớp OrderRequest ở backend: pickupStationId, dropoffStationId, recipientAddressId, package.
import {
  toPackageInput,
  validatePackageForm,
  type PackageFormErrors,
  type PackageFormValues,
  type PackageInput,
} from './packageService';

export interface OrderFormValues {
  addressId: string | null;
  pickupStationId: string;
  dropoffStationId: string;
  pkg: PackageFormValues;
}

export interface OrderFormErrors {
  address?: string;
  pickup?: string;
  dropoff?: string;
  pkg: PackageFormErrors;
}

export interface OrderRequestBody {
  pickupStationId: string;
  dropoffStationId: string;
  recipientAddressId: string;
  package: PackageInput;
}

export function validateOrderForm(v: OrderFormValues): OrderFormErrors {
  const e: OrderFormErrors = { pkg: validatePackageForm(v.pkg) };
  if (!v.addressId) e.address = 'Cần chọn địa chỉ người nhận';
  if (!v.pickupStationId) e.pickup = 'Cần chọn trạm gửi';
  if (!v.dropoffStationId) e.dropoff = 'Cần chọn trạm nhận';
  else if (v.pickupStationId && v.pickupStationId === v.dropoffStationId)
    e.dropoff = 'Trạm nhận phải khác trạm gửi';
  return e;
}

export function hasOrderErrors(e: OrderFormErrors): boolean {
  return !!(e.address || e.pickup || e.dropoff || Object.keys(e.pkg).length);
}

// Chỉ gọi sau khi validateOrderForm không còn lỗi.
export function buildOrderRequest(v: OrderFormValues): OrderRequestBody {
  return {
    pickupStationId: v.pickupStationId,
    dropoffStationId: v.dropoffStationId,
    recipientAddressId: v.addressId as string,
    package: toPackageInput(v.pkg),
  };
}

// Idempotency-Key: 1-100 ký tự chữ, số, . _ : - (IdempotencyKeys ở backend). UUID thỏa điều kiện.
export function newIdempotencyKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `k-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

// Lỗi từng trường do server trả (field có thể là "package.weightGrams", "pickupStationId"...) về lỗi cấp đơn.
export function mapOrderServerErrors(
  errors: { field: string; message: string }[] | undefined,
): Pick<OrderFormErrors, 'address' | 'pickup' | 'dropoff'> {
  const out: Pick<OrderFormErrors, 'address' | 'pickup' | 'dropoff'> = {};
  for (const { field, message } of errors ?? []) {
    if (field === 'recipientAddressId' && !out.address) out.address = message;
    if (field === 'pickupStationId' && !out.pickup) out.pickup = message;
    if (field === 'dropoffStationId' && !out.dropoff) out.dropoff = message;
  }
  return out;
}
