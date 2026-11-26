import apiClient, { ApiRequestError } from "./apiClient";
import type {
  DailyOrderStats,
  DateBasis,
  OrderStatsSummary,
  StationLoadStat,
} from "./reportService";

/**
 * Lớp gọi backend AI (task 7.6). Khớp docs/05-api/ai-api.md, customer-ai-chat.md,
 * operations-ai-analytics.md. Chỉ gọi API của backend bằng JWT: KHÔNG có khóa hay URL nhà cung cấp AI ở client.
 */

// AI chậm hơn API thường (apiClient mặc định 10 giây), nhưng vẫn phải có giới hạn.
export const AI_TIMEOUT_MS = 30_000;

// ---- Envelope chung (AiResult) ----

/** PROVIDER = AI thật; MOCK = giả lập; FALLBACK = dự phòng xác định; FORMULA = công thức (ETA, không phải AI). */
export type AiSource = "PROVIDER" | "MOCK" | "FALLBACK" | "FORMULA";

export interface AiUsage {
  promptTokens: number;
  outputTokens: number;
  totalTokens: number;
}

export interface AiEnvelope<T> {
  /** null khi fallback của khung 7.1 (các tác vụ có fallback riêng vẫn trả result). */
  result: T | null;
  model: string | null;
  mock: boolean;
  degraded: boolean;
  source: AiSource;
  usage: AiUsage;
  requestId: string;
  generatedAt: string;
  fallbackReason: string | null;
}

// ---- ETA (7.2) ----

export interface EtaResult {
  orderId: string;
  status: string;
  method: "FORMULA_V1";
  computedAt: string;
  /** false: trạng thái đơn không áp dụng ETA (reason = STATUS_NOT_APPLICABLE). */
  applicable: boolean;
  /** false: áp dụng nhưng không tính được (reason = COORDINATES_MISSING, SPEED_INVALID, WAIT_INVALID). */
  computed: boolean;
  reason?: string;
  etaAt?: string | null;
  etaMinutes?: number | null;
  travelMinutes?: number;
  waitMinutes?: number;
  distanceKm?: number;
  /** Luôn LOW: chỉ báo tham số chưa hiệu chuẩn, không phải xác suất. */
  confidence?: "LOW";
  limitations?: string[];
}

// ---- Tóm tắt đơn (7.3) ----

export interface SummaryTimelineStep {
  status: string;
  label: string;
  at: string;
  minutesSincePrevious: number | null;
}

export interface OrderSummaryResult {
  orderId: string;
  status: string;
  statusLabel: string;
  summary: string;
  highlights: string[];
  timeline: SummaryTimelineStep[];
  summaryVersion: string;
  /** AI_VERIFIED = văn bản LLM đã kiểm chứng; DETERMINISTIC_FALLBACK = văn bản do Java sinh, không phải AI. */
  mode: "AI_VERIFIED" | "DETERMINISTIC_FALLBACK";
  readOnly: true;
}

// ---- Chat khách hàng (7.4) ----

export interface ChatRequest {
  message: string;
  /** Do server tạo; bỏ trống ở lượt đầu. Xóa khi đổi đơn hoặc đổi tài khoản. */
  conversationId?: string;
  orderId?: string;
}

export interface ChatSourceRef {
  id: string;
  title: string;
  reference: string;
}

export interface ChatResult {
  reply: string;
  conversationId: string;
  orderId?: string;
  knowledgeVersion: string;
  readOnly: true;
  sources: ChatSourceRef[];
}

// ---- Phân tích vận hành (7.5) ----

export interface AnalyticsRequest {
  from?: string;
  to?: string;
  dateBasis?: DateBasis;
  stationId?: string;
}

export interface AnalyticsEvidence {
  id: string;
  text: string;
  source: string;
  recommendation: string | null;
}

export interface AnalyticsTrend {
  daysPerWindow: number;
  firstFrom: string;
  firstTo: string;
  lastFrom: string;
  lastTo: string;
  omittedMiddleDay: string | null;
  firstOrders: number;
  lastOrders: number;
  delta: number;
  changeRate: number | null;
}

export interface AnalyticsResult {
  report: {
    summary: OrderStatsSummary;
    daily: DailyOrderStats[];
    /** Tải khung giờ hiện tại tại collectedAt, không phải tổng cả kỳ. */
    stationLoad: StationLoadStat[];
    collectedAt: string;
  };
  analysis: {
    failureRate: number | null;
    failureSample: number;
    trend: AnalyticsTrend | null;
    warnings: string[];
    evidence: AnalyticsEvidence[];
  };
  insightIds: string[];
  insights: string[];
  recommendationIds: string[];
  recommendations: string[];
  rulesVersion: string;
  narrativeMode: "AI_SELECTED_VERIFIED_EVIDENCE" | "DETERMINISTIC_FALLBACK";
  readOnly: true;
}

// ---- Hủy yêu cầu ----

/** Yêu cầu bị hủy chủ động (rời màn hình, đổi đơn, đăng xuất). Không phải lỗi, màn hình bỏ qua. */
export class AiRequestCanceled extends Error {
  constructor() {
    super("Yêu cầu AI đã bị hủy");
    this.name = "AiRequestCanceled";
  }
}

export const isAiCanceled = (e: unknown): e is AiRequestCanceled =>
  e instanceof AiRequestCanceled;

async function call<T>(
  signal: AbortSignal | undefined,
  run: (opts: { signal?: AbortSignal; timeout: number }) => Promise<{ data: AiEnvelope<T> }>,
): Promise<AiEnvelope<T>> {
  try {
    return (await run({ signal, timeout: AI_TIMEOUT_MS })).data;
  } catch (e) {
    // apiClient chuẩn hóa mọi lỗi; hủy chủ động nhận ra qua chính signal.
    if (signal?.aborted) throw new AiRequestCanceled();
    throw e;
  }
}

const enc = encodeURIComponent;

export const aiService = {
  eta: (orderId: string, signal?: AbortSignal) =>
    call<EtaResult>(signal, (o) => apiClient.get(`/v1/ai/orders/${enc(orderId)}/eta`, o)),

  summary: (orderId: string, signal?: AbortSignal) =>
    call<OrderSummaryResult>(signal, (o) =>
      apiClient.post(`/v1/ai/orders/${enc(orderId)}/summary`, undefined, o),
    ),

  chat: (body: ChatRequest, signal?: AbortSignal) =>
    call<ChatResult>(signal, (o) => apiClient.post("/v1/ai/chat", body, o)),

  analytics: (body: AnalyticsRequest, signal?: AbortSignal) =>
    call<AnalyticsResult>(signal, (o) => apiClient.post("/v1/ai/analytics", body, o)),
};

// ---- Nhãn nguồn: không được trình bày kết quả giả lập/dự phòng/công thức như AI thật ----

export type AiSourceTone = "ai" | "mock" | "fallback" | "formula";

export interface AiSourceInfo {
  tone: AiSourceTone;
  /** Chỉ true khi PROVIDER, không mock, không degraded. */
  isRealAi: boolean;
  label: string;
  detail: string;
}

export function describeAiSource(
  env: Pick<AiEnvelope<unknown>, "source" | "mock" | "degraded" | "model" | "fallbackReason">,
): AiSourceInfo {
  if (env.source === "FORMULA") {
    return {
      tone: "formula",
      isRealAi: false,
      label: "Ước tính theo công thức",
      detail: "Không phải AI hay mô hình học máy; tham số chưa hiệu chuẩn.",
    };
  }
  if (env.source === "MOCK" || env.mock) {
    return {
      tone: "mock",
      isRealAi: false,
      label: "Giả lập (MOCK)",
      detail: "Nội dung giả lập để thử nghiệm, không phải kết quả AI thật.",
    };
  }
  if (env.source === "FALLBACK" || env.degraded) {
    const reason = env.fallbackReason ? AI_ERROR_MESSAGES[env.fallbackReason] : undefined;
    return {
      tone: "fallback",
      isRealAi: false,
      label: "Dự phòng (không dùng AI)",
      detail: reason
        ? `AI không khả dụng: ${reason} Nội dung do hệ thống tạo theo quy tắc cố định.`
        : "AI không khả dụng. Nội dung do hệ thống tạo theo quy tắc cố định.",
    };
  }
  return {
    tone: "ai",
    isRealAi: true,
    label: env.model ? `AI (${env.model})` : "AI",
    detail: "Do mô hình AI tạo và đã qua kiểm tra của hệ thống; vẫn nên đối chiếu với dữ liệu đơn.",
  };
}

// ---- Lỗi ----

/** Thông báo theo mã lỗi AI_* của backend (không chứa chi tiết nhà cung cấp). */
const AI_ERROR_MESSAGES: Record<string, string> = {
  AI_NOT_CONFIGURED: "Dịch vụ AI chưa được cấu hình hoặc đang tắt.",
  AI_PROVIDER_UNAVAILABLE: "Dịch vụ AI tạm thời không khả dụng.",
  AI_TIMEOUT: "Dịch vụ AI phản hồi quá chậm.",
  AI_RATE_LIMITED: "Đã vượt giới hạn số lần dùng AI. Vui lòng thử lại sau.",
  AI_BUDGET_EXCEEDED: "Hệ thống đã hết hạn mức AI trong ngày.",
  AI_RESPONSE_INVALID: "Phản hồi AI không hợp lệ nên đã bị chặn.",
};

export type AiErrorKind =
  | "network" // không tới được máy chủ hoặc hết thời gian chờ phía trình duyệt
  | "unauthorized" // 401 (apiClient đã thử refresh)
  | "forbidden" // 403: vai trò không dùng được tác vụ này
  | "notFound" // 404: đơn/hội thoại không tồn tại, hết hạn hoặc ngoài quyền
  | "validation" // 400
  | "busy" // 409: hội thoại đang xử lý hoặc request mất lease
  | "limit" // 429 AI_RATE_LIMITED / AI_BUDGET_EXCEEDED
  | "aiUnavailable" // AI_NOT_CONFIGURED / AI_PROVIDER_UNAVAILABLE / AI_TIMEOUT / AI_RESPONSE_INVALID
  | "server"
  | "unknown";

export interface AiErrorInfo {
  kind: AiErrorKind;
  message: string;
  /** Có nên đề nghị người dùng bấm "Thử lại". Không bao giờ tự động thử lại vô hạn: màn hình tự giới hạn số lần. */
  retryable: boolean;
  code?: string;
  traceId?: string;
}

export function describeAiError(e: unknown): AiErrorInfo {
  if (!(e instanceof ApiRequestError)) {
    return { kind: "unknown", message: "Không thể hoàn tất thao tác. Vui lòng thử lại.", retryable: true };
  }
  const { status, code, traceId } = e;
  const base = { code, traceId };
  const aiMsg = code ? AI_ERROR_MESSAGES[code] : undefined;

  if (status === undefined) {
    return { ...base, kind: "network", message: "Không kết nối được máy chủ hoặc chờ quá lâu. Kiểm tra mạng và thử lại.", retryable: true };
  }
  if (status === 401) {
    return { ...base, kind: "unauthorized", message: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.", retryable: false };
  }
  if (status === 403) {
    return { ...base, kind: "forbidden", message: "Tài khoản không có quyền dùng tính năng này.", retryable: false };
  }
  if (status === 404) {
    return { ...base, kind: "notFound", message: "Không tìm thấy dữ liệu, dữ liệu đã hết hạn hoặc bạn không có quyền xem.", retryable: false };
  }
  if (status === 400) {
    return { ...base, kind: "validation", message: e.message || "Dữ liệu gửi lên không hợp lệ.", retryable: false };
  }
  if (status === 409) {
    return { ...base, kind: "busy", message: "Yêu cầu trước đang được xử lý. Vui lòng đợi giây lát rồi thử lại.", retryable: true };
  }
  // 429 của AI (rate limit/ngân sách) không được thử lại ngay: thử lại chỉ tốn thêm hạn mức.
  if (status === 429) {
    return { ...base, kind: "limit", message: aiMsg ?? "Quá nhiều yêu cầu. Vui lòng thử lại sau.", retryable: false };
  }
  if (code && aiMsg) {
    // AI chưa cấu hình: thử lại vô ích. Các lỗi AI còn lại có thể là tạm thời.
    return { ...base, kind: "aiUnavailable", message: aiMsg, retryable: code !== "AI_NOT_CONFIGURED" };
  }
  if (status >= 500) {
    return { ...base, kind: "server", message: "Máy chủ đang gặp lỗi. Vui lòng thử lại.", retryable: true };
  }
  return { ...base, kind: "unknown", message: "Không thể hoàn tất thao tác. Vui lòng thử lại.", retryable: false };
}
