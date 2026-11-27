import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_failure.dart';
import '../../core/theme/app_theme.dart';
import '../../core/ui/app_widgets.dart';
import '../../core/ui/async_state_view.dart';
import '../ai/ai_panels.dart';
import '../ai/ai_task_controller.dart';
import '../auth/screens.dart' show signOut;
import '../auth/session.dart';
import '../operator/operator_controllers.dart' show OperatorListState, assignedStationsProvider;
import '../orders/customer_order_models.dart' show OrderDetail, OrderSummary, Station;
import '../orders/order_detail_controller.dart' show stationsProvider;
import '../orders/order_screens.dart' show OrderListCard, StatusChip, formatTime;
import 'dispatcher_controllers.dart';
import 'dispatcher_widgets.dart';

String _stationName(List<Station>? stations, String id) {
  for (final s in stations ?? const <Station>[]) {
    if (s.id == id) return s.name;
  }
  return 'Trạm ${id.length >= 8 ? id.substring(0, 8) : id}';
}

/// Trang chính của điều phối viên (màn "Đơn cần xử lý"): ba nhóm đúng như web gồm chờ duyệt,
/// chờ lên lịch và giao thất bại. Quyền do backend quyết định; app chỉ hiện nút theo trạng thái đơn.
class DispatcherHomeScreen extends ConsumerWidget {
  const DispatcherHomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) =>
      const DefaultTabController(length: 3, child: _DispatcherScaffold());
}

class _DispatcherScaffold extends ConsumerWidget {
  const _DispatcherScaffold();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final lists = {for (final s in dispatcherStatuses) s: ref.watch(dispatcherListProvider(s))};
    String count(OperatorListState s) => s.loading || s.error != null ? '' : ' (${s.total})';
    return Scaffold(
        backgroundColor: AppColors.background,
        body: Column(children: [
          _Header(lists: lists),
          Container(
              margin: const EdgeInsets.fromLTRB(16, 14, 16, 6),
              padding: const EdgeInsets.all(4),
              decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(AppRadii.pill),
                  border: Border.all(color: AppColors.border)),
              child: TabBar(
                  indicatorSize: TabBarIndicatorSize.tab,
                  indicator: BoxDecoration(
                      color: AppColors.primary,
                      borderRadius: BorderRadius.circular(AppRadii.pill)),
                  dividerColor: Colors.transparent,
                  labelColor: Colors.white,
                  unselectedLabelColor: AppColors.textMuted,
                  // Bỏ đệm hai bên: nhãn có số đếm dài hơn tab nên bị cắt mất ")".
                  labelPadding: EdgeInsets.zero,
                  labelStyle: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
                  tabs: [
                    Tab(key: const Key('tab-PENDING'), height: 40, text: 'Chờ duyệt${count(lists['PENDING']!)}'),
                    Tab(key: const Key('tab-APPROVED'), height: 40, text: 'Chờ lịch${count(lists['APPROVED']!)}'),
                    Tab(key: const Key('tab-FAILED'), height: 40, text: 'Thất bại${count(lists['FAILED']!)}'),
                  ])),
          Expanded(
              child: TabBarView(children: [
            for (final (status, empty, action) in const [
              ('PENDING', 'Không có đơn nào đang chờ duyệt.', 'Xem và duyệt'),
              ('APPROVED', 'Không có đơn nào đang chờ lên lịch.', 'Lên lịch'),
              ('FAILED', 'Không có đơn nào giao thất bại cần xử lý.', 'Xử lý'),
            ])
              _OrderList(
                  key: Key('list-$status'),
                  status: status,
                  emptyText: empty,
                  actionLabel: action),
          ])),
        ]),
        bottomNavigationBar: AppBottomNav(
            selectedIndex: 0,
            items: const [
              NavItem(Icons.inbox_outlined, 'Đơn cần xử lý', key: Key('nav-orders')),
              NavItem(Icons.warehouse_outlined, 'Trạm', key: Key('nav-stations')),
              NavItem(Icons.person_outline, 'Hồ sơ', key: Key('nav-profile')),
            ],
            onTap: (i) {
              switch (i) {
                case 1:
                  showModalBottomSheet<void>(
                      context: context,
                      isScrollControlled: true,
                      builder: (_) => const _StationsSheet());
                case 2:
                  context.go('/profile');
              }
            }));
  }
}

class _Header extends ConsumerWidget {
  const _Header({required this.lists});
  final Map<String, OperatorListState> lists;

  String _n(String status) {
    final s = lists[status]!;
    return s.loading || s.error != null ? '' : '${s.total}';
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionProvider).user!;
    return Container(
        width: double.infinity,
        decoration: const BoxDecoration(
            color: AppColors.dark,
            borderRadius: BorderRadius.vertical(bottom: Radius.circular(28))),
        child: SafeArea(
            bottom: false,
            child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 12, 8, 20),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Expanded(
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      const Text('Điều phối viên',
                          style: TextStyle(color: Colors.white70, fontSize: 12, fontWeight: FontWeight.w500)),
                      Text(user.name.isEmpty ? user.email : user.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              color: Colors.white, fontSize: 22, fontWeight: FontWeight.w800)),
                    ])),
                    IconButton(
                        key: const Key('logout'),
                        tooltip: 'Đăng xuất',
                        onPressed: () => signOut(ref),
                        icon: const Icon(Icons.logout, color: Colors.white)),
                  ]),
                  const SizedBox(height: 16),
                  Padding(
                      padding: const EdgeInsets.only(right: 12),
                      child: Row(children: [
                        Expanded(child: StatTile(value: _n('PENDING'), label: 'Chờ duyệt')),
                        const SizedBox(width: 10),
                        Expanded(child: StatTile(value: _n('APPROVED'), label: 'Chờ lịch')),
                        const SizedBox(width: 10),
                        Expanded(child: StatTile(value: _n('FAILED'), label: 'Thất bại')),
                      ])),
                ]))));
  }
}

/// Danh sách một nhóm đơn: kéo để làm mới, cuộn gần cuối thì tải thêm, có trạng thái tải/lỗi/rỗng.
class _OrderList extends ConsumerStatefulWidget {
  const _OrderList(
      {super.key, required this.status, required this.emptyText, required this.actionLabel});
  final String status, emptyText, actionLabel;

  @override
  ConsumerState<_OrderList> createState() => _OrderListState();
}

class _OrderListState extends ConsumerState<_OrderList> {
  final _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      if (_scroll.hasClients && _scroll.position.extentAfter < 300) {
        ref.read(dispatcherListProvider(widget.status).notifier).loadMore();
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
    final s = ref.watch(dispatcherListProvider(widget.status));
    final controller = ref.read(dispatcherListProvider(widget.status).notifier);
    final stations = ref.watch(stationsProvider).valueOrNull;
    return RefreshIndicator(
        onRefresh: controller.reload,
        child: AsyncStateView(
            loading: s.loading,
            error: s.error,
            onRetry: controller.reload,
            isEmpty: s.items.isEmpty,
            emptyIcon: Icons.inbox_outlined,
            emptyTitle: widget.emptyText,
            child: ListView.separated(
                controller: _scroll,
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
                itemCount: s.items.length + (s.hasMore ? 1 : 0),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemBuilder: (context, i) {
                  if (i < s.items.length) {
                    return _Card(s.items[i], stations, widget.actionLabel);
                  }
                  if (s.moreError != null) {
                    return Center(
                        child: TextButton(
                            key: const Key('load-more-retry'),
                            onPressed: controller.loadMore,
                            child: const Text('Tải thêm không được. Thử lại')));
                  }
                  return const Padding(
                      padding: EdgeInsets.all(16),
                      child: Center(child: CircularProgressIndicator()));
                })));
  }
}

class _Card extends StatelessWidget {
  const _Card(this.order, this.stations, this.actionLabel);
  final OrderSummary order;
  final List<Station>? stations;
  final String actionLabel;

  @override
  Widget build(BuildContext context) {
    final weight = order.packageWeightGrams;
    return OrderListCard(
        key: Key('order-${order.id}'),
        title: 'Đơn ${order.shortCode}',
        status: order.status,
        statusLabel: order.statusLabel,
        lines: [
          (
            Icons.route_outlined,
            '${_stationName(stations, order.pickupStationId)} → ${_stationName(stations, order.dropoffStationId)}'
          ),
          (
            Icons.inventory_2_outlined,
            [
              if (order.packageDescription != null) order.packageDescription!,
              if (weight != null) '$weight g',
            ].join(' · ')
          ),
          (Icons.person_outline, 'Người nhận: ${order.recipientName}'),
        ],
        time: formatTime(order.updatedAt ?? order.createdAt),
        actionLabel: actionLabel,
        actionKey: Key('action-${order.id}'),
        onAction: () => context.push('/dispatcher/orders/${order.id}'),
        onTap: () => context.push('/dispatcher/orders/${order.id}'));
  }
}

class _StationsSheet extends ConsumerWidget {
  const _StationsSheet();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stations = ref.watch(assignedStationsProvider);
    return SafeArea(
        child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.7),
            child: ListView(shrinkWrap: true, padding: const EdgeInsets.all(20), children: [
              Text('Trạm và sức chứa',
                  style: Theme.of(context)
                      .textTheme
                      .titleLarge
                      ?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 12),
              stations.when(
                  loading: () => const Padding(
                      padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator())),
                  error: (e, _) => AsyncStateView(
                      error: toApiFailure(e),
                      onRetry: () => ref.invalidate(assignedStationsProvider),
                      child: const SizedBox.shrink()),
                  data: (list) => Column(children: [
                        for (final s in list)
                          Padding(
                              padding: const EdgeInsets.only(bottom: 12),
                              child: Card(
                                  child: Padding(
                                      padding: const EdgeInsets.all(14),
                                      child: Column(
                                          crossAxisAlignment: CrossAxisAlignment.start,
                                          children: [
                                            Row(children: [
                                              Expanded(
                                                  child: Text(s.name,
                                                      style: const TextStyle(
                                                          fontSize: 16, fontWeight: FontWeight.w700))),
                                              Text(s.isActive ? 'Hoạt động' : 'Không hoạt động',
                                                  style: TextStyle(
                                                      fontSize: 12,
                                                      fontWeight: FontWeight.w700,
                                                      color: s.isActive
                                                          ? StatusPalette.done.foreground
                                                          : StatusPalette.failed.foreground)),
                                            ]),
                                            if ((s.address ?? '').isNotEmpty)
                                              Padding(
                                                  padding: const EdgeInsets.only(top: 4, bottom: 10),
                                                  child: Text(s.address!,
                                                      style: const TextStyle(color: AppColors.textMuted))),
                                            StationCapacityBar(
                                                used: s.slotUsed, capacity: s.capacity, onDark: false),
                                          ])))),
                      ])),
            ])));
  }
}

/// Chi tiết đơn của điều phối viên: thông tin, lịch sử, tóm tắt AI và các thao tác theo trạng thái:
/// PENDING (duyệt, từ chối), APPROVED (lên lịch), FAILED (giao lại, hoàn hàng), đang giao dở (báo thất bại).
class DispatcherOrderScreen extends ConsumerWidget {
  const DispatcherOrderScreen({super.key, required this.orderId});
  final String orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(dispatcherDetailProvider(orderId));
    final controller = ref.read(dispatcherDetailProvider(orderId).notifier);
    final stations = ref.watch(stationsProvider).valueOrNull;
    final order = state.order;
    return Scaffold(
        appBar: AppBar(
            title: Text(order == null ? 'Chi tiết đơn' : 'Đơn ${order.shortCode}'),
            leading: IconButton(
                tooltip: 'Quay lại',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => context.canPop() ? context.pop() : context.go('/dispatcher')),
            actions: [
              IconButton(
                  tooltip: 'Tải lại',
                  onPressed: controller.reload,
                  icon: const Icon(Icons.refresh)),
            ]),
        body: Column(children: [
          if (state.notice != null)
            MessageBanner(
                key: const Key('detail-notice'),
                text: state.notice!,
                color: StatusPalette.done.background,
                onClose: controller.dismiss),
          if (state.actionError != null)
            MessageBanner(
                key: const Key('detail-action-error'),
                text: state.actionError!.message,
                color: StatusPalette.failed.background,
                onClose: controller.dismiss),
          Expanded(
              child: AsyncStateView(
                  loading: state.loading && order == null,
                  error: order == null ? state.error : null,
                  onRetry: controller.reload,
                  child: order == null
                      ? const SizedBox.shrink()
                      : RefreshIndicator(
                          onRefresh: controller.reload,
                          child: _Body(order: order, stations: stations)))),
        ]),
        bottomNavigationBar:
            order == null ? null : _ActionBar(order: order, busy: state.busy, controller: controller));
  }
}

class _Body extends StatelessWidget {
  const _Body({required this.order, required this.stations});
  final OrderDetail order;
  final List<Station>? stations;

  @override
  Widget build(BuildContext context) {
    final p = order.package;
    String n(double v) => v == v.roundToDouble() ? v.toInt().toString() : v.toString();
    return ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: [
          Row(children: [
            KeyedSubtree(
                key: const Key('detail-status'), child: StatusChip(order.status, order.statusLabel)),
            const SizedBox(width: 10),
            Expanded(
                child: Text('Tạo lúc ${formatTime(order.createdAt)}',
                    style: const TextStyle(color: AppColors.textMuted, fontSize: 12))),
          ]),
          const SizedBox(height: 14),
          AiSummaryPanel(AiOrderKey(order.id, order.status, order.version)),
          InfoCard('Lộ trình', [
            InfoRow('Trạm gửi', _stationName(stations, order.pickupStationId)),
            InfoRow('Trạm nhận', _stationName(stations, order.dropoffStationId)),
          ]),
          InfoCard('Người nhận', [
            InfoRow('Họ tên', order.recipient.name),
            InfoRow('Điện thoại', order.recipient.phone),
            InfoRow('Địa chỉ', order.recipient.addressLine),
          ]),
          InfoCard('Gói hàng', [
            InfoRow('Mô tả', p.description),
            InfoRow('Trọng lượng', '${p.weightGrams} g'),
            InfoRow('Kích thước', '${n(p.lengthCm)} × ${n(p.widthCm)} × ${n(p.heightCm)} cm'),
          ]),
          if (order.history.isNotEmpty)
            InfoCard('Lịch sử', [
              for (final h in order.history.reversed)
                ListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.circle, size: 10, color: AppColors.primary),
                    title: Text(h.toLabel),
                    subtitle: Text([
                      formatTime(h.occurredAt),
                      if (h.reason != null && h.reason!.isNotEmpty) h.reason!
                    ].join('\n'))),
            ]),
        ]);
  }
}

class _ActionBar extends ConsumerWidget {
  const _ActionBar({required this.order, required this.busy, required this.controller});
  final OrderDetail order;
  final bool busy;
  final DispatcherDetailController controller;

  static const _inFlight = {'SCHEDULED', 'PREPARING', 'IN_TRANSIT', 'AWAITING_PICKUP'};

  Future<bool> _confirm(BuildContext context, String title, String body, String yes) async =>
      await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(title: Text(title), content: Text(body), actions: [
                TextButton(
                    key: const Key('confirm-no'),
                    onPressed: () => Navigator.of(ctx).pop(false),
                    child: const Text('Quay lại')),
                FilledButton(
                    key: const Key('confirm-yes'),
                    onPressed: () => Navigator.of(ctx).pop(true),
                    child: Text(yes)),
              ])) ??
      false;

  Future<void> _approve(BuildContext context) async {
    if (await _confirm(context, 'Duyệt đơn này?',
        'Đơn sẽ chuyển sang "Đã duyệt" và chờ được lên lịch giao.', 'Duyệt đơn')) {
      await controller.approve();
    }
  }

  Future<void> _reject(BuildContext context) async {
    final reason = await showModalBottomSheet<String>(
        context: context,
        isScrollControlled: true,
        builder: (_) => const ReasonSheet(
            title: 'Từ chối đơn',
            hint: 'Lý do từ chối (khách sẽ thấy)',
            confirmLabel: 'Từ chối đơn'));
    if (reason != null) await controller.reject(reason);
  }

  Future<void> _schedule(BuildContext context, WidgetRef ref, {required String title}) async {
    final stations = ref.read(stationsProvider).valueOrNull;
    final slot = await showModalBottomSheet<DateTime>(
        context: context,
        isScrollControlled: true,
        builder: (_) => SlotPickerSheet(
            title: title,
            stationId: order.pickupStationId,
            stationName: _stationName(stations, order.pickupStationId)));
    if (slot != null) await controller.schedule(slot);
  }

  Future<void> _return(BuildContext context) async {
    final reason = await showModalBottomSheet<String>(
        context: context,
        isScrollControlled: true,
        builder: (_) => const ReasonSheet(
            title: 'Hoàn hàng về trạm gửi',
            hint: 'Lý do hoàn hàng',
            confirmLabel: 'Hoàn hàng'));
    if (reason != null) await controller.startReturn(reason);
  }

  Future<void> _fail(BuildContext context) async {
    final choice = await showModalBottomSheet<FailureChoice>(
        context: context, isScrollControlled: true, builder: (_) => const FailureSheet());
    if (choice != null) await controller.fail(choice.code, note: choice.note);
  }

  Widget _busy(Widget label) => busy
      ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
      : label;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final children = <Widget>[];
    if (order.status == 'PENDING') {
      children.addAll([
        Expanded(
            child: OutlinedButton(
                key: const Key('dispatch-reject'),
                onPressed: busy ? null : () => _reject(context),
                child: const Text('Từ chối'))),
        const SizedBox(width: 12),
        Expanded(
            child: FilledButton(
                key: const Key('dispatch-approve'),
                onPressed: busy ? null : () => _approve(context),
                child: _busy(const Text('Duyệt đơn')))),
      ]);
    } else if (order.status == 'APPROVED') {
      children.add(Expanded(
          child: FilledButton.icon(
              key: const Key('dispatch-schedule'),
              onPressed: busy ? null : () => _schedule(context, ref, title: 'Chọn khung giờ giao'),
              icon: const Icon(Icons.event_available_outlined),
              label: _busy(const Text('Lên lịch giao')))));
    } else if (order.status == 'FAILED') {
      children.addAll([
        Expanded(
            child: OutlinedButton(
                key: const Key('dispatch-return'),
                onPressed: busy ? null : () => _return(context),
                child: const Text('Hoàn hàng'))),
        const SizedBox(width: 12),
        Expanded(
            child: FilledButton(
                key: const Key('dispatch-redeliver'),
                onPressed:
                    busy ? null : () => _schedule(context, ref, title: 'Chọn khung giờ giao lại'),
                child: _busy(const Text('Giao lại')))),
      ]);
    } else if (_inFlight.contains(order.status)) {
      children.add(Expanded(
          child: OutlinedButton.icon(
              key: const Key('dispatch-fail'),
              style: OutlinedButton.styleFrom(foregroundColor: AppColors.danger),
              onPressed: busy ? null : () => _fail(context),
              icon: const Icon(Icons.report_gmailerrorred_outlined),
              label: const Text('Báo giao thất bại'))));
    }
    if (children.isEmpty) return const SizedBox.shrink();
    return BottomActionBar(child: Row(children: children));
  }
}
