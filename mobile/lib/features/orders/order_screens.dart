import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/realtime/realtime_client.dart';
import '../../core/realtime/realtime_provider.dart';
import 'order_models.dart';
import 'tracking_controller.dart';
import 'tracking_state.dart';

String two(int n) => n.toString().padLeft(2, '0');
String formatTime(DateTime? t) => t == null
    ? '—'
    : '${two(t.hour)}:${two(t.minute)}:${two(t.second)} ${two(t.day)}/${two(t.month)}/${t.year}';

/// Màu theo ý nghĩa: xanh lá = xong/đến nơi, cam = đang chờ hoặc cần chú ý, xanh dương = đang di
/// chuyển, đỏ = lỗi hoặc bị từ chối, xám = đã đóng. Người vận hành nhìn màu là biết đơn nào cần xử lý.
Color statusColor(BuildContext context, String status) {
  final scheme = Theme.of(context).colorScheme;
  return switch (status) {
    'DELIVERED' || 'AWAITING_PICKUP' => Colors.green.shade700,
    'PENDING' || 'RETURNING' => Colors.orange.shade800,
    'REJECTED' || 'FAILED' => scheme.error,
    'IN_TRANSIT' => Colors.blue.shade700,
    'SCHEDULED' || 'PREPARING' || 'APPROVED' => Colors.indigo.shade600,
    'CANCELLED' || 'RETURNED' => Colors.blueGrey.shade600,
    _ => scheme.primary,
  };
}

IconData statusIcon(String status) => switch (status) {
      'PENDING' => Icons.hourglass_top_rounded,
      'APPROVED' => Icons.thumb_up_alt_outlined,
      'REJECTED' => Icons.block,
      'SCHEDULED' => Icons.event_available_outlined,
      'PREPARING' => Icons.inventory_2_outlined,
      'IN_TRANSIT' => Icons.flight_takeoff,
      'AWAITING_PICKUP' => Icons.storefront_outlined,
      'DELIVERED' => Icons.check_circle_outline,
      'CANCELLED' => Icons.cancel_outlined,
      'FAILED' => Icons.error_outline,
      'RETURNING' => Icons.keyboard_return,
      'RETURNED' => Icons.assignment_returned_outlined,
      _ => Icons.circle_outlined,
    };

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
            borderRadius: BorderRadius.circular(20)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(statusIcon(status), size: 14, color: color),
          const SizedBox(width: 4),
          Flexible(
              child: Text(label,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      color: color, fontSize: 12, fontWeight: FontWeight.w600))),
        ]));
  }
}

/// Thẻ đơn dùng chung cho danh sách của khách và của operator: mã đơn đậm cùng chip trạng thái
/// ở hàng đầu, các dòng thông tin có icon ở dưới, thời gian nhỏ ở cuối.
class OrderListCard extends StatelessWidget {
  const OrderListCard(
      {super.key,
      required this.title,
      required this.status,
      required this.statusLabel,
      required this.lines,
      this.time,
      this.onTap});
  final String title, status, statusLabel;

  /// Mỗi phần tử là (icon, nội dung). Dòng trống bị bỏ.
  final List<(IconData, String)> lines;
  final String? time;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
            onTap: onTap,
            child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Expanded(
                        child: Text(title,
                            style: theme.textTheme.titleMedium
                                ?.copyWith(fontWeight: FontWeight.w700))),
                    const SizedBox(width: 8),
                    StatusChip(status, statusLabel),
                  ]),
                  const SizedBox(height: 10),
                  for (final (icon, text) in lines)
                    if (text.trim().isNotEmpty)
                      Padding(
                          padding: const EdgeInsets.only(bottom: 4),
                          child: Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Icon(icon,
                                    size: 16,
                                    color: theme.colorScheme.onSurfaceVariant),
                                const SizedBox(width: 8),
                                Expanded(child: Text(text)),
                              ])),
                  if (time != null)
                    Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text(time!,
                            style: theme.textTheme.bodySmall?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant))),
                ]))));
  }
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
