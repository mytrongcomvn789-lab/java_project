import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { addressService, type Address, type AddressPage } from "../../lib/addressService";
import CustomerGate from "./CustomerGate";

interface Props { value: string | null; onChange: (address: Address | null) => void }

// Returns an addressId to the caller; task 3.6 must validate ownership again on the server.
export default function AddressPicker(props: Props) {
  return <CustomerGate><Picker {...props} /></CustomerGate>;
}
function Picker({ value, onChange }: Props) {
  const [page, setPage] = useState(1);
  const [data, setData] = useState<AddressPage | null>(null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    setData(null); setError("");
    addressService.list(page, 10).then(result => { if (active) setData(result); })
      .catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [page, retry]);
  return <section className="card customer-panel" aria-label="Chọn địa chỉ giao hàng">
    <h3>Chọn địa chỉ giao hàng</h3>
    <p className="muted">Chọn thông tin người nhận từ sổ địa chỉ của bạn.</p>
    {error ? <div role="alert">{error} <button type="button" onClick={() => setRetry(n => n + 1)}>Thử lại</button></div>
      : !data ? <p role="status">Đang tải địa chỉ…</p>
      : <>
        {!data.total && <p>Chưa có địa chỉ. <Link to="/addresses">Thêm địa chỉ</Link></p>}
        <div className="address-options">
          {data.items.map(address => <label key={address.id} className="address-option">
            <input type="radio" name="deliveryAddress" value={address.id} checked={value === address.id}
              onChange={() => onChange(address)} />
            <span><strong>{address.label} — {address.recipientName}</strong><br />
              {address.phone}<br />{address.addressLine}</span>
          </label>)}
        </div>
        {data.total > data.pageSize && <div className="customer-actions">
          <button type="button" disabled={page === 1} onClick={() => setPage(p => p - 1)}>Trang trước</button>
          <span>Trang {page} / {Math.ceil(data.total / data.pageSize)}</span>
          <button type="button" disabled={page * data.pageSize >= data.total} onClick={() => setPage(p => p + 1)}>Trang sau</button>
        </div>}
      </>}
    {value && <button type="button" onClick={() => onChange(null)}>Bỏ chọn địa chỉ</button>}
  </section>;
}
