import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../auth/screens.dart';
import '../auth/session.dart';
import '../notifications/notifications_controller.dart';
import 'customer_order_models.dart';
import 'order_list_controller.dart';
import 'order_models.dart' show statusLabels;
import 'order_screens.dart';

/// Màn chính của khách: danh sách đơn của tôi, lọc theo trạng thái, phân trang.
class CustomerHomeScreen extends ConsumerStatefulWidget {
  const CustomerHomeScreen({super.key});
  @override
  ConsumerState<CustomerHomeScreen> createState() => _CustomerHomeScreenState();
}

class _CustomerHomeScreenState extends ConsumerState<CustomerHomeScreen> {
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
    final unread = ref.watch(notificationsProvider.select((s) => s.unread));
    return Scaffold(
        appBar: AppBar(title: const Text('Đơn hàng của tôi'), actions: [
          IconButton(
              key: const Key('open-notifications'),
              tooltip: 'Thông báo',
              onPressed: () => context.go('/customer/notifications'),
              icon: Badge(
                  isLabelVisible: unread > 0,
                  label: Text('$unread', key: const Key('unread-count')),
                  child: const Icon(Icons.notifications_outlined))),
          IconButton(
              tooltip: 'Hồ sơ',
              onPressed: () => context.go('/profile'),
              icon: const Icon(Icons.account_circle_outlined)),
          IconButton(
              key: const Key('logout'),
              tooltip: 'Đăng xuất',
              onPressed: () => signOut(ref),
              icon: const Icon(Icons.logout)),
        ]),
        body: Column(children: [
          const ConnectionBanner(),
          StatusFilterBar(selected: state.status, onSelected: controller.setStatus),
          if (state.moreError != null && state.items.isNotEmpty && !state.loadingMore)
            Container(
                key: const Key('list-error-banner'),
                width: double.infinity,
                color: Theme.of(context).colorScheme.errorContainer,
                padding: const EdgeInsets.all(8),
                child: Text('Chưa cập nhật được: ${state.moreError!.message}')),
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
        padding: const EdgeInsets.all(12),
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
  Widget build(BuildContext context) => Card(
      child: ListTile(
          key: Key('order-${order.id}'),
          title: Text('Đơn ${order.shortCode}'),
          subtitle: Text(
              'Người nhận: ${order.recipientName}\n${order.packageDescription ?? ''}\n${formatTime(order.createdAt)}'),
          isThreeLine: true,
          trailing: StatusChip(order.status, order.statusLabel),
          onTap: () => context.go('/customer/orders/${order.id}/detail')));
}
