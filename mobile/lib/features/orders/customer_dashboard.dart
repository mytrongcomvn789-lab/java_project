import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_failure.dart';
import '../../core/realtime/realtime_provider.dart';
import '../../core/theme/app_theme.dart';
import '../../core/ui/async_state_view.dart';
import '../../core/ui/demo_badge.dart';
import '../auth/session.dart';
import '../ai/ai_api.dart';
import '../ai/ai_models.dart' show EtaResult;
import '../flight/flight_api.dart';
import '../notifications/notifications_controller.dart';
import 'customer_nav.dart';
import 'customer_order_models.dart';
import 'customer_orders_api.dart';
import 'order_detail_controller.dart' show stationsProvider;
import 'order_screens.dart' show ConnectionBanner, StatusChip, statusIcon;

/// Các đơn gần đây của khách (trang đầu, mới nhất trước), tự tải lại khi có thông báo realtime.
/// Tách khỏi danh sách có bộ lọc để chọn bộ lọc ở màn "Đơn hàng" không làm đổi trang chủ.
final recentOrdersProvider = FutureProvider.autoDispose<List<OrderSummary>>((ref) async {
  ref.watch(realtimeUserProvider);
  ref.listen(notificationsProvider.select((s) => s.incomingSeq), (_, __) => ref.invalidateSelf());
  final page = await ref.read(customerOrdersApiProvider).listOrders(page: 1, pageSize: 20);
  return page.items;
});

/// ETA thật của một đơn (bản công thức 7.2, `GET /v1/ai/orders/{id}/eta`). null khi chưa tính được
/// hoặc lỗi: thẻ hiện "—" chứ không bịa số.
final dashboardEtaProvider =
    FutureProvider.autoDispose.family<EtaResult?, String>((ref, orderId) async {
  final result = (await ref.read(aiApiProvider).eta(orderId)).result;
  return result != null && result.computed ? result : null;
});

String _shortTime(DateTime? t) {
  if (t == null) return '—';
  String two(int n) => n.toString().padLeft(2, '0');
  return '${two(t.hour)}:${two(t.minute)} ${two(t.day)}/${two(t.month)}';
}

String _stationName(List<Station>? stations, String id) {
  for (final s in stations ?? const <Station>[]) {
    if (s.id == id) return s.name;
  }
  return 'Trạm ${id.length >= 8 ? id.substring(0, 8) : id}';
}

/// Trang chủ của khách (màn 2): lời chào, đơn đang giao, lối tắt và các đơn gần đây.
/// Đơn, thông báo và trạm là dữ liệu thật; ETA, tốc độ, pin và tiến độ bay là dữ liệu mẫu có nhãn
/// "Demo" cho tới khi backend có `/eta` và `/flight`.
class CustomerDashboardScreen extends ConsumerWidget {
  const CustomerDashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionProvider).user!;
    final orders = ref.watch(recentOrdersProvider);
    final name = user.name.isEmpty ? user.email : user.name;
    return Scaffold(
        body: RefreshIndicator(
            onRefresh: () async {
              ref.invalidate(recentOrdersProvider);
              try {
                await ref.read(recentOrdersProvider.future);
              } catch (_) {}
            },
            child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: EdgeInsets.fromLTRB(16, MediaQuery.paddingOf(context).top + 12, 16, 24),
                children: [
                  _Header(name: name),
                  const SizedBox(height: 4),
                  const ConnectionBanner(),
                  const SizedBox(height: 12),
                  _ActiveSection(orders: orders),
                  const SizedBox(height: 20),
                  const _Shortcuts(),
                  const SizedBox(height: 24),
                  _RecentSection(orders: orders),
                ])),
        bottomNavigationBar: const CustomerBottomNav(selectedIndex: 0));
  }
}

class _Header extends ConsumerWidget {
  const _Header({required this.name});
  final String name;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final unread = ref.watch(notificationsProvider.select((s) => s.unread));
    final initial = name.trim().isEmpty ? '?' : name.trim().characters.first.toUpperCase();
    return Row(children: [
      CircleAvatar(
          radius: 22,
          backgroundColor: AppColors.primary,
          child: Text(initial,
              style: const TextStyle(
                  color: Colors.white, fontSize: 18, fontWeight: FontWeight.w800))),
      const SizedBox(width: 12),
      Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Xin chào,',
            style: TextStyle(color: AppColors.textMuted, fontSize: 13)),
        Text(name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
      ])),
      IconButton(
          key: const Key('open-notifications'),
          tooltip: unread > 0 ? 'Thông báo, $unread chưa đọc' : 'Thông báo',
          onPressed: () => context.go('/customer/notifications'),
          constraints: const BoxConstraints.tightFor(width: 48, height: 48),
          icon: Badge(
              isLabelVisible: unread > 0,
              backgroundColor: const Color(0xffd32f2f),
              label: Text('$unread', key: const Key('unread-count')),
              child: const Icon(Icons.notifications_outlined, size: 28))),
    ]);
  }
}

class _ActiveSection extends ConsumerWidget {
  const _ActiveSection({required this.orders});
  final AsyncValue<List<OrderSummary>> orders;

  @override
  Widget build(BuildContext context, WidgetRef ref) => orders.when(
      loading: () => const _SkeletonCard(),
      error: (e, _) => _CardShell(
          child: AsyncStateView(
              error: toApiFailure(e),
              onRetry: () => ref.invalidate(recentOrdersProvider),
              child: const SizedBox.shrink())),
      data: (items) {
        final active = items.where((o) => o.status == 'IN_TRANSIT').firstOrNull;
        return active == null ? const _NoActiveCard() : _ActiveOrderCard(order: active);
      });
}

class _SkeletonCard extends StatelessWidget {
  const _SkeletonCard();
  @override
  Widget build(BuildContext context) => const _CardShell(
      child: Padding(
          padding: EdgeInsets.all(20),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SkeletonBox(width: 120, height: 20),
            SizedBox(height: 16),
            SkeletonBox(width: 180, height: 40),
            SizedBox(height: 16),
            SkeletonBox(height: 10),
          ])));
}

class _CardShell extends StatelessWidget {
  const _CardShell({required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Container(
      constraints: const BoxConstraints(minHeight: 140),
      decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.card),
          boxShadow: cardShadow),
      child: child);
}

class _NoActiveCard extends StatelessWidget {
  const _NoActiveCard();
  @override
  Widget build(BuildContext context) => Container(
      key: const Key('no-active-order'),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(AppRadii.card),
          boxShadow: cardShadow),
      child: Row(children: [
        Container(
            width: 52,
            height: 52,
            decoration: BoxDecoration(
                color: AppColors.primary.withValues(alpha: 0.10),
                borderRadius: BorderRadius.circular(16)),
            child: const Icon(Icons.flight_takeoff, color: AppColors.primary)),
        const SizedBox(width: 14),
        const Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Chưa có đơn đang giao',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
          SizedBox(height: 2),
          Text('Đơn của bạn sẽ hiện ở đây khi drone cất cánh.',
              style: TextStyle(color: AppColors.textMuted, height: 1.3)),
        ])),
      ]));
}

/// Thẻ nổi bật của đơn đang giao (nền #073B45).
class _ActiveOrderCard extends ConsumerWidget {
  const _ActiveOrderCard({required this.order});
  final OrderSummary order;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final eta = ref.watch(dashboardEtaProvider(order.id)).valueOrNull;
    final flight = ref.watch(flightProvider(order.id)).valueOrNull;
    final stations = ref.watch(stationsProvider).valueOrNull;
    // Chỉ số đo của drone (tốc độ, pin, tiến độ) là Demo; ETA là số thật từ backend.
    final isDemo = flight?.isDemo ?? false;
    final minutes = eta?.etaMinutes;
    final t = flight?.data.telemetry;
    return Container(
        key: const Key('active-order-card'),
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
            color: AppColors.dark,
            borderRadius: BorderRadius.circular(26),
            boxShadow: const [
              BoxShadow(color: Color(0x33073b45), blurRadius: 24, offset: Offset(0, 10))
            ]),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            StatusChip(order.status, order.statusLabel),
            const SizedBox(width: 10),
            Expanded(
                child: Text('Đơn ${order.shortCode}',
                    style: const TextStyle(
                        color: Colors.white70, fontWeight: FontWeight.w600))),
            if (isDemo) const DemoBadge(onDark: true),
          ]),
          const SizedBox(height: 14),
          const Text('Dự kiến đến sau',
              style: TextStyle(color: Colors.white70, fontSize: 13)),
          Text(minutes == null ? '—' : '$minutes phút',
              key: const Key('active-eta'),
              style: const TextStyle(
                  color: Colors.white, fontSize: 44, fontWeight: FontWeight.w800, height: 1.1)),
          const SizedBox(height: 14),
          Row(children: [
            _Stat(Icons.speed, 'Tốc độ', t == null ? '—' : '${t.speedKmh.round()} km/h'),
            const SizedBox(width: 12),
            _Stat(Icons.battery_5_bar_outlined, 'Pin drone',
                t == null ? '—' : '${t.batteryPercent}%'),
          ]),
          const SizedBox(height: 18),
          _RouteProgress(
              from: _stationName(stations, order.pickupStationId),
              to: _stationName(stations, order.dropoffStationId),
              progress: flight?.data.progress),
          const SizedBox(height: 18),
          OutlinedButton.icon(
              key: const Key('open-live'),
              onPressed: () => context.push('/customer/orders/${order.id}'),
              style: OutlinedButton.styleFrom(
                  foregroundColor: Colors.white,
                  side: const BorderSide(color: Colors.white38)),
              icon: const Icon(Icons.map_outlined),
              label: const Text('Xem bản đồ trực tiếp')),
        ]));
  }
}

class _Stat extends StatelessWidget {
  const _Stat(this.icon, this.label, this.value);
  final IconData icon;
  final String label, value;

  @override
  Widget build(BuildContext context) => Expanded(
      child: Semantics(
          label: '$label: $value',
          excludeSemantics: true,
          child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.10),
                  borderRadius: BorderRadius.circular(16)),
              child: Row(children: [
                Icon(icon, color: AppColors.tealBright, size: 22),
                const SizedBox(width: 8),
                Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(label, style: const TextStyle(color: Colors.white70, fontSize: 12)),
                  Text(value,
                      style: const TextStyle(
                          color: Colors.white, fontSize: 16, fontWeight: FontWeight.w800)),
                ])),
              ]))));
}

/// Thanh tiến độ giữa hai trạm: drone ở đúng vị trí [progress] (0 đến 1).
class _RouteProgress extends StatelessWidget {
  const _RouteProgress({required this.from, required this.to, required this.progress});
  final String from, to;
  final double? progress;

  @override
  Widget build(BuildContext context) {
    final p = progress ?? 0;
    return Semantics(
        label: 'Tiến độ chuyến bay từ $from đến $to: ${(p * 100).round()}%',
        excludeSemantics: true,
        child: Column(children: [
          SizedBox(
              height: 28,
              child: LayoutBuilder(builder: (context, c) {
                final w = c.maxWidth;
                return Stack(clipBehavior: Clip.none, alignment: Alignment.centerLeft, children: [
                  Container(
                      height: 6,
                      decoration: BoxDecoration(
                          color: Colors.white24, borderRadius: BorderRadius.circular(3))),
                  Container(
                      width: w * p,
                      height: 6,
                      decoration: BoxDecoration(
                          color: AppColors.amber, borderRadius: BorderRadius.circular(3))),
                  Positioned(
                      left: (w * p - 14).clamp(0.0, w - 28),
                      child: Container(
                          width: 28,
                          height: 28,
                          decoration: const BoxDecoration(
                              color: AppColors.amber, shape: BoxShape.circle),
                          child: const Icon(Icons.flight, size: 16, color: AppColors.dark))),
                ]);
              })),
          const SizedBox(height: 6),
          Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            Flexible(
                child: Text(from,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white70, fontSize: 12))),
            const SizedBox(width: 8),
            Flexible(
                child: Text(to,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.end,
                    style: const TextStyle(color: Colors.white70, fontSize: 12))),
          ]),
        ]));
  }
}

class _Shortcuts extends StatelessWidget {
  const _Shortcuts();

  @override
  Widget build(BuildContext context) => Row(children: [
        _tile(context, 'shortcut-new-order', Icons.add_box_outlined, 'Tạo đơn',
            () => context.push('/customer/new-order')),
        _tile(context, 'shortcut-addresses', Icons.location_on_outlined, 'Địa chỉ',
            () => context.push('/customer/addresses')),
        _tile(context, 'shortcut-history', Icons.history, 'Lịch sử',
            () => context.go('/customer/orders')),
        _tile(context, 'shortcut-assistant', Icons.smart_toy_outlined, 'Hỏi AI',
            () => context.go('/customer/assistant')),
      ]);

  Widget _tile(BuildContext context, String key, IconData icon, String label,
          VoidCallback onTap) =>
      Expanded(
          child: Semantics(
              button: true,
              label: label,
              excludeSemantics: true,
              child: InkWell(
                  key: Key(key),
                  borderRadius: BorderRadius.circular(16),
                  onTap: onTap,
                  child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 6),
                      child: Column(mainAxisSize: MainAxisSize.min, children: [
                        Container(
                            width: 56,
                            height: 56,
                            decoration: BoxDecoration(
                                color: AppColors.surface,
                                borderRadius: BorderRadius.circular(18),
                                boxShadow: cardShadow),
                            child: Icon(icon, color: AppColors.primary, size: 26)),
                        const SizedBox(height: 8),
                        Text(label,
                            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
                      ])))));
}

class _RecentSection extends ConsumerWidget {
  const _RecentSection({required this.orders});
  final AsyncValue<List<OrderSummary>> orders;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final items = orders.valueOrNull ?? const <OrderSummary>[];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Expanded(
            child: Text('Đơn gần đây',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800))),
        TextButton(
            key: const Key('see-all'),
            onPressed: () => context.go('/customer/orders'),
            child: const Text('Xem tất cả')),
      ]),
      const SizedBox(height: 4),
      AsyncStateView(
          loading: orders.isLoading && !orders.hasValue,
          error: orders.hasError && !orders.hasValue ? toApiFailure(orders.error!) : null,
          onRetry: () => ref.invalidate(recentOrdersProvider),
          isEmpty: orders.hasValue && items.isEmpty,
          emptyIcon: Icons.inventory_2_outlined,
          emptyTitle: 'Bạn chưa có đơn nào',
          emptyHint: 'Bấm "Tạo đơn" để gửi đơn đầu tiên.',
          skeleton: const _RecentSkeleton(),
          child: Column(children: [
            for (final o in items.take(3))
              Padding(padding: const EdgeInsets.only(bottom: 10), child: _RecentTile(order: o)),
          ])),
    ]);
  }
}

class _RecentSkeleton extends StatelessWidget {
  const _RecentSkeleton();
  @override
  Widget build(BuildContext context) => const ExcludeSemantics(
      child: Column(children: [
        SkeletonBox(height: 72, radius: 18),
        SizedBox(height: 10),
        SkeletonBox(height: 72, radius: 18),
      ]));
}

class _RecentTile extends StatelessWidget {
  const _RecentTile({required this.order});
  final OrderSummary order;

  @override
  Widget build(BuildContext context) {
    final palette = StatusPalette.of(order.status);
    final weight = order.packageWeightGrams;
    final title = [
      if ((order.packageDescription ?? '').isNotEmpty) order.packageDescription!,
      if (weight != null) '$weight g',
    ].join(' · ');
    return DecoratedBox(
        decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18), boxShadow: cardShadow),
        child: Material(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(18),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
                key: Key('recent-${order.id}'),
                onTap: () => context.go('/customer/orders/${order.id}/detail'),
                child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Row(children: [
                      Container(
                          width: 46,
                          height: 46,
                          decoration: BoxDecoration(
                              color: palette.background,
                              borderRadius: BorderRadius.circular(14)),
                          child: Icon(statusIcon(order.status),
                              color: palette.foreground, size: 24)),
                      const SizedBox(width: 12),
                      // Chip nằm dưới cùng cột chữ (không chiếm bên phải) để tên gói và người
                      // nhận không bị cắt cụt.
                      Expanded(
                          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(title.isEmpty ? 'Đơn ${order.shortCode}' : title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontWeight: FontWeight.w700)),
                        const SizedBox(height: 2),
                        Text('${order.recipientName} · ${_shortTime(order.createdAt)}',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
                        const SizedBox(height: 6),
                        StatusChip(order.status, order.statusLabel),
                      ])),
                    ])))));
  }
}
