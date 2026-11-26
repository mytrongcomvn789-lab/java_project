import { describeAiSource, type AiEnvelope } from "../../lib/aiService";

/** Nhãn nguồn kết quả: AI thật / giả lập (MOCK) / dự phòng (FALLBACK) / công thức (FORMULA). */
export default function AiSourceBadge({
  envelope,
}: {
  envelope: Pick<AiEnvelope<unknown>, "source" | "mock" | "degraded" | "model" | "fallbackReason">;
}) {
  const info = describeAiSource(envelope);
  return (
    <span className={`ai-source ai-source-${info.tone}`} title={info.detail}>
      <strong>{info.label}</strong>
      <small> · {info.detail}</small>
    </span>
  );
}
