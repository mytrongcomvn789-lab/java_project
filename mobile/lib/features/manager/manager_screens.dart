import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_failure.dart';
import '../../core/theme/app_theme.dart';
import '../../core/ui/app_widgets.dart';
import '../../core/ui/async_state_view.dart';
import '../ai/ai_panels.dart' show AiSourceBadge;
import '../auth/screens.dart' show signOut;
import '../auth/session.dart';
import '../orders/order_models.dart' show labelOfStatus;
import 'manager_api.dart';
import 'manager_controllers.dart';

/// Phần trăm từ tỷ lệ 0..1. null (mẫu rỗng) hiện "—", không hiện 0%.
String formatPercent(double? v) {
  if (v == null) return '—';
  final p = v * 100;
  return p == p.roundToDouble() ? '${p.round()}%' : '${p.toStringAsFixed(1)}%';
}

/// Thời lượng dễ đọc từ số giây, ví dụ "28 phút", "1 giờ 5 phút". null hiện "—".
String formatDuration(double? seconds) {
  if (seconds == null) return '—';
  if (seconds < 60) return '${seconds.round()} giây';
  final minutes = (seconds / 60).round();
  if (minutes < 60) return '$minutes phút';
  final h = minutes ~/ 60, m = minutes % 60;
  return m == 0 ? '$h giờ' : '$h giờ $m phút';
}

const _auditLabels = {
  'LOGIN_SUCCEEDED': 'Đăng nhập thành công',
  'LOGIN_FAILED': 'Đăng nhập thất bại',
  'LOGOUT': 'Đăng xuất',
  'PASSWORD_CHANGED': 'Đổi mật khẩu',
  'USER_REGISTERED': 'Đăng ký tài khoản',
  'REFRESH_TOKEN_REUSED': 'Dùng lại refresh token (cảnh báo bảo mật)',
  'ACCOUNT_CREATED': 'Tạo tài khoản',
  'ACCOUNT_UPDATED': 'Sửa tài khoản',
  'USER_ACCESS_CHANGED': 'Đổi quyền truy cập',
  'ROLE_PERMISSIONS_UPDATED': 'Đổi quyền của vai trò',
  'SETTINGS_UPDATED': 'Đổi cấu hình hệ thống',
  'STATION_STATUS_CHANGED': 'Đổi trạng thái trạm',
  'API_KEY_ISSUED': 'Cấp khóa API',
  'API_KEY_REVOKED': 'Thu hồi khóa API',
  'API_KEY_ROTATED': 'Đổi khóa API',
  'AI_REQUEST': 'Yêu cầu AI',
};

String auditLabel(String action) => _auditLabels[action] ?? action;

const _warningLabels = {
  'CURRENT_STATUS_NOT_HISTORICAL_SNAPSHOT': 'Trạng thái đơn là hiện tại, không phải ảnh chụp lịch sử.',
  'STATION_LOAD_IS_CURRENT_SLOT_NOT_PERIOD_TOTAL': 'Tải trạm là của khung giờ hiện tại, không phải tổng cả kỳ.',
  'READ_COMMITTED_NOT_FROZEN_SNAPSHOT': 'Số liệu đọc tại thời điểm xem và có thể thay đổi.',
  'LOW_DELIVERY_SAMPLE': 'Mẫu giao hàng ít nên tỷ lệ chỉ mang tính tham khảo.',
  'MISSING_DELIVERY_TIMESTAMPS': 'Một số đơn thiếu mốc thời gian nên bị loại khỏi thời gian giao.',
  'TREND_ZERO_BASELINE': 'Kỳ đầu không có đơn nên không tính được phần trăm thay đổi.',
};

String warningLabel(String code) => _warningLabels[code] ?? code;

/// Trang chính của quản lý hậu cần: Tổng quan, Trạm, Nhật ký, Hồ sơ. Chỉ đọc: quản lý xem số liệu và nhận
/// định, không sửa đơn hay cấu hình. Xuất Excel/PDF dùng bản web.
class ManagerHomeScreen extends ConsumerStatefulWidget {
  const ManagerHomeScreen({super.key});
  @override
  ConsumerState<ManagerHomeScreen> createState() => _ManagerHomeScreenState();
}

class _ManagerHomeScreenState extends ConsumerState<ManagerHomeScreen> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(sessionProvider).user!;
    final title = const ['Tổng quan', 'Tải trạm', 'Nhật ký'][_tab];
    return Scaffold(
        backgroundColor: AppColors.background,
        body: Column(children: [
          Container(
              width: double.infinity,
              decoration: const BoxDecoration(
                  color: AppColors.dark,
                  borderRadius: BorderRadius.vertical(bottom: Radius.circular(28))),
              child: SafeArea(
                  bottom: false,
                  child: Padding(
                      padding: const EdgeInsets.fromLTRB(20, 12, 8, 18),
                      child: Row(children: [
                        Expanded(
                            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Text('Quản lý hậu cần · ${user.name.isEmpty ? user.email : user.name}',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                  color: Colors.white70, fontSize: 12, fontWeight: FontWeight.w500)),
                          Text(title,
                              key: const Key('manager-title'),
                              style: const TextStyle(
                                  color: Colors.white, fontSize: 24, fontWeight: FontWeight.w800)),
                        ])),
                        IconButton(
                            key: const Key('logout'),
                            tooltip: 'Đăng xuất',
                            onPressed: () => signOut(ref),
                            icon: const Icon(Icons.logout, color: Colors.white)),
                      ])))),
          Expanded(
              child: IndexedStack(index: _tab, children: const [
            _OverviewTab(),
            _StationsTab(),
            _AuditTab(),
          ])),
        ]),
        bottomNavigationBar: AppBottomNav(
            selectedIndex: _tab,
            items: const [
              NavItem(Icons.insights_outlined, 'Tổng quan', key: Key('nav-overview')),
              NavItem(Icons.warehouse_outlined, 'Trạm', key: Key('nav-stations')),
              NavItem(Icons.fact_check_outlined, 'Nhật ký', key: Key('nav-audit')),
              NavItem(Icons.person_outline, 'Hồ sơ', key: Key('nav-profile')),
            ],
            onTap: (i) {
              if (i == 3) {
                context.go('/profile');
              } else {
                setState(() => _tab = i);
              }
            }));
  }
}

// ---------------------------------------------------------------------------------------------------
// Tổng quan

class _OverviewTab extends ConsumerWidget {
  const _OverviewTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final days = ref.watch(statsDaysProvider);
    final summary = ref.watch(statsSummaryProvider(days));
    final daily = ref.watch(statsDailyProvider(days));
    Future<void> refresh() async {
      ref.invalidate(statsSummaryProvider(days));
      ref.invalidate(statsDailyProvider(days));
      try {
        await ref.read(statsSummaryProvider(days).future);
      } catch (_) {}
    }

    return RefreshIndicator(
        onRefresh: refresh,
        child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 24),
            children: [
              Wrap(spacing: 8, children: [
                for (final d in statsPeriods)
                  ChoiceChip(
                      key: Key('period-$d'),
                      label: Text('$d ngày qua'),
                      selected: d == days,
                      showCheckmark: false,
                      backgroundColor: AppColors.surface,
                      selectedColor: AppColors.primary,
                      labelStyle: TextStyle(
                          fontWeight: FontWeight.w600,
                          color: d == days ? Colors.white : AppColors.text),
                      onSelected: (_) => ref.read(statsDaysProvider.notifier).state = d),
              ]),
              const SizedBox(height: 12),
              summary.when(
                  loading: () => const _KpiSkeleton(),
                  error: (e, _) => _ErrorCard(toApiFailure(e), () => ref.invalidate(statsSummaryProvider(days))),
                  data: (s) => _SummaryBody(s)),
              const SizedBox(height: 4),
              InfoCard('Đơn theo ngày', [
                daily.when(
                    loading: () => const SkeletonBox(height: 120, radius: 14),
                    error: (e, _) => Text(toApiFailure(e).message),
                    data: (points) => DailyBars(points: points)),
              ]),
              const _AnalyticsCard(),
            ]));
  }
}

class _ErrorCard extends StatelessWidget {
  const _ErrorCard(this.failure, this.onRetry);
  final ApiFailure failure;
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) => InfoCard('Không tải được số liệu', [
        Text(failure.message, key: const Key('stats-error')),
        TextButton(key: const Key('stats-retry'), onPressed: onRetry, child: const Text('Thử lại')),
      ]);
}

class _KpiSkeleton extends StatelessWidget {
  const _KpiSkeleton();
  @override
  Widget build(BuildContext context) => const Column(key: Key('stats-loading'), children: [
        SkeletonBox(height: 84, radius: 18),
        SizedBox(height: 10),
        SkeletonBox(height: 84, radius: 18),
      ]);
}

class _SummaryBody extends StatelessWidget {
  const _SummaryBody(this.s);
  final StatsSummary s;

  @override
  Widget build(BuildContext context) {
    final o = s.orders;
    return Column(children: [
      IntrinsicHeight(
          child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Expanded(child: _Kpi(const Key('kpi-total'), 'Tổng đơn', '${o.total}', Icons.inventory_2_outlined)),
        const SizedBox(width: 10),
        Expanded(child: _Kpi(const Key('kpi-progress'), 'Đang xử lý', '${o.inProgress}', Icons.flight_takeoff)),
      ])),
      const SizedBox(height: 10),
      IntrinsicHeight(
          child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Expanded(child: _Kpi(const Key('kpi-delivered'), 'Đã giao', '${o.delivered}', Icons.check_circle_outline)),
        const SizedBox(width: 10),
        Expanded(
            child: _Kpi(const Key('kpi-success'), 'Giao thành công', formatPercent(s.successRate),
                Icons.percent,
                hint: s.deliverySample == 0 ? 'Chưa có đơn kết thúc' : 'Mẫu ${s.deliverySample} đơn')),
      ])),
      const SizedBox(height: 12),
      InfoCard('Phân bố theo trạng thái', [_StatusBars(s.byStatus)]),
      InfoCard('Chỉ số giao hàng', [
        InfoRow(labelWidth: 150, 'Thời gian giao TB',
            s.deliveryTimeCount == 0 ? '—' : '${formatDuration(s.avgDeliverySeconds)} (${s.deliveryTimeCount} đơn)'),
        InfoRow(labelWidth: 150, 'Tỷ lệ hoàn hàng', formatPercent(s.returnRate)),
        InfoRow(labelWidth: 150, 'Tỷ lệ hủy', formatPercent(s.cancelRate)),
        InfoRow(labelWidth: 150, 'Số lần giao', '${s.totalAttempts}'),
        InfoRow(labelWidth: 150, 'Số lần giao lại', '${s.retryAttempts}'),
      ]),
    ]);
  }
}

class _Kpi extends StatelessWidget {
  const _Kpi(this.keyValue, this.label, this.value, this.icon, {this.hint});
  final Key keyValue;
  final String label, value;
  final IconData icon;
  final String? hint;

  @override
  Widget build(BuildContext context) => Semantics(
      label: '$label: $value',
      excludeSemantics: true,
      child: DecoratedBox(
          decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(AppRadii.card),
              boxShadow: cardShadow),
          child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Icon(icon, size: 18, color: AppColors.primary),
                  const SizedBox(width: 6),
                  Expanded(
                      child: Text(label,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(color: AppColors.textMuted, fontSize: 13))),
                ]),
                const SizedBox(height: 6),
                Text(value,
                    key: keyValue,
                    style: const TextStyle(
                        fontSize: 28, fontWeight: FontWeight.w800, color: AppColors.dark)),
                if (hint != null)
                  Text(hint!, style: const TextStyle(color: AppColors.textMuted, fontSize: 11)),
              ]))));
}

/// Thanh ngang theo từng trạng thái có đơn, màu theo bảng trạng thái dùng chung.
class _StatusBars extends StatelessWidget {
  const _StatusBars(this.byStatus);
  final Map<String, int> byStatus;

  static const _order = [
    'PENDING', 'APPROVED', 'SCHEDULED', 'PREPARING', 'IN_TRANSIT', 'AWAITING_PICKUP', 'DELIVERED',
    'FAILED', 'RETURNING', 'RETURNED', 'CANCELLED', 'REJECTED',
  ];

  @override
  Widget build(BuildContext context) {
    final rows = [for (final k in _order) if ((byStatus[k] ?? 0) > 0) (k, byStatus[k]!)];
    if (rows.isEmpty) {
      return const Text('Chưa có đơn nào trong kỳ này.', key: Key('status-empty'),
          style: TextStyle(color: AppColors.textMuted));
    }
    final max = rows.map((r) => r.$2).reduce((a, b) => a > b ? a : b);
    return Column(children: [
      for (final (status, n) in rows)
        Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(children: [
              SizedBox(
                  width: 126,
                  child: Text(labelOfStatus(status),
                      maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 13))),
              Expanded(
                  child: ClipRRect(
                      borderRadius: BorderRadius.circular(AppRadii.pill),
                      child: LinearProgressIndicator(
                          key: Key('bar-$status'),
                          value: n / max,
                          minHeight: 10,
                          backgroundColor: AppColors.border.withValues(alpha: 0.5),
                          valueColor: AlwaysStoppedAnimation(StatusPalette.of(status).foreground)))),
              SizedBox(
                  width: 34,
                  child: Text('$n', textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w700))),
            ])),
    ]);
  }
}

/// Biểu đồ cột số đơn theo ngày, vẽ bằng code. Ngày không có đơn vẫn có cột (rỗng) để thấy cả kỳ.
class DailyBars extends StatelessWidget {
  const DailyBars({super.key, required this.points});
  final List<DailyPoint> points;

  /// "2026-11-27" → "27/11".
  static String shortDate(String iso) {
    final p = iso.split('-');
    return p.length == 3 ? '${p[2]}/${p[1]}' : iso;
  }

  @override
  Widget build(BuildContext context) {
    final total = points.fold<int>(0, (a, p) => a + p.orders.total);
    if (points.isEmpty || total == 0) {
      return const Text('Chưa có đơn nào trong kỳ này.', key: Key('daily-empty'),
          style: TextStyle(color: AppColors.textMuted));
    }
    final peak = points.reduce((a, b) => a.orders.total >= b.orders.total ? a : b);
    return Semantics(
        label: 'Biểu đồ số đơn theo ngày, tổng $total đơn, cao nhất ${peak.orders.total} đơn ngày ${shortDate(peak.date)}',
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(
              height: 120,
              width: double.infinity,
              child: CustomPaint(key: const Key('daily-chart'), painter: _BarsPainter(points))),
          const SizedBox(height: 6),
          Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            Text(shortDate(points.first.date),
                style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
            Text(shortDate(points.last.date),
                style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
          ]),
          const SizedBox(height: 4),
          Text('Cao nhất ${peak.orders.total} đơn (${shortDate(peak.date)})',
              style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
        ]));
  }
}

class _BarsPainter extends CustomPainter {
  _BarsPainter(this.points);
  final List<DailyPoint> points;

  @override
  void paint(Canvas canvas, Size size) {
    final maxV = points.map((p) => p.orders.total).fold<int>(1, (a, b) => a > b ? a : b);
    final gap = 3.0;
    final w = (size.width - gap * (points.length - 1)) / points.length;
    final base = Paint()..color = AppColors.border.withValues(alpha: 0.6);
    final fill = Paint()..color = AppColors.primary;
    for (var i = 0; i < points.length; i++) {
      final x = i * (w + gap);
      canvas.drawRRect(
          RRect.fromRectAndRadius(Rect.fromLTWH(x, size.height - 3, w, 3), const Radius.circular(1.5)), base);
      final v = points[i].orders.total;
      if (v > 0) {
        final h = (size.height - 3) * v / maxV;
        canvas.drawRRect(
            RRect.fromRectAndRadius(Rect.fromLTWH(x, size.height - h, w, h), const Radius.circular(3)), fill);
      }
    }
  }

  @override
  bool shouldRepaint(_BarsPainter old) => old.points != points;
}

/// Nhận định vận hành. Chỉ chạy khi bấm (có thể tốn hạn mức AI). Văn bản luôn kèm nhãn nguồn: AI chọn
/// nhận định đã kiểm chứng, hay bản dự phòng theo quy tắc (không dùng AI).
class _AnalyticsCard extends ConsumerWidget {
  const _AnalyticsCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(analyticsProvider);
    final controller = ref.read(analyticsProvider.notifier);
    final result = state.envelope?.result;
    return InfoCard('Nhận định vận hành', [
      switch (state.phase) {
        AnalyticsPhase.idle => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('Tóm tắt các điểm đáng chú ý của kỳ đang chọn. Chỉ đọc, không thay đổi dữ liệu.',
                style: TextStyle(color: AppColors.textMuted, height: 1.3)),
            const SizedBox(height: 10),
            FilledButton(
                key: const Key('analytics-run'), onPressed: controller.start, child: const Text('Tạo nhận định')),
          ]),
        AnalyticsPhase.loading => const Row(key: Key('analytics-loading'), children: [
            SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
            SizedBox(width: 10),
            Text('Đang phân tích…'),
          ]),
        AnalyticsPhase.error => Column(
            key: const Key('analytics-error'),
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(state.error!.message),
              if (state.error!.retryable)
                TextButton(
                    key: const Key('analytics-retry'), onPressed: controller.start, child: const Text('Thử lại')),
            ]),
        AnalyticsPhase.success => _analysis(result, state, controller),
      },
    ]);
  }

  Widget _analysis(AnalyticsResult? r, AnalyticsState state, AnalyticsController controller) {
    if (r == null) {
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Chưa có nhận định cho kỳ này.', key: Key('analytics-none')),
        const SizedBox(height: 6),
        AiSourceBadge(state.envelope!),
      ]);
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      for (final t in r.insights)
        Padding(padding: const EdgeInsets.only(bottom: 6), child: Text('• $t', style: const TextStyle(height: 1.3))),
      if (r.recommendations.isNotEmpty) ...[
        const SizedBox(height: 6),
        const Text('Gợi ý', style: TextStyle(fontWeight: FontWeight.w700, color: AppColors.primary)),
        const SizedBox(height: 4),
        for (final t in r.recommendations)
          Padding(padding: const EdgeInsets.only(bottom: 4), child: Text('→ $t', style: const TextStyle(height: 1.3))),
      ],
      if (r.warnings.isNotEmpty) ...[
        const SizedBox(height: 8),
        ExpansionTile(
            key: const Key('analytics-warnings'),
            tilePadding: EdgeInsets.zero,
            title: const Text('Lưu ý về số liệu', style: TextStyle(fontSize: 13)),
            children: [
              for (final w in r.warnings)
                Align(
                    alignment: Alignment.centerLeft,
                    child: Padding(
                        padding: const EdgeInsets.only(bottom: 4),
                        child: Text('• ${warningLabel(w)}',
                            style: const TextStyle(color: AppColors.textMuted, fontSize: 12)))),
            ]),
      ],
      const SizedBox(height: 6),
      Text(
          r.isFallback
              ? 'Nhận định tự động theo quy tắc cố định, không dùng AI.'
              : 'AI chỉ chọn các nhận định đã được hệ thống kiểm chứng, không tự đưa ra số liệu mới.',
          key: const Key('analytics-mode'),
          style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
      const SizedBox(height: 6),
      AiSourceBadge(state.envelope!),
      TextButton(
          key: const Key('analytics-rerun'), onPressed: controller.start, child: const Text('Tạo lại nhận định')),
    ]);
  }
}

// ---------------------------------------------------------------------------------------------------
// Trạm

class _StationsTab extends ConsumerWidget {
  const _StationsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final loads = ref.watch(stationLoadProvider);
    return RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(stationLoadProvider);
          try {
            await ref.read(stationLoadProvider.future);
          } catch (_) {}
        },
        child: loads.when(
            loading: () => const AsyncStateView(loading: true, child: SizedBox.shrink()),
            error: (e, _) => AsyncStateView(
                error: toApiFailure(e),
                onRetry: () => ref.invalidate(stationLoadProvider),
                child: const SizedBox.shrink()),
            data: (list) => AsyncStateView(
                isEmpty: list.isEmpty,
                emptyIcon: Icons.warehouse_outlined,
                emptyTitle: 'Chưa có trạm nào',
                child: ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 14, 16, 24),
                    children: [
                      const Padding(
                          padding: EdgeInsets.only(bottom: 10),
                          child: Text('Tải của khung giờ hiện tại, không phải tổng cả kỳ.',
                              key: Key('load-note'),
                              style: TextStyle(color: AppColors.textMuted, fontSize: 13))),
                      for (final s in list) _StationCard(s),
                    ]))));
  }
}

class _StationCard extends StatelessWidget {
  const _StationCard(this.s);
  final StationLoad s;

  @override
  Widget build(BuildContext context) {
    final active = s.status == 'ACTIVE';
    return InfoCard(s.name, key: Key('station-${s.stationId}'), trailing: Text(
        active ? 'Hoạt động' : 'Không hoạt động',
        style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w700,
            color: active ? StatusPalette.done.foreground : StatusPalette.failed.foreground)), [
      StationCapacityBar(used: s.booked, capacity: s.capacity, onDark: false, label: 'Lịch giao đã đặt'),
      const SizedBox(height: 8),
      InfoRow('Đang xử lý', '${s.processing} kiện'),
      InfoRow('Mức sử dụng', formatPercent(s.utilization)),
    ]);
  }
}

// ---------------------------------------------------------------------------------------------------
// Nhật ký

const _auditFilters = [('', 'Tất cả'), ('LOGIN_FAILED', 'Đăng nhập lỗi'), ('AI_REQUEST', 'Yêu cầu AI')];
final _auditFilterProvider = StateProvider.autoDispose<String>((ref) => '');

class _AuditTab extends ConsumerStatefulWidget {
  const _AuditTab();
  @override
  ConsumerState<_AuditTab> createState() => _AuditTabState();
}

class _AuditTabState extends ConsumerState<_AuditTab> {
  final _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      if (_scroll.hasClients && _scroll.position.extentAfter < 300) {
        ref.read(auditProvider(ref.read(_auditFilterProvider)).notifier).loadMore();
      }
    });
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  String _when(DateTime? t) {
    if (t == null) return '—';
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(t.hour)}:${two(t.minute)} ${two(t.day)}/${two(t.month)}/${t.year}';
  }

  @override
  Widget build(BuildContext context) {
    final filter = ref.watch(_auditFilterProvider);
    final s = ref.watch(auditProvider(filter));
    final controller = ref.read(auditProvider(filter).notifier);
    return Column(children: [
      Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: Wrap(spacing: 8, runSpacing: 4, children: [
            for (final (value, label) in _auditFilters)
              ChoiceChip(
                  key: Key('audit-filter-${value.isEmpty ? 'all' : value}'),
                  label: Text(label),
                  selected: filter == value,
                  showCheckmark: false,
                  backgroundColor: AppColors.surface,
                  selectedColor: AppColors.primary,
                  labelStyle: TextStyle(
                      fontWeight: FontWeight.w600, color: filter == value ? Colors.white : AppColors.text),
                  onSelected: (_) => ref.read(_auditFilterProvider.notifier).state = value),
          ])),
      Expanded(
          child: RefreshIndicator(
              onRefresh: controller.reload,
              child: AsyncStateView(
                  loading: s.loading,
                  error: s.error,
                  onRetry: controller.reload,
                  isEmpty: s.items.isEmpty,
                  emptyIcon: Icons.fact_check_outlined,
                  emptyTitle: 'Chưa có sự kiện nào',
                  child: ListView.separated(
                      controller: _scroll,
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.fromLTRB(16, 10, 16, 24),
                      itemCount: s.items.length + (s.hasMore || s.moreError != null ? 1 : 0),
                      separatorBuilder: (_, __) => const SizedBox(height: 8),
                      itemBuilder: (context, i) {
                        if (i >= s.items.length) {
                          return s.moreError != null
                              ? Center(
                                  child: TextButton(
                                      key: const Key('audit-more-retry'),
                                      onPressed: controller.loadMore,
                                      child: const Text('Tải thêm không được. Thử lại')))
                              : const Padding(
                                  padding: EdgeInsets.all(16),
                                  child: Center(child: CircularProgressIndicator()));
                        }
                        final e = s.items[i];
                        return DecoratedBox(
                            key: Key('audit-${e.id}'),
                            decoration: BoxDecoration(
                                color: AppColors.surface,
                                borderRadius: BorderRadius.circular(AppRadii.button),
                                boxShadow: cardShadow),
                            child: Padding(
                                padding: const EdgeInsets.all(12),
                                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                                  Text(auditLabel(e.action),
                                      style: const TextStyle(fontWeight: FontWeight.w700)),
                                  if ((e.actorEmail ?? '').isNotEmpty)
                                    Text(e.actorEmail!, style: const TextStyle(color: AppColors.textMuted)),
                                  Text(_when(e.occurredAt),
                                      style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
                                ])));
                      })))),
    ]);
  }
}
