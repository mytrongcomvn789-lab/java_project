import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme/app_theme.dart';
import '../ai/ai_panels.dart';
import '../orders/customer_nav.dart';
import '../orders/customer_order_models.dart';
import 'chat_controller.dart';

/// Trợ lý AI cho khách (7.4/7.6). Chỉ đọc: không hủy/sửa/xác nhận đơn. Hội thoại chỉ nằm trong
/// bộ nhớ: rời màn hình, đổi đơn hay đăng xuất đều xóa. Trả lời là văn bản không tin cậy.
class AssistantScreen extends ConsumerStatefulWidget {
  const AssistantScreen({super.key});
  @override
  ConsumerState<AssistantScreen> createState() => _AssistantScreenState();
}

class _AssistantScreenState extends ConsumerState<AssistantScreen> {
  final _input = TextEditingController();
  final _scroll = ScrollController();

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final ok = await ref.read(chatProvider.notifier).send(_input.text);
    if (!mounted) return;
    if (ok) _input.clear();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(_scroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 150), curve: Curves.easeOut);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final chat = ref.watch(chatProvider);
    final controller = ref.read(chatProvider.notifier);
    final orders = ref.watch(chatOrdersProvider);
    final theme = Theme.of(context);
    final error = chat.error;
    final needsNew = chat.exhausted ||
        (error != null && error.needsNewConversation && chat.conversationId != null);
    return Scaffold(
        appBar: AppBar(
            title: const Row(children: [
          CircleAvatar(
              radius: 16,
              backgroundColor: AppColors.tealBright,
              child: Icon(Icons.smart_toy_outlined, size: 18, color: AppColors.dark)),
          SizedBox(width: 10),
          Text('Trợ lý Drone AI'),
        ])),
        body: Column(children: [
          Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(
                    'Hỏi đáp chỉ đọc về đơn của bạn. Trợ lý không thể hủy, sửa hay xác nhận đơn.',
                    style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
                const SizedBox(height: 8),
                _OrderPicker(
                    orders: orders,
                    selected: chat.orderId,
                    enabled: !chat.pending,
                    onChanged: controller.selectOrder),
                if (chat.orderId != null)
                  Align(
                      alignment: Alignment.centerLeft,
                      child: TextButton(
                          key: const Key('chat-open-order'),
                          onPressed: () =>
                              context.go('/customer/orders/${chat.orderId}/detail'),
                          child: const Text('Xem trạng thái chính thức của đơn'))),
              ])),
          Expanded(
              child: ListView(
                  key: const Key('chat-log'),
                  controller: _scroll,
                  padding: const EdgeInsets.all(16),
                  children: [
                if (chat.turns.isEmpty && !chat.pending)
                  Column(
                      key: const Key('chat-empty'),
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                            'Chưa có tin nhắn. Hãy hỏi, ví dụ: “Đơn của tôi đang ở trạng thái nào?”. '
                            'Không nhập mật khẩu, mã OTP hay thông tin nhạy cảm.',
                            style: TextStyle(color: AppColors.textMuted, height: 1.4)),
                        const SizedBox(height: 12),
                        Wrap(spacing: 8, runSpacing: 8, children: [
                          for (final q in _suggestions)
                            ActionChip(
                                key: Key('suggest-$q'),
                                label: Text(q),
                                backgroundColor: StatusPalette.active.background,
                                labelStyle: TextStyle(
                                    color: StatusPalette.active.foreground,
                                    fontWeight: FontWeight.w600),
                                side: BorderSide.none,
                                onPressed: chat.pending
                                    ? null
                                    : () {
                                        _input.text = q;
                                        setState(() {});
                                      }),
                        ]),
                      ]),
                for (final t in chat.turns) _Bubble(t),
                if (chat.pending)
                  const Padding(
                      padding: EdgeInsets.symmetric(vertical: 8),
                      child: Row(children: [
                        SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2)),
                        SizedBox(width: 10),
                        Text('Trợ lý đang trả lời…',
                            key: Key('chat-pending'),
                            style: TextStyle(color: AppColors.textMuted)),
                      ])),
              ])),
          if (error != null)
            Container(
                key: const Key('chat-error'),
                width: double.infinity,
                color: StatusPalette.failed.background,
                padding: const EdgeInsets.all(12),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(error.message),
                  if (error.outcomeUnknown)
                    Text(
                        'Nếu mạng chập chờn, yêu cầu có thể đã được xử lý; gửi lại có thể tạo thêm một lượt hỏi.',
                        style: theme.textTheme.bodySmall),
                  if (chat.exhausted)
                    Text(
                        'Đã thất bại nhiều lần. Hãy bắt đầu cuộc trò chuyện mới hoặc thử lại sau.',
                        style: theme.textTheme.bodySmall),
                ])),
          SafeArea(
              top: false,
              child: Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
                  child: Column(children: [
                    Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
                      Expanded(
                          child: TextField(
                              key: const Key('chat-input'),
                              controller: _input,
                              enabled: !chat.pending && !chat.exhausted,
                              minLines: 1,
                              maxLines: 4,
                              maxLength: maxChatChars,
                              textInputAction: TextInputAction.newline,
                              decoration: const InputDecoration(hintText: 'Nhập câu hỏi…'),
                              onChanged: (_) => setState(() {}))),
                      const SizedBox(width: 8),
                      IconButton.filled(
                          key: const Key('chat-send'),
                          tooltip: error != null && error.retryable && !chat.exhausted
                              ? 'Gửi lại'
                              : 'Gửi',
                          // Khóa khi đang chờ: không bấm đúp, không gửi chồng.
                          onPressed: chat.pending ||
                                  chat.exhausted ||
                                  _input.text.trim().isEmpty
                              ? null
                              : _send,
                          icon: const Icon(Icons.send)),
                    ]),
                    if (needsNew || chat.turns.isNotEmpty)
                      Align(
                          alignment: Alignment.centerLeft,
                          child: TextButton(
                              key: const Key('chat-new'),
                              onPressed: chat.pending ? null : controller.reset,
                              child: const Text('Cuộc trò chuyện mới'))),
                  ]))),
        ]),
        bottomNavigationBar: const CustomerBottomNav(selectedIndex: 3));
  }
}

const _suggestions = [
  'Đơn của tôi đang ở đâu?',
  'Khi nào đơn đến?',
  'Vì sao đơn chưa được duyệt?',
];

class _OrderPicker extends StatelessWidget {
  const _OrderPicker(
      {required this.orders,
      required this.selected,
      required this.enabled,
      required this.onChanged});
  final AsyncValue<List<OrderSummary>> orders;
  final String? selected;
  final bool enabled;
  final ValueChanged<String?> onChanged;

  @override
  Widget build(BuildContext context) {
    final list = orders.valueOrNull ?? const <OrderSummary>[];
    final known = list.any((o) => o.id == selected);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      DropdownButtonFormField<String?>(
          key: const Key('chat-order'),
          isExpanded: true,
          initialValue: known ? selected : null,
          decoration: const InputDecoration(
              labelText: 'Đơn muốn hỏi (tùy chọn)', isDense: true),
          items: [
            const DropdownMenuItem<String?>(
                value: null, child: Text('Hỏi chung, không gắn đơn')),
            for (final o in list)
              DropdownMenuItem<String?>(
                  value: o.id,
                  child: Text('${o.shortCode} · ${o.statusLabel} · ${o.recipientName}',
                      overflow: TextOverflow.ellipsis)),
          ],
          onChanged: enabled ? onChanged : null),
      if (orders.hasError)
        Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text('Không tải được danh sách đơn. Bạn vẫn có thể hỏi chung.',
                key: const Key('chat-orders-error'),
                style: Theme.of(context).textTheme.bodySmall)),
      Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text('Đổi đơn sẽ bắt đầu cuộc trò chuyện mới.',
              style: Theme.of(context).textTheme.bodySmall)),
    ]);
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble(this.turn);
  final ChatTurn turn;
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final env = turn.envelope;
    final sources = env?.result?.sources ?? const [];
    return Align(
        alignment: turn.fromUser ? Alignment.centerRight : Alignment.centerLeft,
        child: Container(
            margin: const EdgeInsets.only(bottom: 12),
            constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * 0.85),
            // Material (không phải DecoratedBox) để ExpansionTile bên trong vẽ đúng.
            child: Material(
              color: turn.fromUser ? AppColors.primary : AppColors.surface,
              elevation: turn.fromUser ? 0 : 1,
              shadowColor: const Color(0x33073b45),
              borderRadius: BorderRadius.only(
                  topLeft: const Radius.circular(18),
                  topRight: const Radius.circular(18),
                  bottomLeft: Radius.circular(turn.fromUser ? 18 : 4),
                  bottomRight: Radius.circular(turn.fromUser ? 4 : 18)),
              child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              // Văn bản không tin cậy: Text thuần, không diễn giải HTML hay liên kết.
              Text(turn.text,
                  style: TextStyle(
                      color: turn.fromUser ? Colors.white : AppColors.text, height: 1.35)),
              if (env != null) ...[
                const SizedBox(height: 8),
                AiSourceBadge(env),
                if (sources.isNotEmpty)
                  ExpansionTile(
                      tilePadding: EdgeInsets.zero,
                      title: Text('Quy tắc hệ thống được cung cấp cho trợ lý',
                          style: theme.textTheme.bodySmall),
                      children: [
                        for (final s in sources)
                          Align(
                              alignment: Alignment.centerLeft,
                              child: Text('• ${s.title}')),
                        Text(
                            'Đây là danh mục quy tắc, không chứng minh mọi câu trả lời đều đúng.',
                            style: theme.textTheme.bodySmall),
                      ]),
              ],
            ])))));
  }
}
