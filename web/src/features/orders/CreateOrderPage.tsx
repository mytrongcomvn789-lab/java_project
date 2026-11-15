import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import PageHeader from '../../components/ui/PageHeader';
import { useAuth } from '../auth/AuthContext';
import AddressPicker from '../addresses/AddressPicker';
import PackageForm from '../packages/PackageForm';
import { ApiRequestError } from '../../lib/apiClient';
import { orderService } from '../../lib/orderService';
import { stationService, type Station } from '../../lib/stationService';
import {
  EMPTY_PACKAGE_FORM,
  mapServerErrors,
  type PackageFormValues,
} from '../../lib/packageService';
import {
  buildOrderRequest,
  hasOrderErrors,
  mapOrderServerErrors,
  newIdempotencyKey,
  validateOrderForm,
  type OrderFormErrors,
  type OrderRequestBody,
} from '../../lib/orderForm';

// FR-04: khách tạo yêu cầu giao hàng = chọn địa chỉ (3.1) + trạm gửi/nhận (3.2) + gói hàng (3.4) rồi gửi POST /v1/orders (3.6).
export default function CreateOrderPage() {
  const { user, isDemo } = useAuth();
  const header = (
    <PageHeader
      crumbs={['Trang chủ', 'Tạo yêu cầu giao hàng']}
      title="Tạo yêu cầu giao hàng"
      subtitle="Gửi yêu cầu giao hàng mới bằng drone."
      code="FR-04"
    />
  );
  if (isDemo)
    return (
      <>
        {header}
        <div className="card customer-notice" role="status">
          Hãy đăng nhập bằng tài khoản thật để tạo yêu cầu giao hàng.
        </div>
      </>
    );
  if (user?.role !== 'CUSTOMER')
    return (
      <>
        {header}
        <div className="card customer-notice" role="alert">
          Chỉ tài khoản khách hàng mới tạo được yêu cầu giao hàng.
        </div>
      </>
    );
  return (
    <>
      {header}
      <Form />
    </>
  );
}

function Form() {
  const navigate = useNavigate();
  const [stations, setStations] = useState<Station[] | null>(null);
  const [stationError, setStationError] = useState('');
  const [retry, setRetry] = useState(0);
  const [addressId, setAddressId] = useState<string | null>(null);
  const [pickupStationId, setPickup] = useState('');
  const [dropoffStationId, setDropoff] = useState('');
  const [pkg, setPkg] = useState<PackageFormValues>(EMPTY_PACKAGE_FORM);
  const [errors, setErrors] = useState<OrderFormErrors>({ pkg: {} });
  const [formError, setFormError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  // Cùng nội dung -> cùng key (gửi lại sau lỗi mạng không tạo đơn thứ hai); đổi nội dung -> key mới
  // (cùng key khác nội dung bị server từ chối IDEMPOTENCY_KEY_CONFLICT).
  const attempt = useRef<{ key: string; body: string } | null>(null);

  useEffect(() => {
    let active = true;
    setStations(null);
    setStationError('');
    stationService
      .list()
      .then((r) => {
        if (active) setStations(r.filter((s) => s.status === 'ACTIVE'));
      })
      .catch((e: Error) => {
        if (active) setStationError(e.message);
      });
    return () => {
      active = false;
    };
  }, [retry]);

  function keyFor(body: OrderRequestBody): string {
    const json = JSON.stringify(body);
    if (!attempt.current || attempt.current.body !== json)
      attempt.current = { key: newIdempotencyKey(), body: json };
    return attempt.current.key;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (submitting) return;
    const found = validateOrderForm({
      addressId,
      pickupStationId,
      dropoffStationId,
      pkg,
    });
    setErrors(found);
    setFormError('');
    if (hasOrderErrors(found)) {
      setFormError('Vui lòng kiểm tra lại các mục báo lỗi.');
      return;
    }
    const body = buildOrderRequest({
      addressId,
      pickupStationId,
      dropoffStationId,
      pkg,
    });
    setSubmitting(true);
    try {
      const order = await orderService.create(body, keyFor(body));
      navigate(`/deliveries/${order.id}`);
    } catch (err) {
      const apiErr = err instanceof ApiRequestError ? err : null;
      const mapped = {
        ...mapOrderServerErrors(apiErr?.errors),
        pkg: mapServerErrors(apiErr?.errors),
      };
      setErrors(mapped);
      // Lỗi giới hạn gói hàng (quá nặng, quá to, số ảnh...) chỉ có message, không có tên trường.
      setFormError(
        err instanceof Error
          ? err.message
          : 'Không gửi được yêu cầu, vui lòng thử lại.',
      );
    } finally {
      setSubmitting(false);
    }
  }

  const stationSelect = (
    id: string,
    label: string,
    value: string,
    set: (v: string) => void,
    error?: string,
  ) => (
    <label className="customer-field" htmlFor={id}>
      <span>
        {label} <span aria-hidden="true">*</span>
      </span>
      <select
        id={id}
        value={value}
        onChange={(e) => set(e.target.value)}
        disabled={!stations || submitting}
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

  return (
    <form className="customer-area" onSubmit={submit} noValidate>
      <AddressPicker
        value={addressId}
        onChange={(a) => setAddressId(a?.id ?? null)}
      />
      {errors.address && (
        <p role="alert" className="customer-error">
          {errors.address}
        </p>
      )}

      <section
        className="card customer-panel"
        aria-label="Chọn trạm gửi và trạm nhận"
      >
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
          <p role="alert">
            Chưa có trạm nào đang hoạt động. Hãy liên hệ quản trị viên.
          </p>
        ) : null}
        <div className="customer-form-grid">
          {stationSelect(
            'order-pickup',
            'Trạm gửi',
            pickupStationId,
            setPickup,
            errors.pickup,
          )}
          {stationSelect(
            'order-dropoff',
            'Trạm nhận',
            dropoffStationId,
            setDropoff,
            errors.dropoff,
          )}
        </div>
      </section>

      <div className="card customer-panel">
        <PackageForm
          value={pkg}
          onChange={setPkg}
          errors={errors.pkg}
          disabled={submitting}
          idPrefix="order-pkg"
        />
      </div>

      {formError && (
        <div role="alert" className="customer-error">
          {formError}
        </div>
      )}
      <div className="customer-actions">
        <button
          type="submit"
          className="customer-primary"
          disabled={submitting}
        >
          {submitting ? 'Đang gửi…' : 'Gửi yêu cầu giao hàng'}
        </button>
      </div>
    </form>
  );
}
