import { useCallback, useEffect, useRef, useState } from "react";
import {
  describeAiError,
  isAiCanceled,
  type AiEnvelope,
  type AiErrorInfo,
} from "./aiService";

/** Số lần thất bại liên tiếp tối đa trước khi ngừng đề nghị "Thử lại" (tránh tốn hạn mức AI). */
export const MAX_AI_FAILURES = 3;

export type AiTaskState<T> =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "success"; envelope: AiEnvelope<T> }
  | { status: "error"; error: AiErrorInfo; failures: number };

/**
 * Chạy một tác vụ AI với: hủy khi rời màn hình hoặc đổi `resetKey`, bỏ phản hồi muộn của lượt cũ,
 * xóa dữ liệu khi phiên bị đăng xuất (sd:unauthorized), và thử lại do người dùng bấm, có giới hạn.
 * `auto = true` tự chạy khi mount/đổi `resetKey` (chỉ dùng cho tác vụ không tốn token như ETA).
 */
export function useAiTask<T>(
  run: (signal: AbortSignal) => Promise<AiEnvelope<T>>,
  resetKey: string,
  auto: boolean,
) {
  const [state, setState] = useState<AiTaskState<T>>({ status: auto ? "loading" : "idle" });
  const runRef = useRef(run);
  runRef.current = run;
  const controllerRef = useRef<AbortController | null>(null);
  const failuresRef = useRef(0);

  const cancel = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
  }, []);

  const start = useCallback(() => {
    cancel();
    const controller = new AbortController();
    controllerRef.current = controller;
    setState({ status: "loading" });
    runRef
      .current(controller.signal)
      .then((envelope) => {
        if (controllerRef.current !== controller) return;
        failuresRef.current = 0;
        setState({ status: "success", envelope });
      })
      .catch((e: unknown) => {
        if (controllerRef.current !== controller || isAiCanceled(e)) return;
        failuresRef.current += 1;
        setState({ status: "error", error: describeAiError(e), failures: failuresRef.current });
      });
  }, [cancel]);

  // Đổi đơn/trạng thái: bỏ kết quả cũ, đếm lại số lần lỗi, và chạy lại nếu là tác vụ tự động.
  useEffect(() => {
    failuresRef.current = 0;
    if (auto) start();
    else {
      cancel();
      setState({ status: "idle" });
    }
    return cancel;
  }, [resetKey, auto, start, cancel]);

  // Đăng xuất / hết phiên: hủy yêu cầu đang chờ và xóa dữ liệu của tài khoản cũ.
  useEffect(() => {
    const onLogout = () => {
      cancel();
      failuresRef.current = 0;
      setState({ status: "idle" });
    };
    window.addEventListener("sd:unauthorized", onLogout);
    return () => window.removeEventListener("sd:unauthorized", onLogout);
  }, [cancel]);

  const canRetry =
    state.status === "error" && state.error.retryable && state.failures < MAX_AI_FAILURES;

  return { state, start, canRetry };
}
