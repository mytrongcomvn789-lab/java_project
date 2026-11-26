/// Mô hình dữ liệu cho `/v1/ai/**` (task 7.6). Khớp docs/05-api/ai-api.md và customer-ai-chat.md.
/// Mobile chỉ đọc kết quả do backend trả; không có khóa hay URL nhà cung cấp AI ở client.
library;

/// Nguồn của kết quả. Chỉ [provider] (kèm mock=false, degraded=false) là AI thật.
enum AiSource { provider, mock, fallback, formula, unknown }

AiSource _source(Object? v) => switch (v) {
      'PROVIDER' => AiSource.provider,
      'MOCK' => AiSource.mock,
      'FALLBACK' => AiSource.fallback,
      'FORMULA' => AiSource.formula,
      _ => AiSource.unknown,
    };

/// Phong bì chung của mọi phản hồi AI. [result] null khi fallback của khung 7.1.
class AiEnvelope<T> {
  const AiEnvelope(
      {required this.result,
      required this.model,
      required this.mock,
      required this.degraded,
      required this.source,
      required this.requestId,
      required this.generatedAt,
      required this.fallbackReason});

  final T? result;
  final String? model;
  final bool mock, degraded;
  final AiSource source;
  final String? requestId, fallbackReason;
  final DateTime? generatedAt;

  /// Tên nguồn theo backend (PROVIDER/MOCK/FALLBACK/FORMULA), dùng cho nhãn hiển thị.
  String get sourceName => switch (source) {
        AiSource.provider => 'PROVIDER',
        AiSource.mock => 'MOCK',
        AiSource.fallback => 'FALLBACK',
        AiSource.formula => 'FORMULA',
        AiSource.unknown => 'UNKNOWN',
      };

  factory AiEnvelope.fromJson(
      Map<String, dynamic> json, T Function(Map<String, dynamic>) parse) {
    final raw = json['result'];
    return AiEnvelope(
        result: raw is Map<String, dynamic> ? parse(raw) : null,
        model: json['model'] as String?,
        mock: json['mock'] == true,
        degraded: json['degraded'] == true,
        source: _source(json['source']),
        requestId: json['requestId'] as String?,
        generatedAt: DateTime.tryParse('${json['generatedAt']}'),
        fallbackReason: json['fallbackReason'] as String?);
  }
}

List<String> _strings(Object? v) =>
    v is List ? [for (final e in v) '$e'] : const <String>[];

/// ETA bản công thức (7.2). Không phải AI/ML; [confidence] luôn LOW.
class EtaResult {
  const EtaResult(
      {required this.orderId,
      required this.status,
      required this.applicable,
      required this.computed,
      this.reason,
      this.etaAt,
      this.etaMinutes,
      this.travelMinutes,
      this.waitMinutes,
      this.distanceKm,
      this.confidence,
      this.limitations = const []});

  final String orderId, status;

  /// false: trạng thái đơn không áp dụng ETA.
  final bool applicable;

  /// false: áp dụng nhưng chưa tính được (xem [reason]).
  final bool computed;
  final String? reason, confidence;
  final DateTime? etaAt;
  final int? etaMinutes, travelMinutes, waitMinutes;
  final double? distanceKm;
  final List<String> limitations;

  factory EtaResult.fromJson(Map<String, dynamic> j) => EtaResult(
      orderId: j['orderId'] as String,
      status: j['status'] as String? ?? '',
      applicable: j['applicable'] == true,
      computed: j['computed'] == true,
      reason: j['reason'] as String?,
      etaAt: j['etaAt'] == null ? null : DateTime.tryParse('${j['etaAt']}'),
      etaMinutes: (j['etaMinutes'] as num?)?.toInt(),
      travelMinutes: (j['travelMinutes'] as num?)?.toInt(),
      waitMinutes: (j['waitMinutes'] as num?)?.toInt(),
      distanceKm: (j['distanceKm'] as num?)?.toDouble(),
      confidence: j['confidence'] as String?,
      limitations: _strings(j['limitations']));
}

class SummaryStep {
  const SummaryStep(this.status, this.label, this.at, this.minutesSincePrevious);
  final String status, label;
  final DateTime? at;
  final int? minutesSincePrevious;
}

/// Tóm tắt đơn (7.3). [mode] = DETERMINISTIC_FALLBACK nghĩa là văn bản do hệ thống sinh, không phải AI.
class OrderSummaryResult {
  const OrderSummaryResult(
      {required this.orderId,
      required this.status,
      required this.statusLabel,
      required this.summary,
      required this.highlights,
      required this.timeline,
      required this.mode});

  final String orderId, status, statusLabel, summary, mode;
  final List<String> highlights;
  final List<SummaryStep> timeline;

  bool get isDeterministicFallback => mode == 'DETERMINISTIC_FALLBACK';

  factory OrderSummaryResult.fromJson(Map<String, dynamic> j) =>
      OrderSummaryResult(
          orderId: j['orderId'] as String,
          status: j['status'] as String? ?? '',
          statusLabel: j['statusLabel'] as String? ?? '',
          summary: j['summary'] as String? ?? '',
          highlights: _strings(j['highlights']),
          timeline: [
            for (final s in (j['timeline'] as List? ?? const []))
              SummaryStep(
                  (s as Map)['status'] as String? ?? '',
                  s['label'] as String? ?? '',
                  DateTime.tryParse('${s['at']}'),
                  (s['minutesSincePrevious'] as num?)?.toInt())
          ],
          mode: j['mode'] as String? ?? '');
}

class ChatSourceRef {
  const ChatSourceRef(this.id, this.title);
  final String id, title;
}

/// Câu trả lời chat (7.4). [reply] là văn bản không tin cậy: chỉ hiển thị dạng text.
class ChatResult {
  const ChatResult(
      {required this.reply,
      required this.conversationId,
      this.orderId,
      this.sources = const []});

  final String reply, conversationId;
  final String? orderId;
  final List<ChatSourceRef> sources;

  factory ChatResult.fromJson(Map<String, dynamic> j) => ChatResult(
      reply: j['reply'] as String? ?? '',
      conversationId: j['conversationId'] as String,
      orderId: j['orderId'] as String?,
      sources: [
        for (final s in (j['sources'] as List? ?? const []))
          ChatSourceRef(
              (s as Map)['id'] as String? ?? '', s['title'] as String? ?? '')
      ]);
}
