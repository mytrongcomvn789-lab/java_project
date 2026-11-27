import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_failure.dart';
import '../../core/theme/app_theme.dart';
import '../../core/ui/async_state_view.dart';
import '../orders/order_models.dart';
import '../orders/order_screens.dart';
import 'notifications_controller.dart';

/// Nhóm lọc thông báo. Backend chưa có trường `category` (xem docs/05-api/mobile-ui-required-apis.md),
/// nên app phân loại theo TRẠNG THÁI ĐƠN đi kèm thông báo; khi backend có `category` chỉ cần đổi [matches].
enum NotificationFilter {
  all('Tất cả'),
  unread('Chưa đọc'),
  delivery('Giao hàng'),
  issue('Sự cố');

  const NotificationFilter(this.label);
  final String label;

  static const _deliveryStatuses = {'IN_TRANSIT', 'AWAITING_PICKUP', 'DELIVERED'};
  static const _issueStatuses = {'FAILED', 'RETURNING', 'RETURNED', 'REJECTED', 'CANCELLED'};

  bool matches(AppNotification n) => switch (this) {
        NotificationFilter.all => true,
        NotificationFilter.unread => n.unread,
        NotificationFilter.delivery => _deliveryStatuses.contains(n.status),
        NotificationFilter.issue => _issueStatuses.contains(n.status),
      };
}

final notificationFilterProvider =
    StateProvider.autoDispose<NotificationFilter>((ref) => NotificationFilter.all);

/// Trung tâm thông báo (màn 6). Thông báo, đã đọc và thời gian thực là dữ liệu thật. Các hành động
/// "Giao lại" và "Hoàn hàng" của khách chưa có API nên chỉ hiện dạng nút tắt "Sắp có".
class NotificationsScreen extends ConsumerStatefulWidget {
  const NotificationsScreen({super.key});
  @override
  ConsumerState<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends ConsumerState<NotificationsScreen> {
  @override
  void initState() {
    super.initState();
    // Mở màn là tải lại: không phụ thuộc việc kết nối realtime đã sẵn sàng hay chưa.
    Future.microtask(() {
      if (mounted) ref.read(notificationsProvider.notifier).reload();
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(notificationsProvider);
    final filter = ref.watch(notificationFilterProvider);
    final controller = ref.read(notificationsProvider.notifier);
    final shown = [for (final n in state.items) if (filter.matches(n)) n];
    return Scaffold(
        appBar: AppBar(
            title: const Text('Thông báo'),
            leading: IconButton(
                tooltip: 'Quay lại',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => context.go('/customer')),
            actions: [
              TextButton(
                  key: const Key('mark-all-read'),
                  onPressed: state.unread == 0 ? null : controller.markAllRead,
                  child: const Text('Đọc tất cả')),
            ]),
        body: Column(children: [
          const ConnectionBanner(),
          _FilterBar(filter: filter, unread: state.unread),
          Expanded(
              child: RefreshIndicator(
                  onRefresh: controller.reload,
                  child: AsyncStateView(
                      loading: !state.loaded && !state.error,
                      error: state.error && state.items.isEmpty
                          ? const ApiFailure(
                              FailureKind.unknown, 'Không tải được thông báo.')
                          : null,
                      onRetry: controller.reload,
                      isEmpty: shown.isEmpty,
                      emptyIcon: Icons.notifications_none_outlined,
                      emptyTitle: filter == NotificationFilter.all
                          ? 'Chưa có thông báo nào'
                          : 'Không có thông báo trong mục này',
                      emptyHint: 'Thông báo về đơn hàng của bạn sẽ hiện ở đây.',
                      child: ListView.separated(
                          physics: const AlwaysScrollableScrollPhysics(),
                          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                          itemCount: shown.length,
                          separatorBuilder: (_, __) => const SizedBox(height: 10),
                          itemBuilder: (context, i) => _NotificationCard(
                              n: shown[i],
                              onTap: () {
                                controller.markRead(shown[i]);
                                context.go('/customer/orders/${shown[i].orderId}');
                              }))))),
        ]));
  }
}

class _FilterBar extends ConsumerWidget {
  const _FilterBar({required this.filter, required this.unread});
  final NotificationFilter filter;
  final int unread;

  /// Wrap thay vì cuộn ngang: cả 4 nhóm luôn thấy được trên màn 390 dp, chật thì xuống dòng.
  @override
  Widget build(BuildContext context, WidgetRef ref) => Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      child: Wrap(spacing: 8, runSpacing: 4, children: [
        for (final f in NotificationFilter.values)
          ChoiceChip(
              key: Key('filter-${f.name}'),
              label: Text(f == NotificationFilter.unread && unread > 0
                  ? '${f.label} ($unread)'
                  : f.label),
              selected: f == filter,
              showCheckmark: false,
              selectedColor: AppColors.primary,
              labelStyle: TextStyle(
                  color: f == filter ? Colors.white : AppColors.text,
                  fontWeight: FontWeight.w600),
              onSelected: (_) => ref.read(notificationFilterProvider.notifier).state = f),
      ]));
}

class _NotificationCard extends StatelessWidget {
  const _NotificationCard({required this.n, required this.onTap});
  final AppNotification n;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final status = n.status ?? '';
    final palette = StatusPalette.of(status);
    final showReturnActions = status == 'FAILED';
    return DecoratedBox(
        decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(AppRadii.card), boxShadow: cardShadow),
        child: Material(
            key: Key('notification-${n.eventId}'),
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(AppRadii.card),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
                onTap: onTap,
                child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Container(
                            width: 40,
                            height: 40,
                            decoration: BoxDecoration(
                                color: palette.background, shape: BoxShape.circle),
                            child: Icon(statusIcon(status),
                                size: 20, color: palette.foreground)),
                        const SizedBox(width: 12),
                        Expanded(
                            child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                              Row(children: [
                                Expanded(
                                    child: Text(n.title,
                                        style: TextStyle(
                                            fontSize: 15,
                                            fontWeight: n.unread
                                                ? FontWeight.w800
                                                : FontWeight.w600))),
                                if (n.unread)
                                  Container(
                                      key: const Key('unread-dot'),
                                      width: 10,
                                      height: 10,
                                      margin: const EdgeInsets.only(left: 8),
                                      decoration: const BoxDecoration(
                                          color: AppColors.tealBright,
                                          shape: BoxShape.circle)),
                              ]),
                              const SizedBox(height: 2),
                              Text(n.body,
                                  style: const TextStyle(
                                      color: AppColors.textMuted, height: 1.3)),
                              const SizedBox(height: 6),
                              Text(formatTime(n.createdAt),
                                  style: const TextStyle(
                                      color: AppColors.textMuted, fontSize: 12)),
                            ])),
                      ]),
                      if (showReturnActions) ...[
                        const SizedBox(height: 10),
                        const Row(children: [
                          Expanded(
                              child: OutlinedButton(
                                  key: Key('action-redeliver'),
                                  // Backend chưa có API khách yêu cầu giao lại/hoàn hàng.
                                  onPressed: null,
                                  child: Text('Giao lại (sắp có)'))),
                          SizedBox(width: 10),
                          Expanded(
                              child: OutlinedButton(
                                  key: Key('action-return'),
                                  onPressed: null,
                                  child: Text('Hoàn hàng (sắp có)'))),
                        ]),
                      ],
                    ])))));
  }
}
