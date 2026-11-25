import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme/app_theme.dart';
import '../../core/ui/app_widgets.dart';
import '../auth/session.dart';
import 'customer_nav.dart';
import 'customer_order_models.dart';
import 'order_list_controller.dart';
import 'order_models.dart' show statusLabels;
import 'order_screens.dart';

/// Màn chính của khách: danh sách đơn của tôi, lọc theo trạng thái, phân trang.
class OrderListScreen extends ConsumerStatefulWidget {
  const OrderListScreen({super.key});
  @override
  ConsumerState<OrderListScreen> createState() => _OrderListScreenState();
}

class _OrderListScreenState extends ConsumerState<OrderListScreen> {
  final _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      if (_scroll.hasClients &&
          _scroll.position.extentAfter < 300) {
        ref.read(orderListProvider.notifier).loadMore();
      }
    });
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(sessionProvider).user!;
    final state = ref.watch(orderListProvider);
    final controller = ref.read(orderListProvider.notifier);
    return Scaffold(
        appBar: AppBar(title: const Text('Đơn hàng của tôi')),
        bottomNavigationBar: const CustomerBottomNav(selectedIndex: 1),
        body: Column(children: [
          const ConnectionBanner(),
          StatusFilterBar(selected: state.status, onSelected: controller.setStatus),
          if (state.moreError != null && state.items.isNotEmpty && !state.loadingMore)
            MessageBanner(
                key: const Key('list-error-banner'),
                text: 'Chưa cập nhật được: ${state.moreError!.message}',
                color: StatusPalette.failed.background,
                onClose: controller.reload),
          Expanded(
              child: RefreshIndicator(
                  onRefresh: controller.reload,
                  child: _body(context, user.name.isEmpty ? user.email : user.name,
                      state, controller))),
        ]));
  }

  Widget _body(BuildContext context, String name, OrderListState state,
      OrderListController controller) {
    if (state.loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (state.error != null) {
      return ListView(children: [
        Padding(
            padding: const EdgeInsets.all(24),
            child: Column(children: [
              Text(state.error!.message, key: const Key('list-error')),
              const SizedBox(height: 12),
              FilledButton(
                  onPressed: controller.reload, child: const Text('Thử lại')),
            ]))
      ]);
    }
    if (state.items.isEmpty) {
      final text = state.status == null
          ? 'Xin chào, $name. Bạn chưa có đơn hàng nào.'
          : 'Không có đơn nào ở trạng thái "${statusLabels[state.status] ?? state.status}".';
      return ListView(children: [
        Padding(
            padding: const EdgeInsets.all(32),
            child: Text(text, key: const Key('list-empty'), textAlign: TextAlign.center))
      ]);
    }
    return ListView.separated(
        controller: _scroll,
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 88),
        itemCount: state.items.length + (state.hasMore ? 1 : 0),
        separatorBuilder: (_, __) => const SizedBox(height: 8),
        itemBuilder: (context, i) {
          if (i < state.items.length) return OrderCard(state.items[i]);
          if (state.moreError != null) {
            return Center(
                child: TextButton(
                    key: const Key('load-more-retry'),
                    onPressed: controller.loadMore,
                    child: const Text('Tải thêm không được. Thử lại')));
          }
          // Cho tải tiếp ngay cả khi danh sách ngắn hơn màn hình.
          WidgetsBinding.instance.addPostFrameCallback((_) => controller.loadMore());
          return const Padding(
              padding: EdgeInsets.all(16),
              child: Center(child: CircularProgressIndicator()));
        });
  }
}

/// Hàng chip lọc theo trạng thái (null = tất cả).
class StatusFilterBar extends StatelessWidget {
  const StatusFilterBar(
      {super.key, required this.selected, required this.onSelected});
  final String? selected;
  final ValueChanged<String?> onSelected;

  @override
  Widget build(BuildContext context) => SizedBox(
      height: 52,
      child: ListView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          children: [
            _chip('Tất cả', null),
            for (final e in statusLabels.entries) _chip(e.value, e.key),
          ]));

  Widget _chip(String label, String? status) => Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(
          key: Key('filter-${status ?? 'ALL'}'),
          label: Text(label),
          selected: selected == status,
          onSelected: (_) => onSelected(status)));
}

class OrderCard extends StatelessWidget {
  const OrderCard(this.order, {super.key});
  final OrderSummary order;
  @override
  Widget build(BuildContext context) => OrderListCard(
      key: Key('order-${order.id}'),
      title: 'Đơn ${order.shortCode}',
      status: order.status,
      statusLabel: order.statusLabel,
      lines: [
        (Icons.person_outline, 'Người nhận: ${order.recipientName}'),
        (Icons.inventory_2_outlined, order.packageDescription ?? ''),
      ],
      time: formatTime(order.createdAt),
      onTap: () => context.go('/customer/orders/${order.id}/detail'));
}
