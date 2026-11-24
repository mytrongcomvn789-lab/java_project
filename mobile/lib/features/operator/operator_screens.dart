import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../auth/screens.dart' show signOut;
import '../auth/session.dart';
import '../orders/customer_order_models.dart' show OrderSummary, Station;
import '../orders/order_detail_controller.dart' show stationsProvider;
import '../orders/order_screens.dart' show StatusChip, formatTime;
import 'operator_controllers.dart';

String _stationName(List<Station>? stations, String id) {
  for (final s in stations ?? const <Station>[]) {
    if (s.id == id) return s.name;
  }
  return 'Trạm ${id.length >= 8 ? id.substring(0, 8) : id}';
}

/// Màn chính của nhân viên trạm: các trạm được phân công, đơn chờ nhận kiện và đơn chờ
/// khách nhận. Phạm vi đơn do server quyết định, không liệt kê mọi trạm.
class OperatorHomeScreen extends ConsumerWidget {
  const OperatorHomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionProvider).user!;
    final receipts = ref.watch(receiptListProvider);
    final pickups = ref.watch(pickupListProvider);
    String count(OperatorListState s) =>
        s.loading || s.error != null ? '' : ' (${s.total})';
    return DefaultTabController(
        length: 2,
        child: Scaffold(
            appBar: AppBar(
                title: const Text('Vận hành trạm'),
                actions: [
                  IconButton(
                      tooltip: 'Hồ sơ',
                      onPressed: () => context.go('/profile'),
                      icon: const Icon(Icons.account_circle_outlined)),
                  IconButton(
                      key: const Key('logout'),
                      tooltip: 'Đăng xuất',
                      onPressed: () => signOut(ref),
                      icon: const Icon(Icons.logout)),
                ],
                bottom: TabBar(tabs: [
                  Tab(
                      key: const Key('tab-receipts'),
                      text: 'Chờ nhận kiện${count(receipts)}'),
                  Tab(
                      key: const Key('tab-pickups'),
                      text: 'Chờ giao khách${count(pickups)}'),
                ])),
            body: Column(children: [
              _StationsBanner(
                  name: user.name.isEmpty ? user.email : user.name),
              Expanded(
                  child: TabBarView(children: [
                _PagedOrders(
                    key: const Key('list-receipts'),
                    state: receipts,
                    emptyText: 'Không có đơn nào đang chờ nhận kiện tại trạm của bạn.',
                    onRefresh: ref.read(receiptListProvider.notifier).reload,
                    onLoadMore: ref.read(receiptListProvider.notifier).loadMore,
                    onTap: (o) => context.push('/operator/receipts/${o.id}')),
                _PagedOrders(
                    key: const Key('list-pickups'),
                    state: pickups,
                    emptyText: 'Không có đơn nào đang chờ khách nhận tại trạm của bạn.',
                    onRefresh: ref.read(pickupListProvider.notifier).reload,
                    onLoadMore: () async {},
                    onTap: (o) => context.push('/operator/pickups/${o.id}')),
              ])),
            ])));
  }
}

class _StationsBanner extends ConsumerWidget {
  const _StationsBanner({required this.name});
  final String name;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stations = ref.watch(assignedStationsProvider);
    final scheme = Theme.of(context).colorScheme;
    return stations.when(
        loading: () => const SizedBox(height: 4, child: LinearProgressIndicator()),
        error: (e, _) => Container(
            key: const Key('stations-error'),
            width: double.infinity,
            color: scheme.errorContainer,
            padding: const EdgeInsets.all(8),
            child: Row(children: [
              const Expanded(
                  child: Text('Chưa tải được danh sách trạm của bạn.')),
              TextButton(
                  onPressed: () => ref.invalidate(assignedStationsProvider),
                  child: const Text('Thử lại')),
            ])),
        data: (list) => Container(
            key: const Key('stations-banner'),
            width: double.infinity,
            color: list.isEmpty ? scheme.tertiaryContainer : scheme.surfaceContainerHighest,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Text(list.isEmpty
                ? 'Tài khoản $name chưa được phân công trạm nào. Liên hệ quản lý để được phân công.'
                : 'Trạm của bạn: ${list.map((s) => s.name).join(' · ')}')));
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
      this.onTap});
  final OperatorListState state;
  final String emptyText;
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
              return _OrderCard(s.items[i], stations, widget.onTap);
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
  const _OrderCard(this.order, this.stations, this.onTap);
  final OrderSummary order;
  final List<Station>? stations;
  final void Function(OrderSummary)? onTap;

  @override
  Widget build(BuildContext context) {
    final weight = order.packageWeightGrams;
    return Card(
        child: ListTile(
            key: Key('order-${order.id}'),
            title: Text('Đơn ${order.shortCode}'),
            subtitle: Text([
              [
                if (order.packageDescription != null) order.packageDescription!,
                if (weight != null) '$weight g',
              ].join(' · '),
              '${_stationName(stations, order.pickupStationId)} → ${_stationName(stations, order.dropoffStationId)}',
              'Người nhận: ${order.recipientName}',
              formatTime(order.updatedAt ?? order.createdAt),
            ].where((e) => e.isNotEmpty).join('\n')),
            isThreeLine: true,
            trailing: StatusChip(order.status, order.statusLabel),
            onTap: onTap == null ? null : () => onTap!(order)));
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
    if (received && mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Đã nhận kiện.')));
      _backToList();
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
      if (!state.canReceive)
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
