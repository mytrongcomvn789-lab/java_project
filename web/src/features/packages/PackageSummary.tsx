import SecureImage from "../../components/ui/SecureImage";
import { formatWeight, type PackageView } from "../../lib/packageService";

// Hiển thị chỉ-đọc gói hàng trong chi tiết đơn (3.7) và màn xác nhận trước khi gửi (3.6).
export default function PackageSummary({ value }: { value: PackageView }) {
  return (
    <section className="card customer-panel" aria-label="Thông tin gói hàng">
      <h3>Gói hàng</h3>
      <p>{value.description}</p>
      <p className="muted">
        Trọng lượng {formatWeight(value.weightGrams)} · Kích thước {value.lengthCm} × {value.widthCm} × {value.heightCm} cm
      </p>
      {value.photoFileIds.length === 0 ? (
        <p className="muted">Chưa có ảnh gói hàng.</p>
      ) : (
        <ul style={{ display: "flex", gap: 12, flexWrap: "wrap", listStyle: "none", padding: 0 }}>
          {value.photoFileIds.map((id, i) => (
            <li key={id} style={{ width: 120 }}>
              <SecureImage fileId={id} alt={`Ảnh gói hàng ${i + 1}`} className="package-thumb" />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
