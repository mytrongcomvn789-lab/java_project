import type { RoleName } from "../types/api";
import type { DeliveryStatus } from "./deliveryStatus";

export type Actor = RoleName | "SYSTEM";

export interface FlowStep {
  title: string;
  detail: string;
  actors: Actor[];
  status: DeliveryStatus;
}

export const ACTOR_LABELS: Record<Actor, string> = {
  CUSTOMER: "Khách hàng",
  DISPATCHER: "Điều phối viên",
  STATION_OPERATOR: "Nhân viên trạm",
  LOGISTICS_MANAGER: "Quản lý hậu cần",
  SYSTEM_ADMIN: "Quản trị viên",
  SYSTEM: "Hệ thống / Drone",
};

// Quy trình giao hàng theo sơ đồ hoạt động (swimlane) và sơ đồ tuần tự.
export const DELIVERY_FLOW: FlowStep[] = [
  {
    title: "Gửi yêu cầu giao hàng",
    detail: "Nhập thông tin kiện hàng, chọn địa chỉ giao hàng rồi gửi yêu cầu.",
    actors: ["CUSTOMER"],
    status: "PENDING",
  },
  {
    title: "Kiểm tra & phê duyệt yêu cầu",
    detail:
      "Yêu cầu hợp lệ thì phê duyệt; không hợp lệ thì từ chối và báo cho khách hàng.",
    actors: ["DISPATCHER"],
    status: "APPROVED",
  },
  {
    title: "Lên lịch giao hàng",
    detail:
      "Chọn lịch giao cho yêu cầu đã phê duyệt, khách hàng nhận thông báo.",
    actors: ["DISPATCHER"],
    status: "SCHEDULED",
  },
  {
    title: "Kiểm tra & tiếp nhận kiện hàng tại trạm",
    detail:
      "Kiểm tra tình trạng sẵn sàng của trạm, tiếp nhận và xác nhận kiện hàng.",
    actors: ["STATION_OPERATOR"],
    status: "PREPARING",
  },
  {
    title: "Drone cất cánh và giao hàng",
    detail:
      "Hệ thống cập nhật vị trí, tiến trình và thời gian dự kiến (ETA) liên tục.",
    actors: ["SYSTEM"],
    status: "DELIVERING",
  },
  {
    title: "Giao thành công & xác nhận nhận hàng",
    detail: "Hệ thống cập nhật Đã giao, khách hàng xác nhận đã nhận hàng.",
    actors: ["SYSTEM", "CUSTOMER"],
    status: "DELIVERED",
  },
  {
    title: "Xử lý giao hàng thất bại",
    detail: "Điều phối viên chọn giao lại (lên lịch lại) hoặc hủy / hoàn đơn.",
    actors: ["SYSTEM", "DISPATCHER"],
    status: "FAILED",
  },
];
