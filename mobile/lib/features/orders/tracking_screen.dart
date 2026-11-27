import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_failure.dart';
import '../../core/realtime/realtime_client.dart';
import '../../core/realtime/realtime_provider.dart';
import '../../core/theme/app_theme.dart';
import '../../core/ui/app_widgets.dart';
import '../../core/ui/async_state_view.dart';
import '../../core/ui/demo_badge.dart';
import '../ai/ai_panels.dart';
import '../ai/ai_task_controller.dart';
import '../flight/flight_api.dart';
import 'customer_orders_api.dart';
import 'order_models.dart';
import 'order_screens.dart';
import 'pickup_code.dart';
import 'tracking_controller.dart';
import 'tracking_state.dart';

/// Theo dõi đơn theo thời gian thực (màn 4). Dữ liệu thật: trạng thái, timeline, hai trạm và toạ độ,
/// vị trí SUY RA từ trạng thái (backend chưa có GPS), ETA công thức, tóm tắt, mã nhận hàng, hủy đơn.
/// Dữ liệu mẫu có nhãn "Demo": tốc độ, độ cao, pin drone (backend chưa có API số đo).
class OrderTrackingScreen extends ConsumerWidget {
  const OrderTrackingScreen({super.key, required this.orderId});
  final String orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(trackingProvider(orderId));
    final connected =
        ref.watch(realtimeStatusProvider).valueOrNull == RealtimeStatus.connected;
    final controller = ref.read(trackingProvider(orderId).notifier);
    final canCancel = s.loaded && s.allowedActions.contains('CANCEL');
    return Scaffold(
        appBar: AppBar(
            title: Text('Đơn ${orderId.substring(0, 8).toUpperCase()}'),
            leading: IconButton(
                tooltip: 'Quay lại',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => context.go('/customer')),
            actions: [
              IconButton(
                  tooltip: 'Chi tiết đơn',
                  key: const Key('open-detail'),
                  onPressed: () => context.go('/customer/orders/$orderId/detail'),
                  icon: const Icon(Icons.receipt_long_outlined)),
              IconButton(
                  tooltip: 'Tải lại',
                  onPressed: controller.reload,
                  icon: const Icon(Icons.refresh))
            ]),
        body: Column(children: [
          const ConnectionBanner(),
          if (s.error != null && s.loaded)
            MessageBanner(
                key: const Key('tracking-stale'),
                text: 'Chưa cập nhật được: ${s.error}',
                color: StatusPalette.failed.background,
                onClose: () {}),
          Expanded(
              child: AsyncStateView(
                  loading: !s.loaded && s.error == null,
                  error: !s.loaded && s.error != null
                      ? ApiFailure(FailureKind.unknown, s.error!)
                      : null,
                  onRetry: controller.reload,
                  child: RefreshIndicator(
                      onRefresh: controller.reload,
                      child: _Content(orderId: orderId, state: s, live: connected)))),
        ]),
        bottomNavigationBar: s.loaded
            ? _ActionBar(orderId: orderId, state: s, canCancel: canCancel)
            : null);
  }
}

class _Content extends ConsumerWidget {
  const _Content({required this.orderId, required this.state, required this.live});
  final String orderId;
  final TrackingState state;
  final bool live;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = state;
    final key = AiOrderKey(orderId, s.status, s.version);
    return ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          RouteMap(
              key: const Key('route-map'),
              pickup: s.pickup,
              dropoff: s.dropoff,
              phase: s.position?.phase,
              simulated: s.position?.simulated ?? false),
          const SizedBox(height: 14),
          Row(children: [
            KeyedSubtree(
                key: const Key('current-status'),
                child: StatusChip(s.status, s.statusLabel)),
            const SizedBox(width: 8),
            if (live && s.live && !s.denied)
              Container(
                  key: const Key('live-chip'),
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                  decoration: BoxDecoration(
                      color: StatusPalette.done.background,
                      borderRadius: BorderRadius.circular(AppRadii.pill)),
                  child: Row(mainAxisSize: MainAxisSize.min, children: [
                    Icon(Icons.circle, size: 8, color: StatusPalette.done.foreground),
                    const SizedBox(width: 6),
                    Text('Trực tuyến',
                        style: TextStyle(
                            color: StatusPalette.done.foreground,
                            fontSize: 12,
                            fontWeight: FontWeight.w700)),
                  ])),
          ]),
          if (s.denied)
            const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text('Không có quyền nhận cập nhật trực tuyến cho đơn này.')),
          const SizedBox(height: 14),
          if (s.status == 'AWAITING_PICKUP') ...[
            PickupCodeCard(orderId: orderId),
            const SizedBox(height: 14),
          ],
          if (s.status == 'IN_TRANSIT') ...[
            _Telemetry(orderId: orderId),
            const SizedBox(height: 14),
          ],
          AiEtaPanel(key),
          AiSummaryPanel(key),
          const SizedBox(height: 4),
          InfoCard('Tiến trình', [
            for (var i = s.timeline.length - 1; i >= 0; i--)
              TimelineTile(
                  entry: s.timeline[i],
                  isLatest: i == s.timeline.length - 1,
                  isFirst: i == 0),
          ]),
          if (realtimeDiag) DiagText(orderId: orderId, state: s),
        ]);
  }
}

class _ActionBar extends ConsumerWidget {
  const _ActionBar({required this.orderId, required this.state, required this.canCancel});
  final String orderId;
  final TrackingState state;
  final bool canCancel;

  Future<void> _cancel(BuildContext context, WidgetRef ref) async {
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
    if (ok != true || !context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      // If-Match = phiên bản đang xem: server từ chối (412/409) nếu đơn đã đổi.
      await ref.read(customerOrdersApiProvider).cancelOrder(orderId, state.version);
      messenger.showSnackBar(const SnackBar(content: Text('Đã hủy đơn.')));
    } on ApiFailure catch (f) {
      messenger.showSnackBar(SnackBar(content: Text(f.message)));
    }
    await ref.read(trackingProvider(orderId).notifier).reload();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) => BottomActionBar(child: Row(children: [
        Expanded(
            child: Tooltip(
                message: 'Sắp có',
                child: OutlinedButton.icon(
                    key: const Key('contact-support'),
                    // Backend chưa có kênh hỗ trợ: nút bị tắt, không giả vờ hoạt động.
                    onPressed: null,
                    icon: const Icon(Icons.support_agent_outlined),
                    label: const Text('Hỗ trợ (sắp có)')))),
        if (canCancel) ...[
          const SizedBox(width: 12),
          Expanded(
              child: FilledButton.icon(
                  key: const Key('cancel-order'),
                  style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
                  onPressed: () => _cancel(context, ref),
                  icon: const Icon(Icons.cancel_outlined),
                  label: const Text('Hủy đơn'))),
        ],
      ]));
}

/// Số đo của drone: DEMO cho tới khi backend có `/v1/orders/{id}/flight`.
class _Telemetry extends ConsumerWidget {
  const _Telemetry({required this.orderId});
  final String orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final flight = ref.watch(flightProvider(orderId));
    return InfoCard('Số đo drone',
        key: const Key('telemetry'),
        trailing: flight.valueOrNull?.isDemo == true ? const DemoBadge() : null,
        [
          flight.when(
              loading: () => const SkeletonBox(height: 56, radius: 14),
              error: (_, __) => const Text('Chưa có số đo của drone.'),
              data: (f) => Row(children: [
                    _Metric(Icons.speed, 'Tốc độ', '${f.data.telemetry.speedKmh.round()} km/h'),
                    _Metric(Icons.height, 'Độ cao', '${f.data.telemetry.altitudeM.round()} m'),
                    _Metric(Icons.battery_5_bar_outlined, 'Pin',
                        '${f.data.telemetry.batteryPercent}%'),
                  ])),
        ]);
  }
}

class _Metric extends StatelessWidget {
  const _Metric(this.icon, this.label, this.value);
  final IconData icon;
  final String label, value;
  @override
  Widget build(BuildContext context) => Expanded(
      child: Column(children: [
        Icon(icon, color: AppColors.primary),
        const SizedBox(height: 4),
        Text(value, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
        Text(label, style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
      ]));
}

/// Mã OTP nhận hàng (chỉ khi đơn chờ nhận). Mã chỉ ở bộ nhớ, rời màn là bỏ. Hết hạn thì cấp lại.
class PickupCodeCard extends ConsumerWidget {
  const PickupCodeCard({super.key, required this.orderId});
  final String orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final code = ref.watch(pickupCodeProvider(orderId));
    String two(int n) => n.toString().padLeft(2, '0');
    return InfoCard('Mã nhận hàng',
        key: const Key('pickup-code-card'),
        [
          code.when(
              loading: () => const SkeletonBox(height: 64, radius: 14),
              error: (e, _) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(e is ApiFailure ? e.message : 'Không lấy được mã nhận hàng.',
                        key: const Key('pickup-code-error')),
                    TextButton(
                        key: const Key('pickup-code-retry'),
                        onPressed: () => ref.invalidate(pickupCodeProvider(orderId)),
                        child: const Text('Thử lại')),
                  ]),
              data: (c) {
                final exp = c.expiresAt;
                return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Center(
                      child: Semantics(
                          label: 'Mã OTP ${c.otp.split('').join(' ')}',
                          excludeSemantics: true,
                          child: Text(c.otpSpaced,
                              key: const Key('pickup-otp'),
                              style: const TextStyle(
                                  fontSize: 36,
                                  letterSpacing: 4,
                                  fontWeight: FontWeight.w800,
                                  color: AppColors.dark)))),
                  const SizedBox(height: 6),
                  Text('Đưa mã này cho nhân viên trạm. Có hiệu lực đến '
                      '${two(exp.hour)}:${two(exp.minute)}. Không chia sẻ cho người khác.',
                      style: const TextStyle(color: AppColors.textMuted, height: 1.3)),
                  Align(
                      alignment: Alignment.centerLeft,
                      child: TextButton.icon(
                          key: const Key('pickup-code-refresh'),
                          onPressed: () => ref.invalidate(pickupCodeProvider(orderId)),
                          icon: const Icon(Icons.refresh, size: 18),
                          label: const Text('Làm mới mã'))),
                ]);
              }),
        ]);
  }
}

/// Mốc trên timeline: chấm và đường nối, mốc mới nhất nổi bật.
class TimelineTile extends StatelessWidget {
  const TimelineTile(
      {super.key, required this.entry, required this.isLatest, required this.isFirst});
  final TimelineEntry entry;
  final bool isLatest, isFirst;
  @override
  Widget build(BuildContext context) {
    final color = isLatest ? statusColor(context, entry.toStatus) : AppColors.border;
    return IntrinsicHeight(
        child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      SizedBox(
          width: 28,
          child: Column(children: [
            Icon(isLatest ? Icons.radio_button_checked : Icons.circle,
                size: isLatest ? 20 : 12, color: color),
            if (!isFirst) Expanded(child: Container(width: 2, color: AppColors.border)),
          ])),
      Expanded(
          child: Padding(
              padding: const EdgeInsets.only(bottom: 16, left: 8),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(entry.label,
                    key: Key('timeline-v${entry.version}'),
                    style: TextStyle(
                        fontWeight: isLatest ? FontWeight.w800 : FontWeight.w500)),
                Text(formatTime(entry.occurredAt),
                    style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
              ]))),
    ]));
  }
}

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

/// Bản đồ vẽ bằng code: hai trạm theo toạ độ thật, đường bay và vị trí drone SUY RA từ trạng thái.
/// Khi đang bay, điểm giữa tuyến chỉ là minh hoạ và được ghi rõ "Vị trí mô phỏng" (chưa có GPS).
class RouteMap extends StatelessWidget {
  const RouteMap(
      {super.key, this.pickup, this.dropoff, this.phase, this.simulated = false});
  final StationPoint? pickup, dropoff;

  /// NOT_STARTED, AT_PICKUP, IN_FLIGHT, AT_DROPOFF hoặc UNKNOWN (null = chưa biết).
  final String? phase;
  final bool simulated;

  /// Tiến độ minh hoạ trên tuyến 0..1; null = không vẽ drone.
  static double? progressOf(String? phase) => switch (phase) {
        'NOT_STARTED' || 'AT_PICKUP' => 0,
        'IN_FLIGHT' => 0.5,
        'AT_DROPOFF' => 1,
        _ => null,
      };

  /// Chiếu toạ độ vào khung [size] (giữ tỉ lệ ngang theo vĩ độ để tuyến không bị méo).
  static (Offset, Offset) project(StationPoint a, StationPoint b, Size size) {
    const padX = 44.0, padY = 54.0;
    final midLat = (a.latitude! + b.latitude!) / 2 * math.pi / 180;
    final k = math.cos(midLat).abs().clamp(0.2, 1.0);
    final ax = a.longitude! * k, bx = b.longitude! * k;
    final ay = -a.latitude!, by = -b.latitude!;
    final w = size.width - 2 * padX, h = size.height - 2 * padY;
    final dx = (bx - ax).abs(), dy = (by - ay).abs();
    // Hai trạm trùng nhau hoặc thẳng hàng: dùng cả bề rộng, đặt vào giữa theo chiều còn lại.
    final scale = (dx < 1e-9 && dy < 1e-9)
        ? 0.0
        : math.min(dx < 1e-9 ? double.infinity : w / dx, dy < 1e-9 ? double.infinity : h / dy);
    Offset at(double x, double y) => Offset(
        padX + w / 2 + (x - (ax + bx) / 2) * scale,
        padY + h / 2 + (y - (ay + by) / 2) * scale);
    final pa = at(ax, ay), pb = at(bx, by);
    // Nếu hai trạm gần nhau tới mức chồng lên nhau, tách ra theo chiều ngang để vẫn đọc được.
    if ((pa - pb).distance < 48) {
      return (Offset(size.width / 2 - 70, size.height / 2), Offset(size.width / 2 + 70, size.height / 2));
    }
    return (pa, pb);
  }

  @override
  Widget build(BuildContext context) {
    final a = pickup, b = dropoff;
    final ready = a != null && b != null && a.hasCoordinates && b.hasCoordinates;
    return Semantics(
        label: ready
            ? 'Bản đồ tuyến bay từ ${a.name} đến ${b.name}'
            : 'Chưa có toạ độ trạm để vẽ bản đồ',
        child: ClipRRect(
            borderRadius: BorderRadius.circular(AppRadii.card),
            child: Container(
                height: 230,
                color: AppColors.dark,
                child: !ready ? _empty() : LayoutBuilder(builder: (context, c) => _map(c, a, b)))));
  }

  Widget _empty() => const Center(
      child: Padding(
          padding: EdgeInsets.all(20),
          child: Text('Chưa có toạ độ trạm nên chưa vẽ được bản đồ.',
              key: Key('map-no-coordinates'),
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white70))));

  Widget _map(BoxConstraints c, StationPoint a, StationPoint b) {
    final size = Size(c.maxWidth, c.maxHeight);
    final (pa, pb) = project(a, b, size);
    final t = progressOf(phase);
    final drone = t == null ? null : Offset.lerp(pa, pb, t)!;
    return Stack(children: [
      Positioned.fill(child: CustomPaint(painter: _RoutePainter(pa, pb))),
      _label(pa, a.name, above: false),
      _label(pb, b.name, above: false),
      _dot(pa),
      _dot(pb),
      if (drone != null)
        Positioned(
            left: drone.dx - 16,
            top: drone.dy - 16,
            child: Container(
                key: const Key('map-drone'),
                width: 32,
                height: 32,
                decoration: const BoxDecoration(color: AppColors.amber, shape: BoxShape.circle),
                child: const Icon(Icons.flight, size: 18, color: AppColors.dark))),
      if (phase == 'IN_FLIGHT' && simulated)
        const Positioned(
            left: 12,
            top: 12,
            child: _MapNote(key: Key('map-simulated'), text: 'Vị trí mô phỏng, chưa có GPS drone')),
    ]);
  }

  Widget _dot(Offset p) => Positioned(
      left: p.dx - 7,
      top: p.dy - 7,
      child: Container(
          width: 14,
          height: 14,
          decoration: BoxDecoration(
              color: AppColors.tealBright,
              shape: BoxShape.circle,
              border: Border.all(color: Colors.white, width: 2))));

  Widget _label(Offset p, String text, {required bool above}) => Positioned(
      left: (p.dx - 55).clamp(4.0, double.infinity),
      top: p.dy + 12,
      width: 110,
      child: Text(text,
          textAlign: TextAlign.center,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600)));
}

class _MapNote extends StatelessWidget {
  const _MapNote({super.key, required this.text});
  final String text;
  @override
  Widget build(BuildContext context) => Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.14),
          borderRadius: BorderRadius.circular(AppRadii.pill)),
      child: Text(text, style: const TextStyle(color: Colors.white, fontSize: 11)));
}

class _RoutePainter extends CustomPainter {
  _RoutePainter(this.a, this.b);
  final Offset a, b;

  @override
  void paint(Canvas canvas, Size size) {
    final grid = Paint()
      ..color = Colors.white.withValues(alpha: 0.06)
      ..strokeWidth = 1;
    for (var x = 0.0; x < size.width; x += 32) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), grid);
    }
    for (var y = 0.0; y < size.height; y += 32) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), grid);
    }
    // Đường bay nét đứt.
    final line = Paint()
      ..color = AppColors.amber
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round;
    final total = (b - a).distance;
    if (total == 0) return;
    final dir = (b - a) / total;
    for (var d = 0.0; d < total; d += 14) {
      canvas.drawLine(a + dir * d, a + dir * math.min(d + 8, total), line);
    }
  }

  @override
  bool shouldRepaint(_RoutePainter old) => old.a != a || old.b != b;
}
