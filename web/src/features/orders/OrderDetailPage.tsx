import { useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import PageHeader from "../../components/ui/PageHeader";
import { ApiRequestError } from "../../lib/apiClient";
import {
  orderService,
  ORDER_STATUS_OPTIONS,
  type OrderDetail,
} from "../../lib/orderService";
import { stationService, type Station } from "../../lib/stationService";
import { formatWeight } from "../../lib/packageService";
import SecureImage from "../../components/ui/SecureImage";
import { failureReasonLabel } from "../../lib/failureService";
import { useAuth } from "../auth/AuthContext";
import OrderReviewActions from "./OrderReviewActions";
import OrderCustomerActions from "./OrderCustomerActions";
import OrderScheduleActions from "./OrderScheduleActions";
import OrderTrackingPanel from "./OrderTrackingPanel";
import OrderFailurePanel from "./OrderFailurePanel";
import PickupCodePanel from "./PickupCodePanel";
import ConfirmDeliveryPanel from "./ConfirmDeliveryPanel";
import DeliveryEvidencePanel from "./DeliveryEvidencePanel";

const when = (v: string) => new Date(v).toLocaleString("vi-VN");

const STATUS_NAMES = new Map(ORDER_STATUS_OPTIONS);
const statusName = (s: string | null) => (s ? (STATUS_NAMES.get(s) ?? s) : "—");

const ROLE_NAMES: Record<string, string> = {
  CUSTOMER: "Khách hàng",
  DISPATCHER: "Điều phối viên",
  STATION_OPERATOR: "Nhân viên trạm",
  LOGISTICS_MANAGER: "Quản lý hậu cần",
  SYSTEM_ADMIN: "Quản trị viên",
};

/** Tên trạm từ danh sách trạm; chưa tải được thì rút gọn UUID thay vì hiện cả chuỗi dài. */
const stationLabel = (stations: Map<string, Station>, id: string) =>
  stations.get(id)?.name ?? `${id.slice(0, 8)}…`;

export default function OrderDetailPage() {
  const { id = "" } = useParams();
  const { isDemo, user } = useAuth();
  const location = useLocation();
  const [notice, setNotice] = useState(
    () => (location.state as { notice?: string } | null)?.notice ?? "",
  );
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [stations, setStations] = useState<Map<string, Station>>(new Map());

  // Tên trạm chỉ để hiển thị; lỗi thì vẫn xem được đơn với mã rút gọn.
  useEffect(() => {
    if (isDemo) return;
    let active = true;
    stationService
      .list()
      .then((list) => {
        if (active) setStations(new Map(list.map((s) => [s.id, s])));
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [isDemo]);

  useEffect(() => {
    if (isDemo) return;
    let active = true;
    // Tải lại cùng một đơn thì giữ nội dung cũ, không xóa trắng cả trang (tránh nhấp nháy, mất thông báo).
    setOrder((prev) => (prev && prev.id === id ? prev : null));
    setError("");
    orderService
      .get(id)
      .then((o) => {
        if (active) setOrder(o);
      })
      .catch((e) => {
        if (!active) return;
        setError(
          e instanceof ApiRequestError && e.status === 404
            ? "Không tìm thấy đơn hoặc bạn không có quyền xem đơn này."
            : "Không tải được chi tiết đơn.",
        );
      });
    return () => {
      active = false;
    };
  }, [id, retry, isDemo]);

  return (
    <>
      <PageHeader
        title="Chi tiết đơn"
        crumbs={["Trang chủ", "Đơn giao hàng", "Chi tiết"]}
      />
      <div className="customer-area">
        <Link to="/deliveries">← Về danh sách đơn</Link>
        {isDemo ? (
          <div className="card customer-notice">
            Hãy đăng nhập bằng tài khoản thật để xem đơn.
          </div>
        ) : error ? (
          <div className="card customer-panel" role="alert">
            {error}{" "}
            <button type="button" onClick={() => setRetry((n) => n + 1)}>
              Thử lại
            </button>
          </div>
        ) : !order ? (
          <p role="status">Đang tải chi tiết đơn…</p>
        ) : (
          <>
            {notice && <p role="status">{notice}</p>}
            {user?.role === "DISPATCHER" && order.status === "PENDING" && (
              <OrderReviewActions
                key={`review:${order.id}:${order.version}`}
                order={order}
                onReviewed={(updated, message) => {
                  setOrder(updated);
                  setNotice(message);
                }}
                onReload={() => setRetry((n) => n + 1)}
              />
            )}
            {user?.role === "DISPATCHER" &&
              (order.status === "APPROVED" || order.status === "FAILED") && (
                <OrderScheduleActions
                  key={`schedule:${order.id}:${order.version}`}
                  order={order}
                  onScheduled={(updated, message) => {
                    setOrder(updated);
                    setNotice(message);
                  }}
                  onReload={() => setRetry((n) => n + 1)}
                />
              )}
            {user?.role === "CUSTOMER" && order.status === "PENDING" && (
              <OrderCustomerActions
                key={`customer:${order.id}:${order.version}`}
                order={order}
                onChanged={(updated, message) => {
                  setOrder(updated);
                  setNotice(message);
                }}
                onReload={() => setRetry((n) => n + 1)}
              />
            )}
                        {user?.role === "CUSTOMER" && order.status === "AWAITING_PICKUP" && (
              <PickupCodePanel orderId={order.id} />
            )}
            {user?.role === "STATION_OPERATOR" &&
              order.status === "AWAITING_PICKUP" && (
                <ConfirmDeliveryPanel
                  key={`confirm:${order.id}:${order.version}`}
                  order={order}
                  onConfirmed={(message) => {
                    setNotice(message);
                    setRetry((n) => n + 1);
                  }}
                  onReload={() => setRetry((n) => n + 1)}
                />
              )}
            {order.status === "DELIVERED" && (
              <DeliveryEvidencePanel orderId={order.id} />
            )}
            <OrderFailurePanel
              key={`failure:${order.id}:${order.version}`}
              order={order}
              onChanged={(updated, message) => {
                setOrder(updated);
                setNotice(message);
              }}
              onReload={() => setRetry((n) => n + 1)}
            />
            <OrderTrackingPanel
              orderId={order.id}
              onVersionChange={() => setRetry((n) => n + 1)}
            />
            <section className="card customer-panel order-info">
              <header className="order-info-head">
                <div>
                  <h3>Đơn {order.id.slice(0, 8)}…</h3>
                  <small className="muted" title={order.id}>
                    {order.id}
                  </small>
                </div>
                <span className={`badge st-${order.status.toLowerCase()}`}>
                  {order.statusLabel}
                </span>
              </header>
              <div className="order-info-grid">
                <div>
                  <h4>Hành trình</h4>
                  <p>
                    <span className="muted">Trạm gửi</span>
                    <br />
                    <strong>{stationLabel(stations, order.pickupStationId)}</strong>
                    {stations.get(order.pickupStationId)?.address && (
                      <small className="muted">
                        <br />
                        {stations.get(order.pickupStationId)?.address}
                      </small>
                    )}
                  </p>
                  <p>
                    <span className="muted">Trạm nhận</span>
                    <br />
                    <strong>{stationLabel(stations, order.dropoffStationId)}</strong>
                    {stations.get(order.dropoffStationId)?.address && (
                      <small className="muted">
                        <br />
                        {stations.get(order.dropoffStationId)?.address}
                      </small>
                    )}
                  </p>
                  <p className="muted">
                    Tạo lúc {when(order.createdAt)}
                    <br />
                    Cập nhật {when(order.updatedAt)}
                  </p>
                </div>
                <div>
                  <h4>Người nhận</h4>
                  <p>
                    <strong>{order.recipient.name}</strong>
                    <br />
                    {order.recipient.phone}
                  </p>
                  <p>{order.recipient.addressLine}</p>
                </div>
                {order.package && (
                  <div className="order-info-wide">
                    <h4>Gói hàng</h4>
                    <p>
                      {order.package.description}
                      <br />
                      <span className="muted">
                        {formatWeight(order.package.weightGrams)} · {order.package.lengthCm} ×{" "}
                        {order.package.widthCm} × {order.package.heightCm} cm
                      </span>
                    </p>
                    {order.package.photoFileIds.length > 0 && (
                      <ul className="order-photos">
                        {order.package.photoFileIds.map((fid, i) => (
                          <li key={fid}>
                            <SecureImage
                              fileId={fid}
                              alt={`Ảnh gói hàng ${i + 1}`}
                              className="package-thumb"
                            />
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </div>
            </section>
            <section className="card customer-panel">
              <h3>Lịch sử trạng thái</h3>
              <ol className="order-timeline">
                {[...order.history].reverse().map((h) => (
                  <li key={h.orderVersion}>
                    <div className="order-timeline-head">
                      <strong>
                        {h.fromStatus
                          ? `${statusName(h.fromStatus)} → ${statusName(h.toStatus)}`
                          : statusName(h.toStatus)}
                      </strong>
                      <small className="muted">{when(h.occurredAt)}</small>
                    </div>
                    <small className="muted">
                      {h.action ?? "Tạo đơn"} ·{" "}
                      {h.actorRole ? (ROLE_NAMES[h.actorRole] ?? h.actorRole) : "Hệ thống"}
                    </small>
                    {(h.reasonCode || h.reason) && (
                      <p className="order-timeline-reason">
                        {h.reasonCode && <strong>{failureReasonLabel(h.reasonCode)} </strong>}
                        {h.reason ?? ""}
                      </p>
                    )}
                  </li>
                ))}
              </ol>
            </section>
          </>
        )}
      </div>
    </>
  );
}
