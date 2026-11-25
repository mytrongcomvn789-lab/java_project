import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/offline/pending_action.dart';
import '../../core/theme/app_theme.dart';
import 'operator_sync.dart';

/// Thông báo trên màn thao tác khi yêu cầu đã được lưu để gửi sau (mất mạng).
class QueuedNotice extends StatelessWidget {
  const QueuedNotice({super.key, required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    // Cùng màu vàng với banner "chờ đồng bộ" ở màn chính để người dùng nhận ra là một việc.
    final warn = StatusPalette.pending;
    return Container(
        padding: const EdgeInsets.all(14),
        margin: const EdgeInsets.only(bottom: 12),
        decoration: BoxDecoration(
            color: warn.background, borderRadius: BorderRadius.circular(14)),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(Icons.cloud_off_outlined, color: warn.foreground),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: TextStyle(color: warn.foreground))),
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
    final controller = ref.read(operatorSyncProvider.notifier);
    final stuck = [
      for (final a in sync.actions)
        if (a.status == PendingStatus.needsAttention) a
    ];
    final warn = StatusPalette.pending;
    final bad = StatusPalette.failed;
    return Column(children: [
      // Banner vàng khi còn thao tác chưa gửi được (mất mạng): tự gửi lại, hoặc bấm Thử lại.
      if (sync.waiting > 0)
        Container(
            key: const Key('sync-waiting'),
            width: double.infinity,
            margin: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            padding: const EdgeInsets.fromLTRB(14, 6, 6, 6),
            decoration: BoxDecoration(
                color: warn.background, borderRadius: BorderRadius.circular(14)),
            child: Row(children: [
              Icon(Icons.cloud_off_outlined, size: 20, color: warn.foreground),
              const SizedBox(width: 10),
              Expanded(
                  child: Text('${sync.waiting} thao tác chờ đồng bộ khi có mạng',
                      style: TextStyle(
                          color: warn.foreground, fontWeight: FontWeight.w600))),
              if (sync.syncing)
                const Padding(
                    padding: EdgeInsets.all(12),
                    child: SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2)))
              else
                TextButton(
                    key: const Key('sync-now'),
                    onPressed: controller.syncNow,
                    style: TextButton.styleFrom(foregroundColor: warn.foreground),
                    child: const Text('Thử lại')),
            ])),
      for (final a in stuck)
        Container(
            key: Key('sync-stuck-${a.id}'),
            width: double.infinity,
            margin: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            padding: const EdgeInsets.fromLTRB(14, 12, 6, 6),
            decoration: BoxDecoration(
                color: bad.background, borderRadius: BorderRadius.circular(14)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${a.kindLabel} đơn ${_short(a.orderId)} chưa được thực hiện',
                  style: TextStyle(
                      color: bad.foreground, fontWeight: FontWeight.w700)),
              const SizedBox(height: 2),
              Text(a.message ?? 'Server từ chối yêu cầu này.',
                  style: TextStyle(color: bad.foreground)),
              Row(mainAxisAlignment: MainAxisAlignment.end, children: [
                TextButton(
                    key: Key('sync-open-${a.id}'),
                    onPressed: () => context.push(_routeOf(a)),
                    style: TextButton.styleFrom(foregroundColor: bad.foreground),
                    child: const Text('Mở đơn')),
                TextButton(
                    key: Key('sync-discard-${a.id}'),
                    onPressed: () => controller.discard(a.id),
                    style: TextButton.styleFrom(foregroundColor: bad.foreground),
                    child: const Text('Bỏ yêu cầu')),
              ]),
            ])),
    ]);
  }
}
