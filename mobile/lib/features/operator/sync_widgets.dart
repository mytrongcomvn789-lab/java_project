import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/offline/pending_action.dart';
import 'operator_sync.dart';

/// Thông báo trên màn thao tác khi yêu cầu đã được lưu để gửi sau (mất mạng).
class QueuedNotice extends StatelessWidget {
  const QueuedNotice({super.key, required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
        padding: const EdgeInsets.all(12),
        margin: const EdgeInsets.only(bottom: 12),
        decoration: BoxDecoration(
            color: scheme.tertiaryContainer,
            borderRadius: BorderRadius.circular(8)),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Icon(Icons.cloud_off_outlined),
          const SizedBox(width: 8),
          Expanded(child: Text(text)),
        ]));
  }
}

String _routeOf(PendingAction a) => switch (a.kind) {
      PendingKind.receive => '/operator/receipts/${a.orderId}',
      PendingKind.confirm => '/operator/pickups/${a.orderId}',
      PendingKind.completeReturn => '/operator/returns/${a.orderId}',
    };

String _short(String id) =>
    (id.length >= 8 ? id.substring(0, 8) : id).toUpperCase();

/// Dải trạng thái đồng bộ ở màn chính: số thao tác đang chờ gửi, nút "Đồng bộ ngay", và các
/// thao tác server đã từ chối (cần người dùng xem lại). Không hiện gì khi hàng đợi trống.
class SyncBanner extends ConsumerStatefulWidget {
  const SyncBanner({super.key});

  @override
  ConsumerState<SyncBanner> createState() => _SyncBannerState();
}

class _SyncBannerState extends ConsumerState<SyncBanner>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// Mở lại app (thường là khi đã có mạng): thử gửi ngay, không đợi chu kỳ.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      ref.read(operatorSyncProvider.notifier).syncNow();
    }
  }

  @override
  Widget build(BuildContext context) {
    final sync = ref.watch(operatorSyncProvider);
    if (sync.isEmpty) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    final controller = ref.read(operatorSyncProvider.notifier);
    final stuck = [
      for (final a in sync.actions)
        if (a.status == PendingStatus.needsAttention) a
    ];
    return Column(children: [
      if (sync.waiting > 0)
        Container(
            key: const Key('sync-waiting'),
            width: double.infinity,
            color: scheme.tertiaryContainer,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            child: Row(children: [
              const Icon(Icons.cloud_off_outlined, size: 18),
              const SizedBox(width: 8),
              Expanded(
                  child: Text(
                      '${sync.waiting} thao tác đang chờ gửi (chưa có mạng). App sẽ tự gửi lại.')),
              if (sync.syncing)
                const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2))
              else
                TextButton(
                    key: const Key('sync-now'),
                    onPressed: controller.syncNow,
                    child: const Text('Đồng bộ ngay')),
            ])),
      for (final a in stuck)
        Container(
            key: Key('sync-stuck-${a.id}'),
            width: double.infinity,
            color: scheme.errorContainer,
            padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${a.kindLabel} đơn ${_short(a.orderId)} chưa được thực hiện',
                  style: Theme.of(context).textTheme.titleSmall),
              Text(a.message ?? 'Server từ chối yêu cầu này.'),
              Row(mainAxisAlignment: MainAxisAlignment.end, children: [
                TextButton(
                    key: Key('sync-open-${a.id}'),
                    onPressed: () => context.push(_routeOf(a)),
                    child: const Text('Mở đơn')),
                TextButton(
                    key: Key('sync-discard-${a.id}'),
                    onPressed: () => controller.discard(a.id),
                    child: const Text('Bỏ yêu cầu')),
              ]),
            ])),
    ]);
  }
}
