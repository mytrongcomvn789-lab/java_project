import ImageUploader from "../../components/ui/ImageUploader";
import SecureImage from "../../components/ui/SecureImage";
import type { PackageFormErrors, PackageFormValues, PackageLimits } from "../../lib/packageService";

interface Props {
  value: PackageFormValues;
  onChange: (next: PackageFormValues) => void;
  errors?: PackageFormErrors;
  limits?: PackageLimits; // chỉ để báo lỗi sớm / gợi ý; server vẫn là nơi quyết định
  disabled?: boolean;
  idPrefix?: string;
}

// Biểu mẫu gói hàng dùng lại được: nhúng vào form tạo/sửa đơn (3.6). Không tự gọi API đơn hàng.
// Ảnh: tải qua ImageUploader (purpose PACKAGE_PHOTO, 3.3), chỉ giữ fileId.
export default function PackageForm({ value, onChange, errors = {}, limits = {}, disabled, idPrefix = "pkg" }: Props) {
  const set = <K extends keyof PackageFormValues>(key: K, v: PackageFormValues[K]) =>
    onChange({ ...value, [key]: v });
  const atMaxPhotos = !!limits.maxPhotos && value.photoFileIds.length >= limits.maxPhotos;

  function input(key: "weightKg" | "lengthCm" | "widthCm" | "heightCm", label: string, hint?: string) {
    const id = `${idPrefix}-${key}`;
    return (
      <label className="customer-field" htmlFor={id}>
        <span>{label} <span aria-hidden="true">*</span></span>
        <input id={id} name={key} type="number" inputMode="decimal" min="0" step="any" required
          value={value[key]} disabled={disabled} aria-invalid={!!errors[key]}
          aria-describedby={errors[key] ? `${id}-error` : undefined}
          onChange={(e) => set(key, e.target.value)} />
        {hint && <span className="muted">{hint}</span>}
        {errors[key] && <span id={`${id}-error`} className="customer-error">{errors[key]}</span>}
      </label>
    );
  }

  return (
    <fieldset className="customer-area" disabled={disabled} style={{ border: 0, padding: 0, margin: 0 }}>
      <legend><strong>Thông tin gói hàng</strong></legend>
      <label className="customer-field" htmlFor={`${idPrefix}-description`}>
        <span>Mô tả gói hàng <span aria-hidden="true">*</span></span>
        <input id={`${idPrefix}-description`} name="description" type="text" maxLength={500} required
          value={value.description} aria-invalid={!!errors.description}
          aria-describedby={errors.description ? `${idPrefix}-description-error` : undefined}
          placeholder="Ví dụ: Hộp thuốc, tài liệu, linh kiện…"
          onChange={(e) => set("description", e.target.value)} />
        {errors.description && <span id={`${idPrefix}-description-error`} className="customer-error">{errors.description}</span>}
      </label>

      <div className="customer-form-grid">
        {input("weightKg", "Trọng lượng (kg)", limits.maxWeightGrams ? `Tối đa ${limits.maxWeightGrams / 1000} kg` : undefined)}
        {input("lengthCm", "Chiều dài (cm)", limits.maxLengthCm ? `Tối đa ${limits.maxLengthCm} cm` : undefined)}
        {input("widthCm", "Chiều rộng (cm)", limits.maxWidthCm ? `Tối đa ${limits.maxWidthCm} cm` : undefined)}
        {input("heightCm", "Chiều cao (cm)", limits.maxHeightCm ? `Tối đa ${limits.maxHeightCm} cm` : undefined)}
      </div>

      <div>
        <p><strong>Ảnh gói hàng</strong>{limits.maxPhotos ? <span className="muted"> (tối đa {limits.maxPhotos} ảnh)</span> : null}</p>
        {value.photoFileIds.length > 0 && (
          <ul style={{ display: "flex", gap: 12, flexWrap: "wrap", listStyle: "none", padding: 0 }}>
            {value.photoFileIds.map((fileId, index) => (
              <li key={fileId} style={{ width: 120 }}>
                <SecureImage fileId={fileId} alt={`Ảnh gói hàng ${index + 1}`} className="package-thumb" />
                <button type="button" aria-label={`Xóa ảnh gói hàng ${index + 1}`}
                  onClick={() => set("photoFileIds", value.photoFileIds.filter((id) => id !== fileId))}>Xóa ảnh</button>
              </li>
            ))}
          </ul>
        )}
        {!atMaxPhotos && (
          // key đổi sau mỗi lần tải xong để ImageUploader về trạng thái trống, sẵn sàng chọn ảnh tiếp theo.
          <ImageUploader key={value.photoFileIds.length} purpose="PACKAGE_PHOTO"
            onUploaded={(id) => set("photoFileIds", [...value.photoFileIds, id])} />
        )}
        {errors.photoFileIds && <p role="alert" className="customer-error">{errors.photoFileIds}</p>}
      </div>
    </fieldset>
  );
}
