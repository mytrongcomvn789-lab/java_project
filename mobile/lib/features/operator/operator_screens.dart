import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../auth/screens.dart' show signOut;
import '../auth/session.dart';
import '../orders/customer_order_models.dart' show OrderSummary, Station;
import '../orders/order_detail_controller.dart' show stationsProvider;
import '../orders/order_screens.dart' show OrderListCard, StatusChip, formatTime;
import '../../core/theme/app_theme.dart';
import '../../core/ui/app_widgets.dart';
import 'operator_controllers.dart';
import 'operator_models.dart' show AssignedStation, PickupCredential;
import 'return_controller.dart';
import 'sync_widgets.dart';

String _stationName(List<Station>? stations, String id) {
  for (final s in stations ?? const <Station>[]) {
    if (s.id == id) return s.name;
  }
  return 'Trạm ${id.length >= 8 ? id.substring(0, 8) : id}';
}

/// Màn chính của nhân viên trạm: header tối (trạm, kết nối, thống kê, sức chứa), ba tab đơn
/// (chờ nhận kiện, chờ giao khách, thất bại/hoàn về) và thanh điều hướng dưới có nút Quét QR.
/// Phạm vi đơn do server quyết định theo các trạm được phân công.
class OperatorHomeScreen extends ConsumerWidget {
  const OperatorHomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Giữ provider mã vừa quét sống khi màn chính còn mở (nó tự hủy khi không ai nghe).
    ref.watch(scannedCredentialProvider);
    return const DefaultTabController(length: 3, child: _OperatorScaffold());
  }
}

class _OperatorScaffold extends ConsumerWidget {
  const _OperatorScaffold();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final receipts = ref.watch(receiptListProvider);
    final pickups = ref.watch(pickupListProvider);
    final returns = ref.watch(returnListProvider);
    String count(OperatorListState s) =>
        s.loading || s.error != null ? '' : ' (${s.total})';
    return Scaffold(
        backgroundColor: AppColors.background,
        body: Column(children: [
          _OperatorHeader(receipts: receipts, pickups: pickups, returns: returns),
          const SyncBanner(),
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
                  labelStyle: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
                  tabs: [
                    Tab(
                        key: const Key('tab-receipts'),
                        height: 40,
                        text: 'Chờ nhận${count(receipts)}'),
                    Tab(
                        key: const Key('tab-pickups'),
                        height: 40,
                        text: 'Chờ giao${count(pickups)}'),
                    Tab(
                        key: const Key('tab-returns'),
                        height: 40,
                        text: 'Thất bại${count(returns)}'),
                  ])),
          Expanded(
              child: TabBarView(children: [
            _PagedOrders(
                key: const Key('list-receipts'),
                state: receipts,
                emptyText: 'Không có đơn nào đang chờ nhận kiện tại trạm của bạn.',
                actionLabel: 'Nhận kiện',
                onRefresh: ref.read(receiptListProvider.notifier).reload,
                onLoadMore: ref.read(receiptListProvider.notifier).loadMore,
                onTap: (o) => context.push('/operator/receipts/${o.id}')),
            _PagedOrders(
                key: const Key('list-pickups'),
                state: pickups,
                emptyText: 'Không có đơn nào đang chờ khách nhận tại trạm của bạn.',
                actionLabel: 'Xác nhận giao',
                onRefresh: ref.read(pickupListProvider.notifier).reload,
                onLoadMore: () async {},
                onTap: (o) => context.push('/operator/pickups/${o.id}')),
            _PagedOrders(
                key: const Key('list-returns'),
                state: returns,
                emptyText: 'Không có đơn nào đang hoàn về trạm gửi của bạn.',
                actionLabel: 'Nhận hàng hoàn',
                onRefresh: ref.read(returnListProvider.notifier).reload,
                onLoadMore: () async {},
                onTap: (o) => context.push('/operator/returns/${o.id}')),
          ])),
        ]),
        bottomNavigationBar: AppBottomNav(
            selectedIndex: 0,
            centerIndex: 1,
            items: const [
              NavItem(Icons.inbox_outlined, 'Đơn cần xử lý', key: Key('nav-orders')),
              NavItem(Icons.qr_code_scanner, 'Quét QR', key: Key('nav-scan')),
              NavItem(Icons.warehouse_outlined, 'Trạm', key: Key('nav-stations')),
              NavItem(Icons.person_outline, 'Hồ sơ', key: Key('nav-profile')),
            ],
            onTap: (i) => _onNav(context, ref, i)));
  }

  Future<void> _onNav(BuildContext context, WidgetRef ref, int i) async {
    switch (i) {
      case 1:
        // Mã khách đưa không cho biết đơn nào, nên quét trước rồi chọn đơn ở tab Chờ giao.
        final credential = await context.push<PickupCredential>('/operator/scan');
        if (credential == null || !context.mounted) return;
        ref.read(scannedCredentialProvider.notifier).state = credential;
        DefaultTabController.of(context).animateTo(1);
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content: Text('Đã quét mã nhận hàng. Chọn đơn trong "Chờ giao" để xác nhận.')));
      case 2:
        await showModalBottomSheet<void>(
            context: context,
            isScrollControlled: true,
            builder: (_) => const _StationsSheet());
      case 3:
        context.go('/profile');
    }
  }
}

class _OperatorHeader extends ConsumerWidget {
  const _OperatorHeader(
      {required this.receipts, required this.pickups, required this.returns});
  final OperatorListState receipts, pickups, returns;

  String _n(OperatorListState s) => s.loading || s.error != null ? '' : '${s.total}';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionProvider).user!;
    final stations = ref.watch(assignedStationsProvider);
    final list = stations.valueOrNull ?? const <AssignedStation>[];
    return Column(children: [
      Container(
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
                      Expanded(child: _stationSelector(context, ref, stations, list)),
                      _ConnectionChip(list),
                      IconButton(
                          key: const Key('logout'),
                          tooltip: 'Đăng xuất',
                          onPressed: () => signOut(ref),
                          icon: const Icon(Icons.logout, color: Colors.white)),
                    ]),
                    const SizedBox(height: 16),
                    Padding(
                        padding: const EdgeInsets.only(right: 12),
                        child: Column(children: [
                          Row(children: [
                            Expanded(child: StatTile(value: _n(receipts), label: 'Chờ nhận')),
                            const SizedBox(width: 10),
                            Expanded(child: StatTile(value: _n(pickups), label: 'Chờ giao')),
                            const SizedBox(width: 10),
                            Expanded(child: StatTile(value: _n(returns), label: 'Thất bại')),
                          ]),
                          const SizedBox(height: 16),
                          StationCapacityBar(
                              used: list.fold(0, (a, s) => a + s.slotUsed),
                              capacity: list.fold(0, (a, s) => a + s.capacity)),
                        ])),
                  ])))),
      if (stations.hasValue && list.isEmpty)
        Container(
            key: const Key('stations-banner'),
            width: double.infinity,
            margin: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
                color: StatusPalette.pending.background,
                borderRadius: BorderRadius.circular(14)),
            child: Text(
                'Tài khoản ${user.name.isEmpty ? user.email : user.name} chưa được phân công trạm nào. Liên hệ quản lý để được phân công.',
                style: TextStyle(color: StatusPalette.pending.foreground))),
    ]);
  }

  Widget _stationSelector(BuildContext context, WidgetRef ref,
      AsyncValue<List<AssignedStation>> stations, List<AssignedStation> list) {
    const label = Text('Trạm làm việc',
        style: TextStyle(color: Colors.white70, fontSize: 12, fontWeight: FontWeight.w500));
    if (stations.hasError && !stations.hasValue) {
      return Column(
          key: const Key('stations-error'),
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            label,
            Wrap(crossAxisAlignment: WrapCrossAlignment.center, children: [
              const Text('Chưa tải được trạm',
                  style: TextStyle(
                      color: Colors.white, fontSize: 16, fontWeight: FontWeight.w700)),
              TextButton(
                  onPressed: () => ref.invalidate(assignedStationsProvider),
                  style: TextButton.styleFrom(foregroundColor: AppColors.amber),
                  child: const Text('Thử lại')),
            ]),
          ]);
    }
    final title = stations.isLoading && !stations.hasValue
        ? 'Đang tải trạm…'
        : list.isEmpty
            ? 'Chưa có trạm'
            : list.length == 1
                ? list.first.name
                : '${list.length} trạm';
    return InkWell(
        key: const Key('station-selector'),
        borderRadius: BorderRadius.circular(12),
        onTap: list.isEmpty
            ? null
            : () => showModalBottomSheet<void>(
                context: context,
                isScrollControlled: true,
                builder: (_) => const _StationsSheet()),
        child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              label,
              Row(children: [
                const Icon(Icons.warehouse_outlined, color: AppColors.amber, size: 20),
                const SizedBox(width: 8),
                Flexible(
                    child: Text(title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            color: Colors.white, fontSize: 20, fontWeight: FontWeight.w800))),
                if (list.isNotEmpty)
                  const Icon(Icons.keyboard_arrow_down, color: Colors.white70),
              ]),
            ])));
  }
}

/// Tình trạng kết nối của trạm do server báo. Backend chưa có nguồn heartbeat nên thường là
/// "Chưa có dữ liệu"; app không được giả là Online.
class _ConnectionChip extends StatelessWidget {
  const _ConnectionChip(this.stations);
  final List<AssignedStation> stations;

  @override
  Widget build(BuildContext context) {
    final states = [for (final s in stations) s.connection];
    final (text, color) = states.isEmpty
        ? ('Chưa có dữ liệu', Colors.white54)
        : states.contains('OFFLINE')
            ? ('Offline', const Color(0xffff8a8f))
            : states.every((s) => s == 'ONLINE')
                ? ('Online', AppColors.tealBright)
                : ('Chưa có dữ liệu', Colors.white54);
    return Semantics(
        label: 'Kết nối trạm: $text',
        excludeSemantics: true,
        child: Container(
            key: const Key('connection-chip'),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(AppRadii.pill)),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
              const SizedBox(width: 6),
              Text(text,
                  style: const TextStyle(
                      color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600)),
            ])));
  }
}

/// Danh sách các trạm được phân công: địa chỉ, tình trạng và sức chứa khung giờ hiện tại.
class _StationsSheet extends ConsumerWidget {
  const _StationsSheet();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stations = ref.watch(assignedStationsProvider).valueOrNull ?? const [];
    return SafeArea(
        child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.7),
            child: ListView(shrinkWrap: true, padding: const EdgeInsets.all(20), children: [
              Text('Trạm của bạn',
                  style: Theme.of(context)
                      .textTheme
                      .titleLarge
                      ?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 12),
              for (final s in stations)
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
            ])));
  }
}

/// Danh sách đơn có kéo để làm mới, cuộn gần cuối thì tải thêm, màn lỗi và trạng thái rỗng.
class _PagedOrders extends ConsumerStatefulWidget {
  const _PagedOrders(
      {super.key,
      required this.state,
      required this.emptyText,
      required this.onRefresh,
      required this.onLoadMore,
      this.onTap,
      this.actionLabel});
  final OperatorListState state;
  final String emptyText;
  final String? actionLabel;
  final Future<void> Function() onRefresh;
  final Future<void> Function() onLoadMore;
  final void Function(OrderSummary)? onTap;

  @override
  ConsumerState<_PagedOrders> createState() => _PagedOrdersState();
}

class _PagedOrdersState extends ConsumerState<_PagedOrders> {
  final _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      if (_scroll.hasClients && _scroll.position.extentAfter < 300) {
        widget.onLoadMore();
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
    final s = widget.state;
    final stations = ref.watch(stationsProvider).valueOrNull;
    Widget body;
    if (s.loading) {
      body = const Center(child: CircularProgressIndicator());
    } else if (s.error != null) {
      body = ListView(children: [
        Padding(
            padding: const EdgeInsets.all(24),
            child: Column(children: [
              Text(s.error!.message, key: const Key('list-error')),
              const SizedBox(height: 12),
              FilledButton(
                  onPressed: widget.onRefresh, child: const Text('Thử lại')),
            ]))
      ]);
    } else if (s.items.isEmpty) {
      body = ListView(children: [
        Padding(
            padding: const EdgeInsets.all(32),
            child: Text(widget.emptyText,
                key: const Key('list-empty'), textAlign: TextAlign.center))
      ]);
    } else {
      body = ListView.separated(
          controller: _scroll,
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(12),
          itemCount: s.items.length + (s.hasMore ? 1 : 0),
          separatorBuilder: (_, __) => const SizedBox(height: 8),
          itemBuilder: (context, i) {
            if (i < s.items.length) {
              return _OrderCard(s.items[i], stations, widget.onTap, widget.actionLabel);
            }
            if (s.moreError != null) {
              return Center(
                  child: TextButton(
                      key: const Key('load-more-retry'),
                      onPressed: widget.onLoadMore,
                      child: const Text('Tải thêm không được. Thử lại')));
            }
            WidgetsBinding.instance
                .addPostFrameCallback((_) => widget.onLoadMore());
            return const Padding(
                padding: EdgeInsets.all(16),
                child: Center(child: CircularProgressIndicator()));
          });
    }
    return Column(children: [
      if (s.moreError != null && s.items.isNotEmpty && !s.loadingMore)
        Container(
            key: const Key('list-error-banner'),
            width: double.infinity,
            color: Theme.of(context).colorScheme.errorContainer,
            padding: const EdgeInsets.all(8),
            child: Text('Chưa cập nhật được: ${s.moreError!.message}')),
      Expanded(child: RefreshIndicator(onRefresh: widget.onRefresh, child: body)),
    ]);
  }
}

class _OrderCard extends StatelessWidget {
  const _OrderCard(this.order, this.stations, this.onTap, this.actionLabel);
  final OrderSummary order;
  final List<Station>? stations;
  final void Function(OrderSummary)? onTap;
  final String? actionLabel;

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
        onTap: onTap == null ? null : () => onTap!(order));
  }
}

/// Chi tiết đơn chờ nhận kiện: kiểm tra kiện thực tế rồi nhận. Route `/operator/receipts/:id`.
class ReceiptDetailScreen extends ConsumerStatefulWidget {
  const ReceiptDetailScreen({super.key, required this.orderId});
  final String orderId;
  @override
  ConsumerState<ReceiptDetailScreen> createState() => _ReceiptDetailScreenState();
}

class _ReceiptDetailScreenState extends ConsumerState<ReceiptDetailScreen> {
  final _note = TextEditingController();
  bool _checked = false;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  /// Về danh sách; nếu màn này được mở thẳng (không có màn trước) thì chuyển tới `/operator`.
  void _backToList() {
    if (context.canPop()) {
      context.pop();
    } else {
      context.go('/operator');
    }
  }

  Future<void> _confirm() async {
    final controller = ref.read(receiptDetailProvider(widget.orderId).notifier);
    final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
                title: const Text('Xác nhận nhận kiện?'),
                content: const Text(
                    'Đơn sẽ chuyển sang "Đang chuẩn bị". Hãy chắc chắn bạn đã đối chiếu kiện thực tế.'),
                actions: [
                  TextButton(
                      key: const Key('receive-dismiss'),
                      onPressed: () => Navigator.of(ctx).pop(false),
                      child: const Text('Quay lại')),
                  FilledButton(
                      key: const Key('receive-confirm'),
                      onPressed: () => Navigator.of(ctx).pop(true),
                      child: const Text('Nhận kiện')),
                ]));
    if (ok != true) return;
    final received = await controller.receive(_note.text);
    if (!mounted) return;
    if (received) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Đã nhận kiện.')));
      _backToList();
    } else if (ref.read(receiptDetailProvider(widget.orderId)).queued) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Chưa có mạng: đã lưu, sẽ tự gửi khi có mạng.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(receiptDetailProvider(widget.orderId));
    final controller = ref.read(receiptDetailProvider(widget.orderId).notifier);
    final stations = ref.watch(stationsProvider).valueOrNull;
    final ctx = state.context;
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
        appBar: AppBar(
            title: Text(ctx == null ? 'Nhận kiện' : 'Đơn ${ctx.order.shortCode}'),
            actions: [
              IconButton(
                  tooltip: 'Tải lại',
                  onPressed: () => controller.reload(),
                  icon: const Icon(Icons.refresh))
            ]),
        body: Column(children: [
          if (state.notice != null)
            _Banner(
                key: const Key('receipt-notice'),
                text: state.notice!,
                color: scheme.tertiaryContainer,
                onClose: controller.dismissNotice),
          if (state.actionError != null)
            _Banner(
                key: const Key('receipt-action-error'),
                text: state.actionError!.message,
                color: scheme.errorContainer,
                onClose: controller.dismissNotice),
          Expanded(child: _body(context, state, controller, stations)),
        ]));
  }

  Widget _body(BuildContext context, ReceiptDetailState state,
      ReceiptDetailController controller, List<Station>? stations) {
    final ctx = state.context;
    if (ctx == null) {
      if (state.error != null) {
        return Center(
            child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Text(
                      state.gone
                          ? 'Đơn không còn trong phạm vi của bạn hoặc không tồn tại.'
                          : state.error!.message,
                      key: const Key('receipt-error'),
                      textAlign: TextAlign.center),
                  const SizedBox(height: 12),
                  if (state.gone)
                    FilledButton(
                        onPressed: _backToList,
                        child: const Text('Quay lại danh sách'))
                  else
                    FilledButton(
                        onPressed: () => controller.reload(),
                        child: const Text('Thử lại')),
                ])));
      }
      return const Center(child: CircularProgressIndicator());
    }
    final theme = Theme.of(context);
    final o = ctx.order;
    final p = o.package;
    return ListView(padding: const EdgeInsets.all(16), children: [
      Row(children: [
        Expanded(
            child: Text(o.statusLabel,
                key: const Key('receipt-status'),
                style: theme.textTheme.headlineSmall)),
        StatusChip(o.status, o.statusLabel),
      ]),
      const SizedBox(height: 16),
      _Section('Lộ trình', [
        _Row('Trạm gửi', _stationName(stations, o.pickupStationId)),
        _Row('Trạm nhận', _stationName(stations, o.dropoffStationId)),
      ]),
      _Section('Người gửi và người nhận', [
        _Row('Người gửi', ctx.senderName),
        _Row('Người nhận', o.recipient.name),
        _Row('Điện thoại', o.recipient.phone),
        _Row('Địa chỉ', o.recipient.addressLine),
      ]),
      _Section('Kiện hàng', [
        _Row('Mô tả', p.description),
        _Row('Khối lượng', '${p.weightGrams} g'),
        _Row('Kích thước',
            '${_n(p.lengthCm)} × ${_n(p.widthCm)} × ${_n(p.heightCm)} cm'),
        _Row('Ảnh', p.photoFileIds.isEmpty ? 'Không có' : '${p.photoFileIds.length} ảnh'),
        _Row('Mã kiện', ctx.packageId.substring(0, ctx.packageId.length >= 8 ? 8 : ctx.packageId.length).toUpperCase()),
      ]),
      _Section('Lần giao', [
        _Row('Lần', '${ctx.attempt.attemptNo}'),
        _Row('Khung giờ',
            '${formatTime(ctx.attempt.slotStart)} → ${formatTime(ctx.attempt.slotEnd)}'),
      ]),
      if (state.queued)
        const QueuedNotice(
            key: Key('receipt-queued'),
            text:
                'Chưa có mạng nên yêu cầu nhận kiện đã được lưu trên máy. Đơn CHƯA được nhận trên hệ thống; app sẽ tự gửi khi có mạng.')
      else if (!state.canReceive)
        Container(
            key: const Key('receipt-not-pending'),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(8)),
            child: Text(state.gone
                ? 'Đơn không còn trong phạm vi của bạn.'
                : 'Đơn không còn chờ nhận kiện (trạng thái: ${o.statusLabel}).'))
      else ...[
        TextField(
            key: const Key('receipt-note'),
            controller: _note,
            maxLength: 500,
            maxLines: 2,
            decoration: const InputDecoration(
                labelText: 'Ghi chú (tùy chọn)', border: OutlineInputBorder())),
        CheckboxListTile(
            key: const Key('receipt-checked'),
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            title: const Text('Tôi đã đối chiếu mã kiện và kiểm tra bao bì thực tế'),
            value: _checked,
            onChanged: state.receiving
                ? null
                : (v) => setState(() => _checked = v ?? false)),
        const SizedBox(height: 8),
        FilledButton(
            key: const Key('receive-button'),
            // Vô hiệu hóa khi đang gửi hoặc chưa tích kiểm tra: chặn bấm đúp.
            onPressed: state.receiving || !_checked ? null : _confirm,
            child: state.receiving
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Nhận kiện')),
      ],
    ]);
  }
}

/// Chi tiết đơn đang hoàn về: xem các lần giao thất bại và xác nhận đã nhận hàng hoàn tại trạm
/// gửi. Route `/operator/returns/:id`.
class ReturnDetailScreen extends ConsumerStatefulWidget {
  const ReturnDetailScreen({super.key, required this.orderId});
  final String orderId;
  @override
  ConsumerState<ReturnDetailScreen> createState() => _ReturnDetailScreenState();
}

class _ReturnDetailScreenState extends ConsumerState<ReturnDetailScreen> {
  final _note = TextEditingController();
  bool _checked = false;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  void _backToList() {
    if (context.canPop()) {
      context.pop();
    } else {
      context.go('/operator');
    }
  }

  Future<void> _complete() async {
    final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
                title: const Text('Xác nhận đã nhận hàng hoàn?'),
                content: const Text(
                    'Đơn sẽ chuyển sang "Đã hoàn hàng" và không hoàn tác được. Hãy chắc chắn kiện đã về tới trạm gửi.'),
                actions: [
                  TextButton(
                      key: const Key('return-dismiss'),
                      onPressed: () => Navigator.of(ctx).pop(false),
                      child: const Text('Quay lại')),
                  FilledButton(
                      key: const Key('return-confirm'),
                      onPressed: () => Navigator.of(ctx).pop(true),
                      child: const Text('Đã nhận hàng hoàn')),
                ]));
    if (ok != true) return;
    final done = await ref
        .read(returnDetailProvider(widget.orderId).notifier)
        .complete(_note.text);
    if (!mounted) return;
    if (done) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Đã xác nhận nhận hàng hoàn.')));
      _backToList();
    } else if (ref.read(returnDetailProvider(widget.orderId)).queued) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Chưa có mạng: đã lưu, sẽ tự gửi khi có mạng.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(returnDetailProvider(widget.orderId));
    final controller = ref.read(returnDetailProvider(widget.orderId).notifier);
    final stations = ref.watch(stationsProvider).valueOrNull;
    final order = state.order;
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
        appBar: AppBar(
            title: Text(order == null ? 'Nhận hàng hoàn' : 'Đơn ${order.shortCode}'),
            actions: [
              IconButton(
                  tooltip: 'Tải lại',
                  onPressed: () => controller.reload(),
                  icon: const Icon(Icons.refresh))
            ]),
        body: Column(children: [
          if (state.notice != null)
            _Banner(
                key: const Key('return-notice'),
                text: state.notice!,
                color: scheme.tertiaryContainer,
                onClose: controller.dismissNotice),
          if (state.actionError != null)
            _Banner(
                key: const Key('return-action-error'),
                text: state.actionError!.message,
                color: scheme.errorContainer,
                onClose: controller.dismissNotice),
          Expanded(child: _body(context, state, controller, stations)),
        ]));
  }

  Widget _body(BuildContext context, ReturnDetailState state,
      ReturnDetailController controller, List<Station>? stations) {
    final o = state.order;
    if (o == null) {
      if (state.error != null) {
        return Center(
            child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Text(
                      state.gone
                          ? 'Đơn không còn trong phạm vi của bạn hoặc không tồn tại.'
                          : state.error!.message,
                      key: const Key('return-error'),
                      textAlign: TextAlign.center),
                  const SizedBox(height: 12),
                  FilledButton(
                      onPressed:
                          state.gone ? _backToList : () => controller.reload(),
                      child: Text(state.gone ? 'Quay lại danh sách' : 'Thử lại')),
                ])));
      }
      return const Center(child: CircularProgressIndicator());
    }
    final theme = Theme.of(context);
    return ListView(padding: const EdgeInsets.all(16), children: [
      Row(children: [
        Expanded(
            child: Text(o.statusLabel,
                key: const Key('return-status'),
                style: theme.textTheme.headlineSmall)),
        StatusChip(o.status, o.statusLabel),
      ]),
      const SizedBox(height: 16),
      _Section('Lộ trình', [
        _Row('Trạm gửi (nhận lại)', _stationName(stations, o.pickupStationId)),
        _Row('Trạm nhận', _stationName(stations, o.dropoffStationId)),
      ]),
      _Section('Kiện hàng', [
        _Row('Mô tả', o.package.description),
        _Row('Khối lượng', '${o.package.weightGrams} g'),
        _Row('Người nhận', o.recipient.name),
      ]),
      if (state.failures.isNotEmpty)
        _Section('Lý do hoàn hàng', [
          for (final h in state.failures)
            _Row(h.toLabel,
                '${h.reason ?? 'Không ghi lý do'}\n${formatTime(h.occurredAt)}'),
        ]),
      if (state.done)
        const QueuedNotice(
            key: Key('return-done'),
            text: 'Đã xác nhận nhận hàng hoàn.')
      else if (state.queued)
        const QueuedNotice(
            key: Key('return-queued'),
            text:
                'Chưa có mạng nên xác nhận đã được lưu trên máy. Đơn CHƯA được hoàn tất trên hệ thống; app sẽ tự gửi khi có mạng.')
      else if (!state.canComplete)
        Container(
            key: const Key('return-not-pending'),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(8)),
            child: Text(state.gone
                ? 'Đơn không còn trong phạm vi của bạn.'
                : 'Đơn không còn chờ nhận hàng hoàn (trạng thái: ${o.statusLabel}).'))
      else ...[
        TextField(
            key: const Key('return-note'),
            controller: _note,
            maxLength: 500,
            maxLines: 2,
            decoration: const InputDecoration(
                labelText: 'Ghi chú (tùy chọn)', border: OutlineInputBorder())),
        CheckboxListTile(
            key: const Key('return-checked'),
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            title: const Text('Tôi đã nhận kiện hoàn về và kiểm tra tình trạng kiện'),
            value: _checked,
            onChanged: state.completing
                ? null
                : (v) => setState(() => _checked = v ?? false)),
        const SizedBox(height: 8),
        FilledButton(
            key: const Key('return-button'),
            onPressed: state.completing || !_checked ? null : _complete,
            child: state.completing
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Đã nhận hàng hoàn')),
      ],
    ]);
  }
}

String _n(double v) => v == v.roundToDouble() ? v.toInt().toString() : v.toString();

class _Banner extends StatelessWidget {
  const _Banner(
      {super.key, required this.text, required this.color, required this.onClose});
  final String text;
  final Color color;
  final VoidCallback onClose;
  @override
  Widget build(BuildContext context) => Container(
      width: double.infinity,
      color: color,
      padding: const EdgeInsets.only(left: 12, top: 4, bottom: 4),
      child: Row(children: [
        Expanded(child: Text(text)),
        IconButton(
            tooltip: 'Đóng', onPressed: onClose, icon: const Icon(Icons.close)),
      ]));
}

class _Section extends StatelessWidget {
  const _Section(this.title, this.rows);
  final String title;
  final List<Widget> rows;
  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        ...rows,
      ]));
}

class _Row extends StatelessWidget {
  const _Row(this.label, this.value);
  final String label, value;
  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
            width: 100,
            child: Text(label, style: Theme.of(context).textTheme.bodySmall)),
        Expanded(child: Text(value)),
      ]));
}
