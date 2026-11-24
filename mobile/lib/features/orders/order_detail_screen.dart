import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'customer_order_models.dart';
import 'order_detail_controller.dart';
import 'order_screens.dart';

/// Chi tiết một đơn của khách. Route `/customer/orders/:id/detail`; từ đây
/// sang màn theo dõi (6.3) qua `/customer/orders/:id`.
class OrderDetailScreen extends ConsumerWidget {
  const OrderDetailScreen({super.key, required this.orderId});
  final String orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(orderDetailProvider(orderId));
    final controller = ref.read(orderDetailProvider(orderId).notifier);
    final stations = ref.watch(stationsProvider).valueOrNull;
    String stationName(String id) {
      for (final s in stations ?? const <Station>[]) {
        if (s.id == id) return s.name;
      }
      return 'Trạm ${id.length >= 8 ? id.substring(0, 8) : id}';
    }

    final order = state.order;
    return Scaffold(
        appBar: AppBar(
            title: Text(order == null
                ? 'Chi tiết đơn'
                : 'Đơn ${order.shortCode}'),
            leading: IconButton(
                tooltip: 'Quay lại',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => context.go('/customer')),
            actions: [
              IconButton(
                  tooltip: 'Tải lại',
                  onPressed: () => controller.reload(),
                  icon: const Icon(Icons.refresh))
            ]),
        body: Column(children: [
          const ConnectionBanner(),
          if (state.notice != null)
            _Banner(
                key: const Key('detail-notice'),
                text: state.notice!,
                color: Theme.of(context).colorScheme.tertiaryContainer,
                onClose: controller.dismissNotice),
          if (state.actionError != null)
            _Banner(
                key: const Key('detail-action-error'),
                text: state.actionError!.message,
                color: Theme.of(context).colorScheme.errorContainer,
                onClose: controller.dismissNotice),
          Expanded(child: _body(context, ref, state, order, controller, stationName)),
        ]),
        bottomNavigationBar: order == null
            ? null
            : SafeArea(
                child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Row(children: [
                      Expanded(
                          child: OutlinedButton.icon(
                              key: const Key('open-tracking'),
                              onPressed: () =>
                                  context.go('/customer/orders/${order.id}'),
                              icon: const Icon(Icons.timeline),
                              label: const Text('Theo dõi đơn'))),
                      if (order.canCancel) ...[
                        const SizedBox(width: 12),
                        Expanded(
                            child: FilledButton.icon(
                                key: const Key('cancel-order'),
                                style: FilledButton.styleFrom(
                                    backgroundColor:
                                        Theme.of(context).colorScheme.error),
                                // Vô hiệu hóa khi đang gửi: chặn bấm đúp.
                                onPressed: state.cancelling
                                    ? null
                                    : () => _confirmCancel(context, controller),
                                icon: state.cancelling
                                    ? const SizedBox(
                                        width: 16,
                                        height: 16,
                                        child: CircularProgressIndicator(
                                            strokeWidth: 2))
                                    : const Icon(Icons.cancel_outlined),
                                label: const Text('Hủy đơn'))),
                      ],
                    ]))));
  }

  Future<void> _confirmCancel(
      BuildContext context, OrderDetailController controller) async {
    final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
                title: const Text('Hủy đơn hàng?'),
                content: const Text(
                    'Đơn sẽ bị hủy và không thể khôi phục. Bạn có chắc chắn không?'),
                actions: [
                  TextButton(
                      key: const Key('cancel-dismiss'),
                      onPressed: () => Navigator.of(ctx).pop(false),
                      child: const Text('Giữ đơn')),
                  FilledButton(
                      key: const Key('cancel-confirm'),
                      onPressed: () => Navigator.of(ctx).pop(true),
                      child: const Text('Hủy đơn')),
                ]));
    if (ok != true) return;
    final cancelled = await controller.cancel();
    if (cancelled && context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Đã hủy đơn.')));
    }
  }

  Widget _body(
      BuildContext context,
      WidgetRef ref,
      OrderDetailState state,
      OrderDetail? order,
      OrderDetailController controller,
      String Function(String) stationName) {
    if (order == null) {
      if (state.error != null) {
        return Center(
            child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Text(state.error!.message, key: const Key('detail-error')),
                  const SizedBox(height: 12),
                  FilledButton(
                      onPressed: () => controller.reload(),
                      child: const Text('Thử lại')),
                ])));
      }
      return const Center(child: CircularProgressIndicator());
    }
    final theme = Theme.of(context);
    final p = order.package;
    return RefreshIndicator(
        onRefresh: () => controller.reload(),
        child: ListView(padding: const EdgeInsets.all(16), children: [
          Row(children: [
            Expanded(
                child: Text(order.statusLabel,
                    key: const Key('detail-status'),
                    style: theme.textTheme.headlineSmall)),
            StatusChip(order.status, order.statusLabel),
          ]),
          const SizedBox(height: 4),
          Text('Tạo lúc ${formatTime(order.createdAt)}',
              style: theme.textTheme.bodySmall),
          const SizedBox(height: 16),
          _Section('Lộ trình', [
            _Row('Trạm gửi', stationName(order.pickupStationId)),
            _Row('Trạm nhận', stationName(order.dropoffStationId)),
          ]),
          _Section('Người nhận', [
            _Row('Họ tên', order.recipient.name),
            _Row('Điện thoại', order.recipient.phone),
            _Row('Địa chỉ', order.recipient.addressLine),
          ]),
          _Section('Gói hàng', [
            _Row('Mô tả', p.description),
            _Row('Trọng lượng', '${p.weightGrams} g'),
            _Row('Kích thước',
                '${_num(p.lengthCm)} × ${_num(p.widthCm)} × ${_num(p.heightCm)} cm'),
            _Row('Ảnh', p.photoFileIds.isEmpty ? 'Không có' : '${p.photoFileIds.length} ảnh'),
          ]),
          if (order.history.isNotEmpty) ...[
            Text('Lịch sử', style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            for (final h in order.history.reversed)
              ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.circle, size: 10),
                  title: Text(h.toLabel),
                  subtitle: Text([
                    formatTime(h.occurredAt),
                    if (h.reason != null && h.reason!.isNotEmpty) h.reason!
                  ].join('\n'))),
          ],
        ]));
  }
}

String _num(double v) => v == v.roundToDouble() ? v.toInt().toString() : v.toString();

class _Banner extends StatelessWidget {
  const _Banner(
      {super.key, required this.text, required this.color, required this.onClose});
  final String text;
  final Color color;
  final VoidCallback onClose;
  @override
  Widget build(BuildContext context) => Container(
      width: double.infinity,
      color: color,
      padding: const EdgeInsets.only(left: 12, top: 4, bottom: 4),
      child: Row(children: [
        Expanded(child: Text(text)),
        IconButton(
            tooltip: 'Đóng', onPressed: onClose, icon: const Icon(Icons.close)),
      ]));
}

class _Section extends StatelessWidget {
  const _Section(this.title, this.rows);
  final String title;
  final List<Widget> rows;
  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        ...rows,
      ]));
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value);
  final String label, value;
  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
            width: 100,
            child: Text(label, style: Theme.of(context).textTheme.bodySmall)),
        Expanded(child: Text(value)),
      ]));
}
