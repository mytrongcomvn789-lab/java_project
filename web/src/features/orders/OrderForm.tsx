import { useEffect, useState, type FormEvent } from 'react';
import AddressPicker from '../addresses/AddressPicker';
import PackageForm from '../packages/PackageForm';
import PackageSummary from '../packages/PackageSummary';
import { ApiRequestError } from '../../lib/apiClient';
import type { Address } from '../../lib/addressService';
import { stationService, type Station } from '../../lib/stationService';
import { explainOrderError } from '../../lib/orderErrors';
import {
  EMPTY_PACKAGE_FORM,
  mapServerErrors,
  toPackageInput,
  type PackageFormValues,
} from '../../lib/packageService';
import {
  buildOrderRequest,
  hasOrderErrors,
  mapOrderServerErrors,
  validateOrderForm,
  type OrderFormErrors,
  type OrderRequestBody,
} from '../../lib/orderForm';

export interface OrderFormInitial {
  pickupStationId: string;
  dropoffStationId: string;
  pkg: PackageFormValues;
}

interface Props {
  initial?: OrderFormInitial;
  // Khi sửa đơn: người nhận hiện tại (đơn chỉ lưu bản chụp, nên khách phải chọn lại địa chỉ để lưu).
  currentRecipient?: { name: string; phone: string; addressLine: string };
  submitLabel: string;
  // Ném lỗi (ApiRequestError) nếu thất bại; thành công thì tự chuyển trang.
  onSubmit: (body: OrderRequestBody) => Promise<void>;
  onCancel?: () => void;
  // Chỉ có khi SỬA đơn: nút "Tải lại đơn" khi gặp lỗi phiên bản/trạng thái.
  onReload?: () => void;
}

// Biểu mẫu đơn dùng chung cho TẠO và SỬA: địa chỉ (3.1) + trạm (3.2) + gói hàng (3.4),
// sau đó bước xem tóm tắt trước khi gửi.
export default function OrderForm({
  initial,
  currentRecipient,
  submitLabel,
  onSubmit,
  onCancel,
  onReload,
}: Props) {
  const [stations, setStations] = useState<Station[] | null>(null);
  const [stationError, setStationError] = useState('');
  const [retry, setRetry] = useState(0);
  const [address, setAddress] = useState<Address | null>(null);
  const [pickupStationId, setPickup] = useState(initial?.pickupStationId ?? '');
  const [dropoffStationId, setDropoff] = useState(initial?.dropoffStationId ?? '');
  const [pkg, setPkg] = useState<PackageFormValues>(initial?.pkg ?? EMPTY_PACKAGE_FORM);
  const [step, setStep] = useState<'edit' | 'review'>('edit');
  const [errors, setErrors] = useState<OrderFormErrors>({ pkg: {} });
  const [formError, setFormError] = useState('');
  const [mustReload, setMustReload] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let active = true;
    setStations(null);
    setStationError('');
    stationService
      .list()
      .then((r) => {
        if (!active) return;
        const open = r.filter((s) => s.status === 'ACTIVE');
        setStations(open);
        // Trạm cũ của đơn có thể đã ngừng hoạt động: bắt chọn lại thay vì để giá trị ẩn.
        setPickup((p) => (open.some((s) => s.id === p) ? p : ''));
        setDropoff((p) => (open.some((s) => s.id === p) ? p : ''));
      })
      .catch((e: Error) => {
        if (active) setStationError(e.message);
      });
    return () => {
      active = false;
    };
  }, [retry]);
    const values = { addressId: address?.id ?? null, pickupStationId, dropoffStationId, pkg };

  function goReview(e: FormEvent) {
    e.preventDefault();
    const found = validateOrderForm(values);
    setErrors(found);
    setFormError('');
    if (hasOrderErrors(found)) {
      setFormError('Vui lòng kiểm tra lại các mục báo lỗi.');
      return;
    }
    setStep('review');
  }

  async function confirm() {
    if (submitting || mustReload) return;
    setSubmitting(true);
    setFormError('');
    try {
      await onSubmit(buildOrderRequest(values));
    } catch (err) {
      const api = err instanceof ApiRequestError ? err : null;
      const fieldErrors = {
        ...mapOrderServerErrors(api?.errors),
        pkg: mapServerErrors(api?.errors),
      };
      setErrors(fieldErrors);
      if (onReload) {
        const info = explainOrderError(err);
        setFormError(info.message);
        setMustReload(info.mustReload);
      } else {
        setFormError(
          (err instanceof Error ? err.message : 'Không gửi được yêu cầu, vui lòng thử lại.') +
            (api?.status ? '' : ' Bạn có thể gửi lại, hệ thống sẽ không tạo đơn trùng.'),
        );
      }
      if (hasOrderErrors(fieldErrors)) setStep('edit');
    } finally {
      setSubmitting(false);
    }
  }

  function stationSelect(
    id: string,
    label: string,
    value: string,
    set: (v: string) => void,
    error?: string,
  ) {
    return (
      <label className="customer-field" htmlFor={id}>
        <span>
          {label} <span aria-hidden="true">*</span>
        </span>
        <select
          id={id}
          value={value}
          onChange={(e) => set(e.target.value)}
          disabled={!stations}
          aria-invalid={!!error}
          aria-describedby={error ? `${id}-error` : undefined}
        >
          <option value="">— Chọn trạm —</option>
          {stations?.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
        {error && (
          <span id={`${id}-error`} className="customer-error">
            {error}
          </span>
        )}
      </label>
    );
  }
    if (step === 'review') {
    const stationName = (id: string) => stations?.find((s) => s.id === id)?.name ?? id;
    return (
      <div className="customer-area">
        <section className="card customer-panel" aria-label="Xác nhận yêu cầu giao hàng">
          <h3>Kiểm tra lại trước khi gửi</h3>
          <p>
            <strong>Người nhận:</strong> {address?.recipientName} · {address?.phone}
            <br />
            {address?.addressLine}
          </p>
          <p>
            <strong>Trạm gửi:</strong> {stationName(pickupStationId)}
            <br />
            <strong>Trạm nhận:</strong> {stationName(dropoffStationId)}
          </p>
        </section>
        <PackageSummary value={toPackageInput(pkg)} />
        {formError && (
          <div role="alert" className="customer-error">
            {formError}
          </div>
        )}
        <div className="customer-actions">
          <button
            type="button"
            className="customer-primary"
            disabled={submitting || mustReload}
            onClick={() => void confirm()}
          >
            {submitting ? 'Đang gửi…' : submitLabel}
          </button>
          <button
            type="button"
            disabled={submitting}
            onClick={() => {
              setStep('edit');
              setFormError('');
            }}
          >
            Quay lại chỉnh sửa
          </button>
          {mustReload && onReload && (
            <button type="button" onClick={onReload}>
              Tải lại đơn
            </button>
          )}
        </div>
      </div>
    );
  }
    return (
    <form className="customer-area" onSubmit={goReview} noValidate>
      <AddressPicker value={address?.id ?? null} onChange={setAddress} />
      {currentRecipient && (
        <p className="muted">
          Người nhận hiện tại của đơn: {currentRecipient.name} · {currentRecipient.phone} ·{' '}
          {currentRecipient.addressLine}. Hãy chọn lại địa chỉ để lưu thay đổi.
        </p>
      )}
      {errors.address && (
        <p role="alert" className="customer-error">
          {errors.address}
        </p>
      )}

      <section className="card customer-panel" aria-label="Chọn trạm gửi và trạm nhận">
        <h3>Trạm gửi và trạm nhận</h3>
        {stationError ? (
          <div role="alert">
            {stationError}{' '}
            <button type="button" onClick={() => setRetry((n) => n + 1)}>
              Thử lại
            </button>
          </div>
        ) : !stations ? (
          <p role="status">Đang tải danh sách trạm…</p>
        ) : stations.length === 0 ? (
          <p role="alert">Chưa có trạm nào đang hoạt động. Hãy liên hệ quản trị viên.</p>
        ) : null}
        <div className="customer-form-grid">
          {stationSelect('order-pickup', 'Trạm gửi', pickupStationId, setPickup, errors.pickup)}
          {stationSelect('order-dropoff', 'Trạm nhận', dropoffStationId, setDropoff, errors.dropoff)}
        </div>
      </section>

      <div className="card customer-panel">
        <PackageForm value={pkg} onChange={setPkg} errors={errors.pkg} idPrefix="order-pkg" />
      </div>

      {formError && (
        <div role="alert" className="customer-error">
          {formError}
        </div>
      )}
      <div className="customer-actions">
        <button type="submit" className="customer-primary">
          Xem lại trước khi gửi
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel}>
            Hủy chỉnh sửa
          </button>
        )}
      </div>
    </form>
  );
}