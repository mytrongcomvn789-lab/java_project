import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/features/ai/ai_api.dart';
import 'package:smartdrone_mobile/features/ai/ai_errors.dart';
import 'package:smartdrone_mobile/features/ai/ai_models.dart';

import 'support/fakes.dart';

const orderId = '11111111-1111-1111-1111-111111111111';

Map<String, dynamic> envelope(Object? result,
        {String source = 'PROVIDER',
        bool mock = false,
        bool degraded = false,
        String? model = 'gemini',
        String? reason}) =>
    {
      'result': result,
      'model': model,
      'mock': mock,
      'degraded': degraded,
      'source': source,
      'usage': {'promptTokens': 0, 'outputTokens': 0, 'totalTokens': 0},
      'requestId': 'r1',
      'generatedAt': '2026-11-25T03:00:00Z',
      'fallbackReason': reason,
    };

AiApi apiWith(FakeAdapter adapter) =>
    AiApi(Dio()..httpClientAdapter = adapter);

void main() {
  group('AiApi', () {
    test('ETA đã tính: đọc đúng trường, nguồn FORMULA, không phải AI', () async {
      final adapter = FakeAdapter((_) => jsonResponse(envelope({
            'orderId': orderId,
            'status': 'SCHEDULED',
            'applicable': true,
            'computed': true,
            'method': 'FORMULA_V1',
            'etaAt': '2026-11-25T05:08:00Z',
            'etaMinutes': 128,
            'travelMinutes': 3,
            'waitMinutes': 5,
            'distanceKm': 1.57,
            'confidence': 'LOW',
            'limitations': ['STRAIGHT_LINE_DISTANCE']
          }, source: 'FORMULA', model: null)));
      final env = await apiWith(adapter).eta(orderId);
      expect(adapter.requests.single.method, 'GET');
      expect(adapter.requests.single.path, '/v1/ai/orders/$orderId/eta');
      final r = env.result!;
      expect(r.computed, isTrue);
      expect(r.etaMinutes, 128);
      expect(r.distanceKm, 1.57);
      expect(r.limitations, ['STRAIGHT_LINE_DISTANCE']);
      final info = describeAiSource(env);
      expect(info.tone, AiSourceTone.formula);
      expect(info.isRealAi, isFalse);
    });

    test('ETA không áp dụng: etaMinutes null không làm sập', () async {
      final adapter = FakeAdapter((_) => jsonResponse(envelope({
            'orderId': orderId,
            'status': 'DELIVERED',
            'applicable': false,
            'computed': false,
            'reason': 'STATUS_NOT_APPLICABLE',
            'etaMinutes': null
          }, source: 'FORMULA', model: null)));
      final r = (await apiWith(adapter).eta(orderId)).result!;
      expect(r.applicable, isFalse);
      expect(r.etaAt, isNull);
      expect(r.reason, 'STATUS_NOT_APPLICABLE');
    });

    test('Tóm tắt: POST không body, đọc timeline và mode fallback', () async {
      final adapter = FakeAdapter((_) => jsonResponse(envelope({
            'orderId': orderId,
            'status': 'DELIVERED',
            'statusLabel': 'Đã giao',
            'summary': 'Đơn được duyệt rồi giao xong.',
            'highlights': ['Giai đoạn bay mất 10 phút.'],
            'timeline': [
              {
                'status': 'APPROVED',
                'label': 'Đã duyệt',
                'at': '2026-11-25T03:00:00Z',
                'minutesSincePrevious': null
              }
            ],
            'mode': 'DETERMINISTIC_FALLBACK'
          }, source: 'FALLBACK', degraded: true, model: null, reason: 'AI_TIMEOUT')));
      final env = await apiWith(adapter).summary(orderId);
      expect(adapter.requests.single.method, 'POST');
      expect(adapter.requests.single.data, isNull);
      final r = env.result!;
      expect(r.isDeterministicFallback, isTrue);
      expect(r.timeline.single.minutesSincePrevious, isNull);
      final info = describeAiSource(env);
      expect(info.tone, AiSourceTone.fallback);
      expect(info.isRealAi, isFalse);
      expect(info.detail, contains('quá chậm'));
    });

    test('Chat: lượt đầu không gửi conversationId, lượt sau gửi lại', () async {
      final adapter = FakeAdapter((_) => jsonResponse(envelope({
            'reply': 'Xin chào',
            'conversationId': 'c1',
            'orderId': orderId,
            'sources': [
              {'id': 'scope', 'title': 'Phạm vi', 'reference': 'x'}
            ]
          }, source: 'MOCK', mock: true, model: 'mock')));
      final api = apiWith(adapter);
      final first = await api.chat('Đơn của tôi?', orderId: orderId);
      expect(adapter.requests.last.data,
          {'message': 'Đơn của tôi?', 'orderId': orderId});
      expect(first.result!.conversationId, 'c1');
      expect(first.result!.sources.single.title, 'Phạm vi');
      await api.chat('Tiếp?', conversationId: 'c1');
      expect(adapter.requests.last.data,
          {'message': 'Tiếp?', 'conversationId': 'c1'});
      final info = describeAiSource(first);
      expect(info.tone, AiSourceTone.mock);
      expect(info.isRealAi, isFalse);
    });

    test('Nguồn PROVIDER thật mới được coi là AI thật', () {
      const real = AiEnvelope<Object?>(
          result: null,
          model: 'gemini',
          mock: false,
          degraded: false,
          source: AiSource.provider,
          requestId: null,
          generatedAt: null,
          fallbackReason: null);
      expect(describeAiSource(real).isRealAi, isTrue);
      const unknown = AiEnvelope<Object?>(
          result: null,
          model: null,
          mock: false,
          degraded: false,
          source: AiSource.unknown,
          requestId: null,
          generatedAt: null,
          fallbackReason: null);
      expect(describeAiSource(unknown).isRealAi, isFalse);
    });

    test('result sai kiểu không làm sập mà thành ApiFailure', () async {
      final adapter =
          FakeAdapter((_) => jsonResponse(envelope({'orderId': 5})));
      expect(apiWith(adapter).eta(orderId), throwsA(isA<ApiFailure>()));
    });

    test('hủy bằng CancelToken ném AiCanceled, không phải lỗi', () async {
      final adapter = FakeAdapter((_) async {
        await Future<void>.delayed(const Duration(milliseconds: 50));
        return jsonResponse(envelope(null));
      });
      final token = CancelToken();
      final future = apiWith(adapter).chat('x', cancel: token);
      token.cancel();
      await expectLater(future, throwsA(isA<AiCanceled>()));
    });
  });

  group('describeAiFailure', () {
    DioException http(int status, String code) => DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(
            requestOptions: RequestOptions(path: '/x'),
            statusCode: status,
            data: {'code': code, 'message': 'msg $code'}));

    test('429 rate limit và hết ngân sách KHÔNG được thử lại', () {
      for (final code in ['AI_RATE_LIMITED', 'AI_BUDGET_EXCEEDED']) {
        final e = describeAiFailure(http(429, code));
        expect(e.retryable, isFalse, reason: code);
      }
      // Không giống ApiFailure.isRetryable (coi mọi 429 là thử lại được).
      expect(toApiFailure(http(429, 'AI_RATE_LIMITED')).isRetryable, isTrue);
    });

    test('AI_NOT_CONFIGURED không thử lại; timeout/provider/invalid thử lại', () {
      expect(describeAiFailure(http(503, 'AI_NOT_CONFIGURED')).retryable, isFalse);
      expect(describeAiFailure(http(504, 'AI_TIMEOUT')).retryable, isTrue);
      expect(describeAiFailure(http(503, 'AI_PROVIDER_UNAVAILABLE')).retryable,
          isTrue);
      expect(describeAiFailure(http(502, 'AI_RESPONSE_INVALID')).retryable,
          isTrue);
      expect(describeAiFailure(http(504, 'AI_TIMEOUT')).message,
          contains('quá chậm'));
    });

    test('409 chat bận cho thử lại; 404/400 cần hội thoại mới; 403 không', () {
      final busy = describeAiFailure(http(409, 'DATA_CONFLICT'));
      expect(busy.busy, isTrue);
      expect(busy.retryable, isTrue);
      expect(describeAiFailure(http(404, 'RESOURCE_NOT_FOUND')).needsNewConversation,
          isTrue);
      expect(describeAiFailure(http(400, 'VALIDATION_ERROR')).needsNewConversation,
          isTrue);
      final forbidden = describeAiFailure(http(403, 'FORBIDDEN'));
      expect(forbidden.retryable, isFalse);
    });

    test('mất mạng: thử lại được và cảnh báo kết quả chưa rõ', () {
      final e = describeAiFailure(DioException(
          requestOptions: RequestOptions(path: '/x'),
          type: DioExceptionType.connectionError));
      expect(e.retryable, isTrue);
      expect(e.outcomeUnknown, isTrue);
    });
  });
}
