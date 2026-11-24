import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/dio_client.dart';
import '../../core/realtime/realtime_client.dart';
import '../../core/realtime/realtime_provider.dart';
import '../auth/screens.dart';
import '../auth/session.dart';
import '../notifications/notifications_controller.dart';
import 'order_models.dart';
import 'orders_api.dart';
import 'tracking_controller.dart';
import 'tracking_state.dart';

/// Orders of the signed-in customer. Keyed by user so another account never
/// sees a cached list; refreshed when a live notification arrives.
final ordersListProvider =
    FutureProvider.autoDispose<List<OrderListItem>>((ref) {
  ref.watch(realtimeUserProvider);
  ref.listen(notificationsProvider.select((s) => s.incomingSeq),
      (_, __) => ref.invalidateSelf());
  return ref.read(ordersApiProvider).listOrders();
});

String two(int n) => n.toString().padLeft(2, '0');
String formatTime(DateTime? t) => t == null
    ? '—'
    : '${two(t.hour)}:${two(t.minute)}:${two(t.second)} ${two(t.day)}/${two(t.month)}/${t.year}';

Color statusColor(BuildContext context, String status) {
  final scheme = Theme.of(context).colorScheme;
  return switch (status) {
    'DELIVERED' || 'AWAITING_PICKUP' => Colors.green.shade700,
    'REJECTED' || 'CANCELLED' || 'FAILED' => scheme.error,
    'IN_TRANSIT' => Colors.blue.shade700,
    _ => scheme.primary,
  };
}

class StatusChip extends StatelessWidget {
  const StatusChip(this.status, this.label, {super.key});
  final String status, label;
  @override
  Widget build(BuildContext context) {
    final color = statusColor(context, status);
    return Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
            color: color.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(12)),
        child: Text(label,
            style: TextStyle(color: color, fontWeight: FontWeight.w600)));
  }
}

class CustomerHomeScreen extends ConsumerWidget {
  const CustomerHomeScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionProvider).user!;
    final orders = ref.watch(ordersListProvider);
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
          Expanded(
              child: RefreshIndicator(
                  onRefresh: () async {
                    ref.invalidate(ordersListProvider);
                    await ref.read(ordersListProvider.future).catchError((_) => <OrderListItem>[]);
                  },
                  child: orders.when(
                    loading: () => const Center(child: CircularProgressIndicator()),
                    error: (e, _) => ListView(children: [
                      Padding(
                          padding: const EdgeInsets.all(24),
                          child: Column(children: [
                            Text(apiError(e)),
                            const SizedBox(height: 12),
                            FilledButton(
                                onPressed: () =>
                                    ref.invalidate(ordersListProvider),
                                child: const Text('Thử lại')),
                          ]))
                    ]),
                    data: (items) => items.isEmpty
                        ? ListView(children: [
                            Padding(
                                padding: const EdgeInsets.all(32),
                                child: Text(
                                    'Xin chào, ${user.name.isEmpty ? user.email : user.name}. Bạn chưa có đơn hàng nào.',
                                    textAlign: TextAlign.center))
                          ])
                        : ListView.separated(
                            padding: const EdgeInsets.all(12),
                            itemCount: items.length,
                            separatorBuilder: (_, __) => const SizedBox(height: 8),
                            itemBuilder: (context, i) => OrderCard(items[i])),
                  ))),
        ]));
  }
}

class OrderCard extends StatelessWidget {
  const OrderCard(this.order, {super.key});
  final OrderListItem order;
  @override
  Widget build(BuildContext context) => Card(
      child: ListTile(
          key: Key('order-${order.id}'),
          title: Text('Đơn ${order.shortCode}'),
          subtitle: Text(
              'Người nhận: ${order.recipientName}\n${formatTime(order.createdAt)}'),
          isThreeLine: true,
          trailing: StatusChip(order.status, order.statusLabel),
          onTap: () => context.go('/customer/orders/${order.id}')));
}

/// Shows realtime health; hidden while the live connection is fine.
class ConnectionBanner extends ConsumerWidget {
  const ConnectionBanner({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(realtimeStatusProvider).valueOrNull;
    final (text, color) = switch (status) {
      RealtimeStatus.reconnecting || RealtimeStatus.connecting => (
          'Mất kết nối trực tuyến. Đang kết nối lại…',
          Colors.orange.shade800
        ),
      RealtimeStatus.paused => ('Đã tạm dừng cập nhật trực tuyến.', Colors.grey.shade700),
      _ => (null, Colors.transparent),
    };
    if (text == null) return const SizedBox.shrink();
    return Container(
        key: const Key('connection-banner'),
        width: double.infinity,
        color: color,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Text(text, style: const TextStyle(color: Colors.white)));
  }
}

class OrderTrackingScreen extends ConsumerWidget {
  const OrderTrackingScreen({super.key, required this.orderId});
  final String orderId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(trackingProvider(orderId));
    final connected =
        ref.watch(realtimeStatusProvider).valueOrNull == RealtimeStatus.connected;
    return Scaffold(
        appBar: AppBar(
            title: Text('Đơn ${orderId.substring(0, 8).toUpperCase()}'),
            leading: IconButton(
                tooltip: 'Quay lại',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => context.go('/customer')),
            actions: [
              IconButton(
                  tooltip: 'Tải lại',
                  onPressed: () =>
                      ref.read(trackingProvider(orderId).notifier).reload(),
                  icon: const Icon(Icons.refresh))
            ]),
        body: Column(children: [
          const ConnectionBanner(),
          if (s.error != null && s.loaded)
            Container(
                width: double.infinity,
                color: Theme.of(context).colorScheme.errorContainer,
                padding: const EdgeInsets.all(8),
                child: Text('Chưa cập nhật được: ${s.error}')),
          Expanded(child: _body(context, ref, s, connected)),
        ]));
  }

  Widget _body(
      BuildContext context, WidgetRef ref, TrackingState s, bool connected) {
    if (!s.loaded) {
      if (s.error != null) {
        return Center(
            child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Text(s.error!),
                  const SizedBox(height: 12),
                  FilledButton(
                      onPressed: () =>
                          ref.read(trackingProvider(orderId).notifier).reload(),
                      child: const Text('Thử lại')),
                ])));
      }
      return const Center(child: CircularProgressIndicator());
    }
    return RefreshIndicator(
        onRefresh: () => ref.read(trackingProvider(orderId).notifier).reload(),
        child: ListView(padding: const EdgeInsets.all(16), children: [
          Row(children: [
            Expanded(
                child: Text(s.statusLabel,
                    key: const Key('current-status'),
                    style: Theme.of(context).textTheme.headlineSmall)),
            if (connected && s.live && !s.denied)
              const Chip(
                  key: Key('live-chip'),
                  avatar: Icon(Icons.circle, size: 10, color: Colors.green),
                  label: Text('Trực tuyến')),
          ]),
          if (s.pickup != null || s.dropoff != null) ...[
            const SizedBox(height: 8),
            Text('${s.pickup?.name ?? '—'}  →  ${s.dropoff?.name ?? '—'}'),
          ],
          if (s.denied)
            const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text('Không có quyền nhận cập nhật trực tuyến cho đơn này.')),
          const SizedBox(height: 16),
          Text('Tiến trình', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          for (var i = s.timeline.length - 1; i >= 0; i--)
            TimelineTile(
                entry: s.timeline[i],
                isLatest: i == s.timeline.length - 1,
                isFirst: i == 0),
          if (realtimeDiag) DiagText(orderId: orderId, state: s),
        ]));
  }
}

const realtimeDiag = bool.fromEnvironment('REALTIME_DIAG');

class DiagText extends ConsumerWidget {
  const DiagText({super.key, required this.orderId, required this.state});
  final String orderId;
  final TrackingState state;
  @override
  Widget build(BuildContext context, WidgetRef ref) => Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Text(
          'v=${state.version} dup=${state.duplicates} stale=${state.stale} gap=${state.gaps} catchup=${state.catchUps}',
          key: const Key('tracking-diag'),
          style: const TextStyle(fontSize: 11, color: Colors.grey)));
}

class TimelineTile extends StatelessWidget {
  const TimelineTile(
      {super.key,
      required this.entry,
      required this.isLatest,
      required this.isFirst});
  final TimelineEntry entry;
  final bool isLatest, isFirst;
  @override
  Widget build(BuildContext context) {
    final color = isLatest
        ? statusColor(context, entry.toStatus)
        : Theme.of(context).disabledColor;
    return IntrinsicHeight(
        child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      SizedBox(
          width: 28,
          child: Column(children: [
            Icon(isLatest ? Icons.radio_button_checked : Icons.circle,
                size: isLatest ? 20 : 12, color: color),
            if (!isFirst) Expanded(child: Container(width: 2, color: color)),
          ])),
      Expanded(
          child: Padding(
              padding: const EdgeInsets.only(bottom: 16, left: 8),
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(entry.label,
                        key: Key('timeline-v${entry.version}'),
                        style: TextStyle(
                            fontWeight:
                                isLatest ? FontWeight.bold : FontWeight.normal)),
                    Text(formatTime(entry.occurredAt),
                        style: Theme.of(context).textTheme.bodySmall),
                  ]))),
    ]));
  }
}
