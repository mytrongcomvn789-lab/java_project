import { useState } from "react";
import type { Address } from "../../lib/addressService";
import AddressPicker from "./AddressPicker";

export default function DeliveryAddressPreview() {
  const [address, setAddress] = useState<Address | null>(null);
  return <div className="customer-area">
    <AddressPicker value={address?.id ?? null} onChange={setAddress} />
    {address && <div className="card customer-panel" role="status">
      <h3>Địa chỉ đã chọn</h3>
      <p>{address.recipientName} · {address.phone}</p>
      <p>{address.addressLine}</p>
      <p className="muted">Địa chỉ đã được chọn cho biểu mẫu. Chưa có yêu cầu giao hàng nào được gửi.</p>
    </div>}
  </div>;
}
