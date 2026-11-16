import {
  Client,
  type IFrame,
  type IMessage,
  type StompSubscription,
} from "@stomp/stompjs";
import { refreshAccessTokenOnce } from "./apiClient";
import { IS_DEMO_AUTH, WS_URL } from "./env";
import { tokenStorage } from "./token";

/** Sự kiện đơn hàng, khớp OrderRealtimeEvent ở backend (docs/05-api/realtime-api.md). */
export interface OrderRealtimeEvent {
  schemaVersion: number;
  eventId: string;
  type: string;
  event: string;
  orderId: string;
  attemptId: string | null;
  fromStatus: string | null;
  status: string;
  version: number;
  occurredAt: string;
}

export type RealtimeState = "offline" | "connecting" | "live" | "reconnecting";

interface OrderListener {
  onEvent: (event: OrderRealtimeEvent) => void;
  /** Gọi mỗi khi (kết nối lại và) đăng ký kênh xong: tải bù dữ liệu bằng REST. */
  onResync?: () => void;
  /** Server từ chối đăng ký kênh (không có quyền hoặc đơn không tồn tại). */
  onDenied?: () => void;
}

const MAX_BACKOFF_MS = 30_000;
const TOKEN_MARGIN_S = 30; // làm mới token khi còn dưới 30 giây
const SEEN_LIMIT = 200; // số eventId nhớ cho mỗi đơn để loại trùng

function secondsLeft(token: string): number {
  try {
    const part = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const json = JSON.parse(
      atob(part.padEnd(Math.ceil(part.length / 4) * 4, "=")),
    );
    return typeof json.exp === "number" ? json.exp - Date.now() / 1000 : 0;
  } catch {
    return 0;
  }
}

/**
 * Một kết nối STOMP cho mỗi tab trình duyệt, dùng chung cho mọi màn hình.
 * - Token gửi trong header CONNECT (không đặt lên URL).
 * - Mất kết nối: thử lại với backoff 1s, 2s, 4s... tối đa 30s (có jitter).
 * - Token sắp hết hạn: kết nối lại bằng token mới trước khi server đóng.
 * - Đăng xuất: disconnect() xóa sạch kết nối và đăng ký.
 */
class RealtimeClient {
  private client: Client | null = null;
  private connecting = false;
  private stopped = true;
  private forceRefresh = false;
  private attempt = 0;
  private retryTimer: number | undefined;
  private renewTimer: number | undefined;
  private state: RealtimeState = "offline";
  private readonly stateListeners = new Set<(s: RealtimeState) => void>();
  private readonly listeners = new Map<string, Set<OrderListener>>();
  private readonly subscriptions = new Map<string, StompSubscription>();
  private readonly denied = new Set<string>();
  private readonly seen = new Map<string, Set<string>>();

  getState(): RealtimeState {
    return this.state;
  }

  onState(cb: (s: RealtimeState) => void): () => void {
    this.stateListeners.add(cb);
    return () => {
      this.stateListeners.delete(cb);
    };
  }

  /** Theo dõi một đơn. Trả về hàm hủy; đơn cuối cùng bị hủy thì đóng kết nối. */
  subscribeOrder(orderId: string, listener: OrderListener): () => void {
    if (IS_DEMO_AUTH) return () => undefined; // chế độ demo không có token thật
    let set = this.listeners.get(orderId);
    if (!set) {
      set = new Set();
      this.listeners.set(orderId, set);
    }
    set.add(listener);
    if (this.stopped) {
      this.stopped = false;
      this.attempt = 0;
      void this.connect();
    } else {
      this.attach(orderId);
    }
    return () => this.unsubscribeOrder(orderId, listener);
  }

  /** Đăng xuất hoặc hết phiên: đóng kết nối và dọn sạch mọi đăng ký. */
  disconnect(): void {
    this.stopped = true;
    window.clearTimeout(this.retryTimer);
    window.clearTimeout(this.renewTimer);
    const client = this.client;
    this.client = null;
    this.subscriptions.clear();
    this.listeners.clear();
    this.denied.clear();
    this.seen.clear();
    if (client) void client.deactivate();
    this.setState("offline");
  }

  private unsubscribeOrder(orderId: string, listener: OrderListener): void {
    const set = this.listeners.get(orderId);
    if (!set) return;
    set.delete(listener);
    if (set.size > 0) return;
    this.listeners.delete(orderId);
    this.seen.delete(orderId);
    this.denied.delete(orderId);
    try {
      this.subscriptions.get(orderId)?.unsubscribe();
    } catch {
      /* kết nối đã đóng */
    }
    this.subscriptions.delete(orderId);
    if (this.listeners.size === 0) this.disconnect();
  }

  private async freshToken(): Promise<string | null> {
    let token = tokenStorage.get();
    if (!token) return null;
    if (this.forceRefresh || secondsLeft(token) < TOKEN_MARGIN_S) {
      this.forceRefresh = false;
      token = await refreshAccessTokenOnce();
    }
    return token;
  }

  private async connect(): Promise<void> {
    if (this.stopped || this.client || this.connecting) return;
    this.connecting = true;
    this.setState(this.attempt === 0 ? "connecting" : "reconnecting");
    try {
      const token = await this.freshToken();
      if (this.stopped) return;
      if (!token) {
        this.disconnect(); // chưa đăng nhập: không có gì để kết nối
        return;
      }
      const client = new Client({
        brokerURL: WS_URL,
        connectHeaders: { Authorization: `Bearer ${token}` },
        reconnectDelay: 0, // tự quản lý backoff và làm mới token
        heartbeatIncoming: 10_000,
        heartbeatOutgoing: 10_000,
        onConnect: () => this.onConnected(client, token),
        onStompError: (frame) => this.onStompError(frame),
        onWebSocketClose: (event) => this.onClosed(client, event),
      });
      this.client = client;
      client.activate();
    } catch {
      if (!this.stopped) this.scheduleRetry();
    } finally {
      this.connecting = false;
    }
  }

  private onConnected(client: Client, token: string): void {
    if (this.client !== client) return;
    this.attempt = 0;
    this.setState("live");
    for (const orderId of this.listeners.keys()) this.attach(orderId);
    // Làm mới kết nối trước khi token hết hạn; jitter để nhiều tab không refresh cùng lúc.
    const renewInMs =
      (secondsLeft(token) - TOKEN_MARGIN_S - Math.random() * 10) * 1000;
    window.clearTimeout(this.renewTimer);
    this.renewTimer = window.setTimeout(
      () => this.renew(),
      Math.max(renewInMs, 5_000),
    );
  }

  private attach(orderId: string): void {
    const client = this.client;
    if (
      !client ||
      !client.connected ||
      this.subscriptions.has(orderId) ||
      this.denied.has(orderId)
    ) {
      return;
    }
    const receipt = `sub:${orderId}`;
    // Server xác nhận đăng ký xong thì tải bù bằng REST (không để sót sự kiện giữa lúc tải và lúc đăng ký).
    client.watchForReceipt(receipt, () =>
      this.listeners.get(orderId)?.forEach((l) => l.onResync?.()),
    );
    const subscription = client.subscribe(
      `/topic/orders/${orderId}`,
      (message) => this.onMessage(orderId, message),
      { id: `order-${orderId}`, receipt },
    );
    this.subscriptions.set(orderId, subscription);
  }

  private onMessage(orderId: string, message: IMessage): void {
    let event: OrderRealtimeEvent;
    try {
      event = JSON.parse(message.body) as OrderRealtimeEvent;
    } catch {
      return;
    }
    if (event.orderId !== orderId) return; // sai kênh thì bỏ
    let seen = this.seen.get(orderId);
    if (!seen) {
      seen = new Set();
      this.seen.set(orderId, seen);
    }
    if (seen.has(event.eventId)) return; // loại sự kiện trùng theo eventId
    seen.add(event.eventId);
    if (seen.size > SEEN_LIMIT) {
      const oldest = seen.values().next().value;
      if (oldest !== undefined) seen.delete(oldest);
    }
    this.listeners.get(orderId)?.forEach((l) => l.onEvent(event));
  }

  private onStompError(frame: IFrame): void {
    const message = frame.headers["message"] ?? "";
    const receipt = frame.headers["receipt-id"] ?? "";
    if (message.includes("FORBIDDEN") && receipt.startsWith("sub:")) {
      const orderId = receipt.slice(4);
      this.denied.add(orderId); // không đăng ký lại đơn này khi nối lại
      this.listeners.get(orderId)?.forEach((l) => l.onDenied?.());
    } else if (
      message.includes("TOKEN_EXPIRED") ||
      message.includes("AUTH_REQUIRED")
    ) {
      this.forceRefresh = true; // lần nối sau phải lấy token mới
    }
    // Server đóng kết nối sau khung ERROR; onClosed() sẽ lên lịch nối lại.
  }

  private onClosed(client: Client, event: CloseEvent): void {
    if (this.client !== client) return; // kết nối cũ đã bị thay thế
    this.client = null;
    this.subscriptions.clear();
    window.clearTimeout(this.renewTimer);
    if (event.reason.includes("TOKEN_EXPIRED")) this.forceRefresh = true;
    if (this.stopped) {
      this.setState("offline");
      return;
    }
    this.setState("reconnecting");
    this.scheduleRetry();
  }

  private renew(): void {
    if (this.stopped) return;
    const old = this.client;
    this.client = null;
    this.subscriptions.clear();
    if (old) void old.deactivate();
    this.attempt = 0;
    void this.connect();
  }

  private scheduleRetry(): void {
    window.clearTimeout(this.retryTimer);
    const delay =
      Math.min(MAX_BACKOFF_MS, 1_000 * 2 ** this.attempt) + Math.random() * 500;
    this.attempt += 1;
    this.retryTimer = window.setTimeout(() => void this.connect(), delay);
  }

  private setState(next: RealtimeState): void {
    if (this.state === next) return;
    this.state = next;
    this.stateListeners.forEach((cb) => cb(next));
  }
}

export const realtime = new RealtimeClient();
