import { useState } from 'react';
import PageHeader from '../../components/ui/PageHeader';
import CustomerGate from '../addresses/CustomerGate';
import PackageForm from './PackageForm';
import PackageSummary from './PackageSummary';
import {
  EMPTY_PACKAGE_FORM,
  toPackageInput,
  validatePackageForm,
  type PackageFormErrors,
  type PackageFormValues,
  type PackageInput,
} from '../../lib/packageService';
import type { PackageLimits } from '../../lib/packageService';
const DEMO_LIMITS: PackageLimits = {
  maxWeightGrams: 5000,
  maxLengthCm: 50,
  maxWidthCm: 50,
  maxHeightCm: 50,
  minPhotos: 0,
  maxPhotos: 3,
};
// Trang demo CHỈ có ở chế độ dev (npm run dev): cho Khách hàng thử biểu mẫu + tải ảnh khi task 3.6 chưa có
// màn tạo đơn. Không gọi API đơn hàng; hiển thị đúng payload mà 3.6 sẽ gửi trong trường `package`.
export default function PackageFormPreviewPage() {
  return (
    <>
      <PageHeader
        title="Xem thử biểu mẫu gói hàng"
        subtitle="Chỉ dùng khi phát triển (task 3.4)."
        crumbs={['Trang chủ', 'Biểu mẫu gói hàng']}
      />
      <CustomerGate>
        <Preview />
      </CustomerGate>
    </>
  );
}

function Preview() {
  const [values, setValues] = useState<PackageFormValues>(EMPTY_PACKAGE_FORM);
  const [errors, setErrors] = useState<PackageFormErrors>({});
  const [payload, setPayload] = useState<PackageInput | null>(null);

  function check() {
    const found = validatePackageForm(values, DEMO_LIMITS);
    setErrors(found);
    setPayload(Object.keys(found).length ? null : toPackageInput(values));
  }

  return (
    <div className="customer-area">
      <div className="card customer-panel">
        <PackageForm
          value={values}
          onChange={setValues}
          errors={errors}
          limits={DEMO_LIMITS}
        />
        <div className="customer-actions">
          <button type="button" className="customer-primary" onClick={check}>
            Kiểm tra dữ liệu
          </button>
          <button
            type="button"
            onClick={() => {
              setValues(EMPTY_PACKAGE_FORM);
              setErrors({});
              setPayload(null);
            }}
          >
            Làm lại
          </button>
        </div>
      </div>
      {payload && (
        <>
          <PackageSummary value={payload} />
          <div className="card customer-panel">
            <h3>Payload gửi trong yêu cầu đơn</h3>
            <pre>{JSON.stringify({ package: payload }, null, 2)}</pre>
          </div>
        </>
      )}
    </div>
  );
}
