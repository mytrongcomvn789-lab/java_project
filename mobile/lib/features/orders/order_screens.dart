import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/theme/app_theme.dart';
import '../../core/realtime/realtime_client.dart';
import '../../core/realtime/realtime_provider.dart';

String two(int n) => n.toString().padLeft(2, '0');
String formatTime(DateTime? t) => t == null
    ? '—'
    : '${two(t.hour)}:${two(t.minute)}:${two(t.second)} ${two(t.day)}/${two(t.month)}/${t.year}';

/// Màu chữ/icon của một trạng thái, lấy từ bảng màu chip của đặc tả ([StatusPalette]).
Color statusColor(BuildContext context, String status) =>
    StatusPalette.of(status).foreground;

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
    final palette = StatusPalette.of(status);
    return Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
            color: palette.background,
            borderRadius: BorderRadius.circular(AppRadii.pill)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(statusIcon(status),
              size: 14, color: palette.foreground, semanticLabel: label),
          const SizedBox(width: 4),
          Flexible(
              child: Text(label,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      color: palette.foreground,
                      fontSize: 12,
                      fontWeight: FontWeight.w700))),
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
      this.onTap,
      this.actionLabel,
      this.actionKey,
      this.onAction});
  final String title, status, statusLabel;

  /// Nút hành động chính của thẻ (ví dụ "Nhận kiện"); bỏ trống thì không hiện.
  final String? actionLabel;
  final Key? actionKey;
  final VoidCallback? onAction;

  /// Mỗi phần tử là (icon, nội dung). Dòng trống bị bỏ.
  final List<(IconData, String)> lines;
  final String? time;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final shape = BorderRadius.circular(AppRadii.card);
    return DecoratedBox(
        decoration: BoxDecoration(
            color: AppColors.surface, borderRadius: shape, boxShadow: cardShadow),
        child: Material(
            color: Colors.transparent,
            borderRadius: shape,
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
                  if (actionLabel != null)
                    Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: FilledButton(
                            key: actionKey,
                            onPressed: onAction ?? onTap,
                            style: FilledButton.styleFrom(
                                minimumSize: const Size.fromHeight(44)),
                            child: Text(actionLabel!))),
                ])))));
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

const realtimeDiag = bool.fromEnvironment('REALTIME_DIAG');
