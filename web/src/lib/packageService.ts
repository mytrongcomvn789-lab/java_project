// Task 3.4: kiểu dữ liệu + kiểm tra form gói hàng. Gói là thành phần của đơn (v1): KHÔNG có API /packages;
// task 3.6 gửi PackageInput trong trường `package` của yêu cầu tạo/sửa đơn.
// Giới hạn (trọng lượng/kích thước/số ảnh) do nhóm chốt và server kiểm tra; ở đây chỉ kiểm tra bắt buộc/dương.
// Host có thể truyền `limits` (nếu sau này server công bố) để báo lỗi sớm; không có thì không tự đoán.

export interface PackageInput {
  description: string;
  weightGrams: number;
  lengthCm: number;
  widthCm: number;
  heightCm: number;
  photoFileIds: string[];
}

export type PackageView = PackageInput; // server chỉ trả fileId ảnh, không trả URL

export interface PackageLimits {
  maxWeightGrams?: number;
  maxLengthCm?: number;
  maxWidthCm?: number;
  maxHeightCm?: number;
  minPhotos?: number;
  maxPhotos?: number;
}

// Giá trị trong ô nhập (chuỗi) để người dùng gõ dở vẫn giữ được; trọng lượng nhập theo kg, gửi đi theo gram.
export interface PackageFormValues {
  description: string;
  weightKg: string;
  lengthCm: string;
  widthCm: string;
  heightCm: string;
  photoFileIds: string[];
}

export type PackageFormErrors = Partial<Record<keyof PackageFormValues, string>>;

export const EMPTY_PACKAGE_FORM: PackageFormValues = {
  description: "",
  weightKg: "",
  lengthCm: "",
  widthCm: "",
  heightCm: "",
  photoFileIds: [],
};

function num(text: string): number {
  return text.trim() === "" ? NaN : Number(text.replace(",", "."));
}

export function validatePackageForm(
  v: PackageFormValues,
  limits: PackageLimits = {},
): PackageFormErrors {
  const e: PackageFormErrors = {};
  if (!v.description.trim()) e.description = "Mô tả gói hàng không được để trống";
  else if (v.description.trim().length > 500) e.description = "Mô tả gói hàng tối đa 500 ký tự";

  const grams = Math.round(num(v.weightKg) * 1000);
  if (!Number.isFinite(grams) || grams < 1) e.weightKg = "Cần nhập trọng lượng lớn hơn 0";
  else if (limits.maxWeightGrams && grams > limits.maxWeightGrams)
    e.weightKg = `Trọng lượng tối đa ${limits.maxWeightGrams / 1000} kg`;

  const dims: [keyof PackageFormValues, string, number | undefined][] = [
    ["lengthCm", "chiều dài", limits.maxLengthCm],
    ["widthCm", "chiều rộng", limits.maxWidthCm],
    ["heightCm", "chiều cao", limits.maxHeightCm],
  ];
  for (const [key, label, max] of dims) {
    const n = num(v[key] as string);
    if (!Number.isFinite(n) || n <= 0) e[key] = `Cần nhập ${label} lớn hơn 0`;
    else if (max && n > max) e[key] = `${label[0].toUpperCase()}${label.slice(1)} tối đa ${max} cm`;
  }

  if (limits.minPhotos && v.photoFileIds.length < limits.minPhotos)
    e.photoFileIds = `Cần ít nhất ${limits.minPhotos} ảnh gói hàng`;
  if (limits.maxPhotos && v.photoFileIds.length > limits.maxPhotos)
    e.photoFileIds = `Tối đa ${limits.maxPhotos} ảnh gói hàng`;
  return e;
}

// Chỉ gọi sau khi validatePackageForm không còn lỗi.
export function toPackageInput(v: PackageFormValues): PackageInput {
  return {
    description: v.description.trim(),
    weightGrams: Math.round(num(v.weightKg) * 1000),
    lengthCm: num(v.lengthCm),
    widthCm: num(v.widthCm),
    heightCm: num(v.heightCm),
    photoFileIds: [...v.photoFileIds],
  };
}

// Điền lại form khi sửa đơn (3.6).
export function fromPackageView(p: PackageView): PackageFormValues {
  return {
    description: p.description,
    weightKg: String(p.weightGrams / 1000),
    lengthCm: String(p.lengthCm),
    widthCm: String(p.widthCm),
    heightCm: String(p.heightCm),
    photoFileIds: [...p.photoFileIds],
  };
}

export function formatWeight(grams: number): string {
  return grams >= 1000 ? `${grams / 1000} kg` : `${grams} g`;
}

// Lỗi từ server (ApiRequestError.errors có field dạng "package.weightGrams" hoặc "weightGrams") → lỗi theo ô nhập.
const SERVER_FIELD_MAP: Record<string, keyof PackageFormValues> = {
  description: "description",
  weightGrams: "weightKg",
  lengthCm: "lengthCm",
  widthCm: "widthCm",
  heightCm: "heightCm",
  photoFileIds: "photoFileIds",
};

export function mapServerErrors(errors: { field: string; message: string }[] | undefined): PackageFormErrors {
  const out: PackageFormErrors = {};
  for (const { field, message } of errors ?? []) {
    const base = field.split(".").pop()?.replace(/\[\d+\]$/, "") ?? "";
    const key = SERVER_FIELD_MAP[base];
    if (key && !out[key]) out[key] = message;
  }
  return out;
}
