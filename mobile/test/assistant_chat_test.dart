import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/features/ai/ai_api.dart';
import 'package:smartdrone_mobile/features/ai/ai_errors.dart';
import 'package:smartdrone_mobile/features/ai/ai_models.dart';
import 'package:smartdrone_mobile/features/assistant/assistant_screen.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/customer_orders_api.dart';

const oid = '11111111-1111-1111-1111-111111111111';

DioException http(int status, String code) => DioException(
    requestOptions: RequestOptions(path: '/x'),
    response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: status,
        data: {'code': code, 'message': 'msg $code'}));

AiEnvelope<ChatResult> reply(String text,
        {String conversationId = 'c1',
        AiSource source = AiSource.mock,
        bool mock = true}) =>
    AiEnvelope(
        result: ChatResult(
            reply: text,
            conversationId: conversationId,
            sources: const [ChatSourceRef('scope', 'Phạm vi dữ liệu khách hàng')]),
        model: mock ? 'mock' : 'gemini',
        mock: mock,
        degraded: false,
        source: source,
        requestId: 'r',
        generatedAt: null,
        fallbackReason: null);

class ChatCall {
  ChatCall(this.message, this.conversationId, this.orderId, this.token);
  final String message;
  final String? conversationId, orderId;
  final CancelToken? token;
}

class FakeChatAi extends AiApi {
  FakeChatAi() : super(Dio());
  final calls = <ChatCall>[];
  Future<AiEnvelope<ChatResult>> Function(ChatCall) onChat =
      (c) async => reply('Xin chào');

  @override
  Future<AiEnvelope<ChatResult>> chat(String message,
      {String? conversationId, String? orderId, CancelToken? cancel}) {
    final call = ChatCall(message, conversationId, orderId, cancel);
    calls.add(call);
    return onChat(call);
  }
}

class FakeOrders extends CustomerOrdersApi {
  FakeOrders() : super(Dio());
  @override
  Future<OrderPage> listOrders(
          {int page = 1,
          int pageSize = 20,
          String? status,
          DateTime? from,
          DateTime? to}) async =>
      OrderPage(page: 1, pageSize: 50, total: 1, items: [
        OrderSummary(
            id: oid,
            status: 'SCHEDULED',
            statusLabel: 'Đã lên lịch',
            pickupStationId: 's1',
            dropoffStationId: 's2',
            recipientName: 'An',
            recipientAddressLine: '1 Lê Lợi')
      ]);
}

void main() {
  late FakeChatAi ai;
  late StateProvider<String?> user;

  setUp(() {
    ai = FakeChatAi();
    user = StateProvider<String?>((ref) => 'user-1');
  });

  Future<void> mount(WidgetTester tester) async {
    tester.view.physicalSize = const Size(800, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
        overrides: [
          aiApiProvider.overrideWithValue(ai),
          customerOrdersApiProvider.overrideWithValue(FakeOrders()),
          realtimeUserProvider.overrideWith((ref) => ref.watch(user)),
        ],
        child: const MaterialApp(home: AssistantScreen())));
    await tester.pumpAndSettle();
  }

  Future<void> ask(WidgetTester tester, String text) async {
    await tester.enterText(find.byKey(const Key('chat-input')), text);
    await tester.pump();
    await tester.tap(find.byKey(const Key('chat-send')));
    await tester.pumpAndSettle();
  }

  bool sendEnabled(WidgetTester tester) =>
      tester.widget<IconButton>(find.byKey(const Key('chat-send'))).onPressed != null;

  testWidgets('không còn màn giữ chỗ/nhãn Demo; nút gửi khóa khi ô nhập trống',
      (tester) async {
    await mount(tester);
    expect(find.text('Demo'), findsNothing);
    expect(find.textContaining('sắp có'), findsNothing);
    expect(find.byKey(const Key('chat-empty')), findsOneWidget);
    expect(find.textContaining('Không nhập mật khẩu'), findsOneWidget);
    expect(sendEnabled(tester), isFalse);
  });

  testWidgets('gửi: lượt đầu không có conversationId, lượt sau gửi lại; xóa ô nhập; nhãn MOCK',
      (tester) async {
    await mount(tester);
    await ask(tester, 'Đơn của tôi đâu?');
    expect(ai.calls.single.conversationId, isNull);
    expect(ai.calls.single.orderId, isNull);
    expect(find.text('Đơn của tôi đâu?'), findsOneWidget);
    expect(find.text('Xin chào'), findsOneWidget);
    expect(find.text('Giả lập (MOCK)'), findsOneWidget);
    expect(tester.widget<TextField>(find.byKey(const Key('chat-input'))).controller!.text,
        isEmpty);
    await ask(tester, 'Tiếp theo?');
    expect(ai.calls.last.conversationId, 'c1');
  });

  testWidgets('đang chờ: khóa nút gửi và ô nhập, không gửi chồng', (tester) async {
    final gate = Completer<AiEnvelope<ChatResult>>();
    ai.onChat = (_) => gate.future;
    await mount(tester);
    await tester.enterText(find.byKey(const Key('chat-input')), 'Hỏi');
    await tester.pump();
    await tester.tap(find.byKey(const Key('chat-send')));
    await tester.pump();
    expect(find.byKey(const Key('chat-pending')), findsOneWidget);
    expect(sendEnabled(tester), isFalse);
    expect(ai.calls.length, 1);
    gate.complete(reply('Xong'));
    await tester.pumpAndSettle();
    expect(find.text('Xong'), findsOneWidget);
  });

  testWidgets('chọn đơn: gửi kèm orderId; đổi đơn thì bắt đầu hội thoại mới',
      (tester) async {
    await mount(tester);
    await tester.tap(find.byKey(const Key('chat-order')));
    await tester.pumpAndSettle();
    await tester.tap(find.textContaining('11111111').last);
    await tester.pumpAndSettle();
    await ask(tester, 'Trạng thái?');
    expect(ai.calls.single.orderId, oid);
    expect(find.text('Trạng thái?'), findsOneWidget);

    await tester.tap(find.byKey(const Key('chat-order')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Hỏi chung, không gắn đơn').last);
    await tester.pumpAndSettle();
    expect(find.text('Trạng thái?'), findsNothing); // hội thoại cũ đã xóa
    await ask(tester, 'Câu hỏi chung');
    expect(ai.calls.last.conversationId, isNull);
    expect(ai.calls.last.orderId, isNull);
  });

  testWidgets('429 hết hạn mức: báo lỗi, giữ chữ đã gõ', (tester) async {
    ai.onChat = (_) async => throw http(429, 'AI_RATE_LIMITED');
    await mount(tester);
    await ask(tester, 'Hỏi nhiều quá');
    expect(find.byKey(const Key('chat-error')), findsOneWidget);
    expect(find.textContaining('vượt giới hạn'), findsOneWidget);
    expect(tester.widget<TextField>(find.byKey(const Key('chat-input'))).controller!.text,
        'Hỏi nhiều quá');
    expect(find.text('Hỏi nhiều quá'), findsOneWidget); // chỉ ở ô nhập, chưa thành lượt hội thoại
  });

  testWidgets('lỗi mạng: cảnh báo có thể đã xử lý; sau 3 lần thất bại thì khóa gửi',
      (tester) async {
    ai.onChat = (_) async => throw DioException(
        requestOptions: RequestOptions(path: '/x'),
        type: DioExceptionType.connectionError);
    await mount(tester);
    await tester.enterText(find.byKey(const Key('chat-input')), 'Alo');
    await tester.pump();
    for (var i = 0; i < maxAiFailures; i++) {
      expect(sendEnabled(tester), isTrue, reason: 'lần ${i + 1}');
      await tester.tap(find.byKey(const Key('chat-send')));
      await tester.pumpAndSettle();
    }
    expect(ai.calls.length, maxAiFailures);
    expect(find.textContaining('có thể đã được xử lý'), findsOneWidget);
    expect(find.textContaining('thất bại nhiều lần'), findsOneWidget);
    expect(sendEnabled(tester), isFalse);
    expect(find.byKey(const Key('chat-new')), findsOneWidget);
    await tester.tap(find.byKey(const Key('chat-new')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('chat-error')), findsNothing);
  });

  testWidgets('hội thoại hết hạn (404): đề nghị cuộc trò chuyện mới', (tester) async {
    await mount(tester);
    await ask(tester, 'Câu 1');
    ai.onChat = (_) async => throw http(404, 'RESOURCE_NOT_FOUND');
    await ask(tester, 'Câu 2');
    expect(find.textContaining('hết hạn'), findsWidgets);
    expect(find.byKey(const Key('chat-new')), findsOneWidget);
    await tester.tap(find.byKey(const Key('chat-new')));
    await tester.pumpAndSettle();
    ai.onChat = (_) async => reply('Mới', conversationId: 'c2');
    await ask(tester, 'Câu 3');
    expect(ai.calls.last.conversationId, isNull); // không dùng lại conversationId cũ
  });

  testWidgets('đăng xuất/đổi tài khoản: xóa toàn bộ hội thoại và conversationId',
      (tester) async {
    await mount(tester);
    await ask(tester, 'Bí mật của tài khoản 1');
    expect(find.text('Bí mật của tài khoản 1'), findsOneWidget);
    final container = ProviderScope.containerOf(tester.element(find.byType(AssistantScreen)));
    container.read(user.notifier).state = 'user-2';
    await tester.pumpAndSettle();
    expect(find.text('Bí mật của tài khoản 1'), findsNothing);
    expect(find.text('Xin chào'), findsNothing);
    await ask(tester, 'Câu của tài khoản 2');
    expect(ai.calls.last.conversationId, isNull);
  });

  testWidgets('rời màn hình thì hủy yêu cầu đang chờ', (tester) async {
    ai.onChat = (_) => Completer<AiEnvelope<ChatResult>>().future;
    await mount(tester);
    await tester.enterText(find.byKey(const Key('chat-input')), 'Hỏi');
    await tester.pump();
    await tester.tap(find.byKey(const Key('chat-send')));
    await tester.pump();
    final token = ai.calls.single.token!;
    expect(token.isCancelled, isFalse);
    await tester.pumpWidget(const SizedBox());
    expect(token.isCancelled, isTrue);
  });

  testWidgets('trả lời chứa HTML/liên kết chỉ hiển thị như text', (tester) async {
    const nasty = '<a href="http://evil">bấm</a> <script>x()</script>';
    ai.onChat = (_) async => reply(nasty);
    await mount(tester);
    await ask(tester, 'Thử');
    expect(find.text(nasty), findsOneWidget);
  });

  testWidgets('chỉ AI thật (PROVIDER) mới có nhãn AI', (tester) async {
    ai.onChat = (_) async => reply('Thật', source: AiSource.provider, mock: false);
    await mount(tester);
    await ask(tester, 'Thử');
    expect(find.text('AI (gemini)'), findsOneWidget);
    expect(find.text('Giả lập (MOCK)'), findsNothing);
  });
}
