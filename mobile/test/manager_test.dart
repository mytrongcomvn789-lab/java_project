import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/core/router/app_router.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/features/ai/ai_models.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'package:smartdrone_mobile/features/manager/manager_api.dart';
import 'package:smartdrone_mobile/features/manager/manager_screens.dart';

import 'support/fakes.dart';

Map<String, dynamic> summaryJson({int total = 17, double? success = 0.5}) => {
      'from': '2026-10-29',
      'to': '2026-11-27',
      'orders': {
        'total': total,
        'pending': 0,
        'inProgress': 8,
        'delivered': 3,
        'failed': 1,
        'returning': 1,
        'returned': 1,
        'cancelled': 1,
        'rejected': 2
      },
      'byStatus': {'APPROVED': 1, 'DELIVERED': 3, 'FAILED': 1, 'IN_TRANSIT': 1, 'PENDING': 0},
      'rates': {'successRate': success, 'returnRate': 0.3333, 'cancelRate': 0.0588, 'deliverySample': success == null ? 0 : 6},
      'attempts': {'totalAttempts': 13, 'retryAttempts': 2},
      'deliveryTime': {'count': 2, 'avgSeconds': 1717.0},
    };

class FakeManagerApi extends ManagerApi {
  FakeManagerApi() : super(Dio());
  final calls = <String>[];
  StatsSummary summaryData = StatsSummary.fromJson(summaryJson());
  List<DailyPoint> dailyData = [
    DailyPoint('2026-11-25', const OrderCounts(total: 2)),
    DailyPoint('2026-11-26', const OrderCounts(total: 0)),
    DailyPoint('2026-11-27', const OrderCounts(total: 5)),
  ];
  List<StationLoad> loads = const [
    StationLoad(stationId: 's1', name: 'Trạm Bến Thành', status: 'ACTIVE', capacity: 4, booked: 3, processing: 2, utilization: 0.75),
  ];
  AuditPage auditPage = AuditPage([
    AuditEvent(id: 'a1', action: 'LOGIN_FAILED', actorEmail: 'x@y.z', occurredAt: DateTime(2026, 11, 27, 9, 5)),
    AuditEvent(id: 'a2', action: 'MY_NEW_ACTION', actorEmail: 'q@y.z'),
  ], 1, 20, 2);
  ApiFailure? failure;
  AiEnvelope<AnalyticsResult> analytics = AiEnvelope(
      result: const AnalyticsResult(
          insights: ['Có 17 đơn.'],
          recommendations: ['Kiểm tra bộ lọc.'],
          narrativeMode: 'AI_SELECTED_VERIFIED_EVIDENCE',
          warnings: ['LOW_DELIVERY_SAMPLE', 'CODE_LA']),
      model: 'gemini',
      mock: false,
      degraded: false,
      source: AiSource.provider,
      requestId: 'r',
      generatedAt: null,
      fallbackReason: null);

  @override
  Future<StatsSummary> summary(int days, {DateTime? now}) async {
    calls.add('summary $days');
    if (failure != null) throw failure!;
    return summaryData;
  }

  @override
  Future<List<DailyPoint>> daily(int days, {DateTime? now}) async {
    calls.add('daily $days');
    return dailyData;
  }

  @override
  Future<List<StationLoad>> stationLoad() async => loads;

  @override
  Future<AuditPage> audit({int page = 1, int pageSize = 20, String? action}) async {
    calls.add('audit action=$action');
    return auditPage;
  }

  @override
  Future<AiEnvelope<AnalyticsResult>> analyze(int days, {DateTime? now, CancelToken? cancel}) async {
    calls.add('analyze $days');
    if (failure != null) throw failure!;
    return analytics;
  }
}

void main() {
  group('định dạng', () {
    test('phần trăm: null là "—", số nguyên bỏ phần lẻ', () {
      expect(formatPercent(null), '—');
      expect(formatPercent(0.5), '50%');
      expect(formatPercent(0.3333), '33.3%');
      expect(formatPercent(0), '0%');
    });
    test('thời lượng', () {
      expect(formatDuration(null), '—');
      expect(formatDuration(30), '30 giây');
      expect(formatDuration(1717), '29 phút');
      expect(formatDuration(3600), '1 giờ');
      expect(formatDuration(3900), '1 giờ 5 phút');
    });
    test('nhãn nhật ký và cảnh báo; mã lạ giữ nguyên', () {
      expect(auditLabel('LOGIN_FAILED'), 'Đăng nhập thất bại');
      expect(auditLabel('MY_NEW_ACTION'), 'MY_NEW_ACTION');
      expect(warningLabel('LOW_DELIVERY_SAMPLE'), contains('Mẫu giao hàng ít'));
      expect(warningLabel('CODE_LA'), 'CODE_LA');
      expect(DailyBars.shortDate('2026-11-27'), '27/11');
    });
  });

  group('model', () {
    test('summary đọc đủ trường; tỷ lệ null khi mẫu rỗng', () {
      final s = StatsSummary.fromJson(summaryJson());
      expect(s.orders.total, 17);
      expect(s.orders.inProgress, 8);
      expect(s.byStatus['DELIVERED'], 3);
      expect(s.successRate, 0.5);
      expect(s.avgDeliverySeconds, 1717.0);
      expect(s.retryAttempts, 2);
      expect(StatsSummary.fromJson(summaryJson(total: 0, success: null)).successRate, isNull);
    });
    test('phân tích đọc nhận định, cảnh báo và chế độ', () {
      final r = AnalyticsResult.fromJson({
        'insights': ['a'],
        'recommendations': ['b'],
        'narrativeMode': 'DETERMINISTIC_FALLBACK',
        'analysis': {'warnings': ['W']}
      });
      expect(r.isFallback, isTrue);
      expect(r.warnings, ['W']);
    });
    test('trang nhật ký biết còn trang sau không', () {
      expect(AuditPage.fromJson({'items': <dynamic>[], 'page': 1, 'pageSize': 20, 'total': 45}).hasMore, isTrue);
      expect(AuditPage.fromJson({'items': <dynamic>[], 'page': 3, 'pageSize': 20, 'total': 45}).hasMore, isFalse);
    });
  });

  group('ManagerApi gửi đúng yêu cầu', () {
    test('khoảng ngày theo giờ Việt Nam; phân tích gửi from/to', () async {
      final seen = <RequestOptions>[];
      final dio = Dio(BaseOptions(baseUrl: 'http://x'))
        ..httpClientAdapter = FakeAdapter((o) {
          seen.add(o);
          if (o.path.endsWith('/analytics')) return jsonResponse({'result': {}, 'source': 'FALLBACK'});
          if (o.path.endsWith('/summary')) return jsonResponse(summaryJson());
          return jsonResponse(<dynamic>[]);
        });
      final api = ManagerApi(dio);
      // 18:30 UTC ngày 26 là 01:30 ngày 27 ở Việt Nam.
      final now = DateTime.utc(2026, 11, 26, 18, 30);
      await api.summary(7, now: now);
      await api.daily(30, now: now);
      await api.analyze(7, now: now);
      expect(seen[0].queryParameters, {'from': '2026-11-21', 'to': '2026-11-27'});
      expect(seen[1].queryParameters['from'], '2026-10-29');
      expect(seen[2].method, 'POST');
      expect(seen[2].data, {'from': '2026-11-21', 'to': '2026-11-27'});
    });
  });

  test('định tuyến: quản lý hậu cần vào /manager, không sang route của vai trò khác', () async {
    final session = makeSession(
        store: MemoryTokens(),
        api: FakeAdapter((_) => jsonResponse({})),
        auth: FakeAdapter((_) => jsonResponse(tokenResponse(role: 'LOGISTICS_MANAGER'))));
    addTearDown(session.dispose);
    await session.login('m@test.com', 'secret');
    expect(session.user!.supported, isTrue);
    expect(session.user!.home, '/manager');
    expect(sessionRedirect(session, '/manager'), isNull);
    expect(sessionRedirect(session, '/profile'), isNull);
    expect(sessionRedirect(session, '/dispatcher'), '/manager');
    expect(sessionRedirect(session, '/customer'), '/manager');
  });

  group('màn hình', () {
    late FakeManagerApi api;

    Future<void> mount(WidgetTester tester) async {
      tester.view.physicalSize = const Size(1170, 2532);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      final session = makeSession(
          store: MemoryTokens()..value = const Tokens('a', 'r'),
          api: FakeAdapter((_) => jsonResponse(profile(role: 'LOGISTICS_MANAGER', name: 'Phạm Quản Lý'))),
          auth: FakeAdapter((_) => jsonResponse({})));
      await tester.runAsync(session.restore);
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (_, __) => const ManagerHomeScreen()),
        GoRoute(path: '/profile', builder: (_, __) => const Scaffold(body: Text('PROFILE'))),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            sessionProvider.overrideWith((ref) => session),
            realtimeClientProvider.overrideWith((ref) => null),
            managerApiProvider.overrideWithValue(api),
          ],
          child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
      await tester.pumpAndSettle();
    }

    setUp(() => api = FakeManagerApi());

    testWidgets('tổng quan: tên, số liệu, tỷ lệ, phân bố trạng thái, biểu đồ theo ngày', (tester) async {
      await mount(tester);
      expect(find.textContaining('Phạm Quản Lý'), findsOneWidget);
      expect(find.byKey(const Key('manager-title')), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(const Key('kpi-total'))).data, '17');
      expect(tester.widget<Text>(find.byKey(const Key('kpi-delivered'))).data, '3');
      expect(tester.widget<Text>(find.byKey(const Key('kpi-success'))).data, '50%');
      expect(find.text('Mẫu 6 đơn'), findsOneWidget);
      expect(find.byKey(const Key('bar-DELIVERED')), findsOneWidget);
      expect(find.byKey(const Key('bar-PENDING')), findsNothing, reason: 'trạng thái 0 đơn bị ẩn');
      await tester.scrollUntilVisible(find.byKey(const Key('daily-chart')), 300,
          scrollable: find.byType(Scrollable).first);
      expect(find.byKey(const Key('daily-chart')), findsOneWidget);
      expect(find.text('Cao nhất 5 đơn (27/11)'), findsOneWidget);
    });

    testWidgets('đổi kỳ 7 ngày: tải lại số liệu của kỳ mới', (tester) async {
      await mount(tester);
      expect(api.calls, containsAll(['summary 30', 'daily 30']));
      await tester.tap(find.byKey(const Key('period-7')));
      await tester.pumpAndSettle();
      expect(api.calls, containsAll(['summary 7', 'daily 7']));
    });

    testWidgets('mẫu rỗng: tỷ lệ hiện "—", không hiện 0%', (tester) async {
      api.summaryData = StatsSummary.fromJson(summaryJson(total: 0, success: null));
      await mount(tester);
      expect(tester.widget<Text>(find.byKey(const Key('kpi-success'))).data, '—');
      expect(find.text('Chưa có đơn kết thúc'), findsOneWidget);
    });

    testWidgets('lỗi tải: báo lỗi và Thử lại phục hồi được', (tester) async {
      api.failure = const ApiFailure(FailureKind.offline, 'Không có mạng');
      await mount(tester);
      expect(find.byKey(const Key('stats-error')), findsOneWidget);
      api.failure = null;
      await tester.tap(find.byKey(const Key('stats-retry')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('kpi-total')), findsOneWidget);
    });

    testWidgets('nhận định chỉ chạy khi bấm; hiện nhận định, cảnh báo đã dịch và nhãn nguồn AI', (tester) async {
      await mount(tester);
      await tester.scrollUntilVisible(find.byKey(const Key('analytics-run')), 300,
          scrollable: find.byType(Scrollable).first);
      expect(api.calls.any((e) => e.startsWith('analyze')), isFalse, reason: 'không tự chạy');
      await tester.tap(find.byKey(const Key('analytics-run')));
      await tester.pumpAndSettle();
      expect(api.calls, contains('analyze 30'));
      expect(find.text('• Có 17 đơn.'), findsOneWidget);
      expect(find.text('→ Kiểm tra bộ lọc.'), findsOneWidget);
      expect(find.byKey(const Key('analytics-mode')), findsOneWidget);
      expect(find.textContaining('AI chỉ chọn'), findsOneWidget);
      expect(find.byKey(const Key('ai-source')), findsOneWidget);
    });

    testWidgets('nhận định dự phòng: nói rõ không dùng AI', (tester) async {
      api.analytics = AiEnvelope(
          result: const AnalyticsResult(
              insights: ['x'], recommendations: [], narrativeMode: 'DETERMINISTIC_FALLBACK', warnings: []),
          model: null,
          mock: false,
          degraded: true,
          source: AiSource.fallback,
          requestId: 'r',
          generatedAt: null,
          fallbackReason: 'AI_TIMEOUT');
      await mount(tester);
      await tester.scrollUntilVisible(find.byKey(const Key('analytics-run')), 300,
          scrollable: find.byType(Scrollable).first);
      await tester.tap(find.byKey(const Key('analytics-run')));
      await tester.pumpAndSettle();
      expect(find.textContaining('không dùng AI'), findsWidgets);
    });

    testWidgets('lỗi phân tích: báo lỗi tiếng Việt, đổi kỳ thì kết quả cũ bị bỏ', (tester) async {
      await mount(tester);
      await tester.scrollUntilVisible(find.byKey(const Key('analytics-run')), 300,
          scrollable: find.byType(Scrollable).first);
      await tester.tap(find.byKey(const Key('analytics-run')));
      await tester.pumpAndSettle();
      expect(find.text('• Có 17 đơn.'), findsOneWidget);
      await tester.scrollUntilVisible(find.byKey(const Key('period-7')), -300,
          scrollable: find.byType(Scrollable).first);
      await tester.tap(find.byKey(const Key('period-7')));
      await tester.pumpAndSettle();
      expect(find.text('• Có 17 đơn.'), findsNothing);
    });

    testWidgets('tab Trạm: tải, mức sử dụng, ghi chú khung giờ hiện tại', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('nav-stations')));
      await tester.pumpAndSettle();
      expect(find.text('Trạm Bến Thành'), findsOneWidget);
      expect(find.text('3/4'), findsOneWidget);
      expect(find.text('2 kiện'), findsOneWidget);
      expect(find.text('75%'), findsOneWidget);
      expect(find.byKey(const Key('load-note')), findsOneWidget);
    });

    testWidgets('tab Nhật ký: nhãn tiếng Việt, mã lạ giữ nguyên, lọc theo hành động', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('nav-audit')));
      await tester.pumpAndSettle();
      expect(find.text('Đăng nhập thất bại'), findsWidgets);
      expect(find.text('MY_NEW_ACTION'), findsOneWidget);
      await tester.tap(find.byKey(const Key('audit-filter-LOGIN_FAILED')));
      await tester.pumpAndSettle();
      expect(api.calls, contains('audit action=LOGIN_FAILED'));
    });

    testWidgets('Hồ sơ mở màn hồ sơ', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('nav-profile')));
      await tester.pumpAndSettle();
      expect(find.text('PROFILE'), findsOneWidget);
    });
  });
}
