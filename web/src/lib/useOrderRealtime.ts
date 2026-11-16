import { useEffect, useRef, useState } from "react";
import {
  realtime,
  type OrderRealtimeEvent,
  type RealtimeState,
} from "./realtime";

interface Handlers {
  onEvent: (event: OrderRealtimeEvent) => void;
  onResync: () => void;
}

/** Theo dõi một đơn theo thời gian thực. Tự hủy đăng ký khi đổi đơn, tắt `enabled` hoặc rời trang. */
export function useOrderRealtime(
  orderId: string,
  handlers: Handlers,
  enabled = true,
): { state: RealtimeState; denied: boolean } {
  const latest = useRef(handlers);
  latest.current = handlers; // luôn gọi bản mới nhất, không phải đăng ký lại mỗi lần render
  const [state, setState] = useState<RealtimeState>(realtime.getState());
  const [denied, setDenied] = useState(false);

  useEffect(() => realtime.onState(setState), []);

  useEffect(() => {
    if (!enabled || !orderId) return;
    setDenied(false);
    return realtime.subscribeOrder(orderId, {
      onEvent: (e) => latest.current.onEvent(e),
      onResync: () => latest.current.onResync(),
      onDenied: () => setDenied(true),
    });
  }, [orderId, enabled]);

  return { state, denied };
}
