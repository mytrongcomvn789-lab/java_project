import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/realtime/realtime_provider.dart';
import '../ai/ai_api.dart';
import '../ai/ai_errors.dart';
import '../ai/ai_models.dart';
import '../orders/customer_order_models.dart';
import '../orders/customer_orders_api.dart';

/// Khớp smartdrone.ai.max-chat-message-chars (mặc định 1000); server vẫn kiểm tra lại.
const maxChatChars = 1000;

class ChatTurn {
  const ChatTurn(this.id, this.fromUser, this.text, [this.envelope]);
  final int id;
  final bool fromUser;

  /// Văn bản không tin cậy: chỉ hiển thị dạng text.
  final String text;

  /// Chỉ có ở lượt trả lời của trợ lý.
  final AiEnvelope<ChatResult>? envelope;
}

class ChatState {
  const ChatState(
      {this.turns = const [],
      this.conversationId,
      this.orderId,
      this.pending = false,
      this.error,
      this.failures = 0});

  final List<ChatTurn> turns;

  /// Do server tạo; chỉ nằm trong bộ nhớ, xóa khi đổi đơn/đăng xuất.
  final String? conversationId, orderId;
  final bool pending;
  final AiErrorInfo? error;
  final int failures;

  bool get exhausted => failures >= maxAiFailures;
}

/// Đơn của chính khách để gắn vào hội thoại (tùy chọn). Dựng lại khi đổi tài khoản.
final chatOrdersProvider = FutureProvider.autoDispose<List<OrderSummary>>((ref) async {
  ref.watch(realtimeUserProvider);
  final page = await ref.read(customerOrdersApiProvider).listOrders(pageSize: 50);
  return page.items;
});

/// Chat khách hàng (7.4/7.6), chỉ đọc. Hủy yêu cầu khi rời màn hình, bỏ phản hồi muộn,
/// xóa toàn bộ hội thoại khi đăng xuất/đổi tài khoản (provider dựng lại).
class ChatController extends AutoDisposeNotifier<ChatState> {
  CancelToken? _token;
  int _nextId = 1;

  @override
  ChatState build() {
    ref.watch(realtimeUserProvider);
    ref.onDispose(() => _token?.cancel());
    return const ChatState();
  }

  void _cancel() {
    _token?.cancel();
    _token = null;
  }

  /// Server không cho đổi đơn trong cùng hội thoại: chọn đơn khác = hội thoại mới.
  void selectOrder(String? orderId) {
    _cancel();
    state = ChatState(orderId: orderId);
  }

  void reset() {
    _cancel();
    state = ChatState(orderId: state.orderId);
  }

  /// Trả true nếu đã nhận được câu trả lời (màn hình khi đó xóa ô nhập).
  Future<bool> send(String raw) async {
    final text = raw.trim();
    if (text.isEmpty || text.length > maxChatChars) return false;
    if (state.pending || state.exhausted) return false;
    _cancel();
    final token = CancelToken();
    _token = token;
    final before = state;
    state = ChatState(
        turns: before.turns,
        conversationId: before.conversationId,
        orderId: before.orderId,
        pending: true,
        failures: before.failures);
    try {
      final env = await ref.read(aiApiProvider).chat(text,
          conversationId: before.conversationId,
          orderId: before.orderId,
          cancel: token);
      if (_token != token) return false;
      final result = env.result;
      if (result == null) {
        throw const FormatException('empty chat result');
      }
      state = ChatState(
          turns: [
            ...before.turns,
            ChatTurn(_nextId++, true, text),
            ChatTurn(_nextId++, false, result.reply, env),
          ],
          conversationId: result.conversationId,
          orderId: before.orderId);
      return true;
    } on AiCanceled {
      return false;
    } catch (e) {
      if (_token != token) return false;
      state = ChatState(
          turns: before.turns,
          conversationId: before.conversationId,
          orderId: before.orderId,
          failures: before.failures + 1,
          error: e is FormatException
              ? describeAiFailure(Object())
              : describeAiFailure(e));
      return false;
    }
  }
}

final chatProvider =
    NotifierProvider.autoDispose<ChatController, ChatState>(ChatController.new);
