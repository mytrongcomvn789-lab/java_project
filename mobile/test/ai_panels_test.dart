import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/features/ai/ai_api.dart';
import 'package:smartdrone_mobile/features/ai/ai_errors.dart';
import 'package:smartdrone_mobile/features/ai/ai_models.dart';
import 'package:smartdrone_mobile/features/ai/ai_panels.dart';
import 'package:smartdrone_mobile/features/ai/ai_task_controller.dart';

const oid = '11111111-1111-1111-1111-111111111111';
const key = AiOrderKey(oid, 'SCHEDULED', 1);

DioException http(int status, String code) => DioException(
    requestOptions: RequestOptions(path: '/x'),
    response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: status,
        data: {'code': code, 'message': 'msg $code'}));

AiEnvelope<T> env<T>(T result,
        {AiSource source = AiSource.provider,
        bool mock = false,
        bool degraded = false,
        String? model = 'gemini',
        String? reason}) =>
    AiEnvelope(
        result: result,
        model: model,
        mock: mock,
        degraded: degraded,
        source: source,
        requestId: 'r',
        generatedAt: null,
        fallbackReason: reason);

const computedEta = EtaResult(
    orderId: oid,
    status: 'SCHEDULED',
    applicable: true,
    computed: true,
    etaAt: null,
    etaMinutes: 22,
    travelMinutes: 17,
    waitMinutes: 5,
    distanceKm: 11.26,
    confidence: 'LOW',
    limitations: ['STRAIGHT_LINE_DISTANCE']);

const sampleSummary = OrderSummaryResult(
    orderId: oid,
    status: 'DELIVERED',
    statusLabel: 'Đã giao',
    summary: 'Đơn được duyệt rồi giao xong.',
    highlights: ['Giai đoạn bay mất 10 phút.'],
    timeline: [SummaryStep('APPROVED', 'Đã duyệt', null, null)],
    mode: 'AI_VERIFIED');

class FakeAi extends AiApi {
  FakeAi() : super(Dio());
  Future<AiEnvelope<EtaResult>> Function() onEta = () async => env(computedEta,
      source: AiSource.formula, model: null);
  Future<AiEnvelope<OrderSummaryResult>> Function() onSummary =
      () async => env(sampleSummary);
  int etaCalls = 0, summaryCalls = 0;
  CancelToken? lastEtaToken;

  @override
  Future<AiEnvelope<EtaResult>> eta(String orderId, {CancelToken? cancel}) {
    etaCalls++;
    lastEtaToken = cancel;
    return onEta();
  }

  @override
  Future<AiEnvelope<OrderSummaryResult>> summary(String orderId,
      {CancelToken? cancel}) {
    summaryCalls++;
    return onSummary();
  }
}

void main() {
  late FakeAi ai;
  late StateProvider<String?> user;

  setUp(() {
    ai = FakeAi();
    user = StateProvider<String?>((ref) => 'user-1');
  });

  Future<void> mount(WidgetTester tester, Widget panel) =>
      tester.pumpWidget(ProviderScope(
          overrides: [
            aiApiProvider.overrideWithValue(ai),
            realtimeUserProvider.overrideWith((ref) => ref.watch(user)),
          ],
          child: MaterialApp(home: Scaffold(body: SingleChildScrollView(child: panel)))));

  group('ETA', () {
    testWidgets('tự tải, hiện ETA, nhãn công thức, giới hạn và độ tin cậy thấp',
        (tester) async {
      await mount(tester, const AiEtaPanel(key));
      expect(find.byKey(const Key('ai-loading')), findsOneWidget);
      await tester.pumpAndSettle();
      expect(ai.etaCalls, 1);
      expect(find.textContaining('khoảng 22 phút nữa'), findsOneWidget);
      expect(find.text('Ước tính theo công thức'), findsOneWidget);
      expect(find.textContaining('Độ tin cậy: thấp'), findsOneWidget);
      expect(find.textContaining('đường chim bay'), findsOneWidget);
    });

    testWidgets('trạng thái không áp dụng: hiện lý do, không hiện giờ giao',
        (tester) async {
      ai.onEta = () async => env(
          const EtaResult(
              orderId: oid,
              status: 'DELIVERED',
              applicable: false,
              computed: false,
              reason: 'STATUS_NOT_APPLICABLE'),
          source: AiSource.formula,
          model: null);
      await mount(tester, const AiEtaPanel(key));
      await tester.pumpAndSettle();
      expect(find.textContaining('không áp dụng ước tính'), findsOneWidget);
      expect(find.byKey(const Key('ai-eta-value')), findsNothing);
    });

    testWidgets('lỗi tạm thời: thử lại được, tối đa 3 lần thất bại rồi ngừng',
        (tester) async {
      ai.onEta = () async => throw http(504, 'AI_TIMEOUT');
      await mount(tester, const AiEtaPanel(key));
      await tester.pumpAndSettle();
      for (var i = 1; i < maxAiFailures; i++) {
        expect(find.byKey(const Key('ai-retry')), findsOneWidget);
        await tester.tap(find.byKey(const Key('ai-retry')));
        await tester.pumpAndSettle();
      }
      expect(ai.etaCalls, maxAiFailures);
      expect(find.byKey(const Key('ai-retry')), findsNothing);
      expect(find.textContaining('Đã thử nhiều lần'), findsOneWidget);
    });

    testWidgets('thử lại thành công thì hiện kết quả', (tester) async {
      var first = true;
      ai.onEta = () async {
        if (first) {
          first = false;
          throw http(503, 'AI_PROVIDER_UNAVAILABLE');
        }
        return env(computedEta, source: AiSource.formula, model: null);
      };
      await mount(tester, const AiEtaPanel(key));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('ai-retry')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('ai-eta-value')), findsOneWidget);
      expect(find.byKey(const Key('ai-error')), findsNothing);
    });

    testWidgets('rời màn hình thì hủy yêu cầu đang chờ', (tester) async {
      final never = Completer<AiEnvelope<EtaResult>>();
      ai.onEta = () => never.future;
      await mount(tester, const AiEtaPanel(key));
      await tester.pump();
      final token = ai.lastEtaToken!;
      expect(token.isCancelled, isFalse);
      await tester.pumpWidget(const SizedBox());
      expect(token.isCancelled, isTrue);
    });

    testWidgets('đơn đổi trạng thái: tải lại ETA với khóa mới', (tester) async {
      var status = 'SCHEDULED';
      final statusProvider = StateProvider<String>((ref) => status);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            aiApiProvider.overrideWithValue(ai),
            realtimeUserProvider.overrideWith((ref) => 'user-1'),
          ],
          child: MaterialApp(
              home: Consumer(
                  builder: (context, ref, _) => Scaffold(
                      body: AiEtaPanel(AiOrderKey(oid, ref.watch(statusProvider), 1)))))));
      await tester.pumpAndSettle();
      expect(ai.etaCalls, 1);
      final container = ProviderScope.containerOf(tester.element(find.byType(AiEtaPanel)));
      container.read(statusProvider.notifier).state = 'IN_TRANSIT';
      await tester.pumpAndSettle();
      expect(ai.etaCalls, 2);
    });
  });

  group('Tóm tắt', () {
    testWidgets('không gọi API cho đến khi bấm nút (tránh tốn hạn mức)',
        (tester) async {
      await mount(tester, const AiSummaryPanel(key));
      await tester.pumpAndSettle();
      expect(ai.summaryCalls, 0);
      expect(find.byKey(const Key('ai-summary-start')), findsOneWidget);
      await tester.tap(find.byKey(const Key('ai-summary-start')));
      await tester.pumpAndSettle();
      expect(ai.summaryCalls, 1);
      expect(find.text('Đơn được duyệt rồi giao xong.'), findsOneWidget);
      expect(find.text('AI (gemini)'), findsOneWidget);
    });

    testWidgets('MOCK và FALLBACK không được gắn nhãn AI thật', (tester) async {
      ai.onSummary = () async => env(sampleSummary,
          source: AiSource.mock, mock: true, model: 'mock');
      await mount(tester, const AiSummaryPanel(key));
      await tester.tap(find.byKey(const Key('ai-summary-start')));
      await tester.pumpAndSettle();
      expect(find.text('Giả lập (MOCK)'), findsOneWidget);
      expect(find.text('AI (mock)'), findsNothing);

      ai.onSummary = () async => env(sampleSummary,
          source: AiSource.fallback,
          degraded: true,
          model: null,
          reason: 'AI_TIMEOUT');
      await tester.tap(find.byKey(const Key('ai-summary-restart')));
      await tester.pumpAndSettle();
      expect(find.text('Dự phòng (không dùng AI)'), findsOneWidget);
      expect(find.textContaining('quá chậm'), findsOneWidget);
    });

    testWidgets('429 hết hạn mức: báo lỗi, không có nút thử lại', (tester) async {
      ai.onSummary = () async => throw http(429, 'AI_BUDGET_EXCEEDED');
      await mount(tester, const AiSummaryPanel(key));
      await tester.tap(find.byKey(const Key('ai-summary-start')));
      await tester.pumpAndSettle();
      expect(find.textContaining('hết hạn mức'), findsOneWidget);
      expect(find.byKey(const Key('ai-retry')), findsNothing);
    });

    testWidgets('đăng xuất/đổi tài khoản: xóa tóm tắt của tài khoản cũ',
        (tester) async {
      await mount(tester, const AiSummaryPanel(key));
      await tester.tap(find.byKey(const Key('ai-summary-start')));
      await tester.pumpAndSettle();
      expect(find.text('Đơn được duyệt rồi giao xong.'), findsOneWidget);
      final container =
          ProviderScope.containerOf(tester.element(find.byType(AiSummaryPanel)));
      container.read(user.notifier).state = 'user-2';
      await tester.pumpAndSettle();
      expect(find.text('Đơn được duyệt rồi giao xong.'), findsNothing);
      expect(find.byKey(const Key('ai-summary-start')), findsOneWidget);
    });

    testWidgets('văn bản có ký tự giống HTML chỉ hiển thị như text',
        (tester) async {
      const nasty = '<b>đậm</b> <script>alert(1)</script>';
      ai.onSummary = () async => env(OrderSummaryResult(
          orderId: oid,
          status: 'X',
          statusLabel: 'X',
          summary: nasty,
          highlights: const [],
          timeline: const [],
          mode: 'AI_VERIFIED'));
      await mount(tester, const AiSummaryPanel(key));
      await tester.tap(find.byKey(const Key('ai-summary-start')));
      await tester.pumpAndSettle();
      expect(find.text(nasty), findsOneWidget);
    });
  });
}
