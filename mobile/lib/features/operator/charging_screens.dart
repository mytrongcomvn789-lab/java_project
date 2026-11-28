import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_failure.dart';
import '../../core/theme/app_theme.dart';
import '../../core/ui/app_widgets.dart';
import '../../core/ui/async_state_view.dart';
import 'charging_api.dart';
import 'charging_controllers.dart';
import 'operator_controllers.dart';
import 'operator_models.dart' show AssignedStation;

String _two(int n) => n.toString().padLeft(2, '0');

/// "HH:mm" theo giờ máy.
String hm(DateTime? t) => t == null ? '—' : '${_two(t.hour)}:${_two(t.minute)}';

/// Thời lượng dễ đọc từ số phút: "45 phút", "1 giờ 5 phút".
String minutesLabel(int? m) {
  if (m == null) return '—';
  if (m < 60) return '$m phút';
  final h = m ~/ 60, r = m % 60;
  return r == 0 ? '$h giờ' : '$h giờ $r phút';
}

String chargerStateLabel(ChargerState s) => switch (s) {
      ChargerState.empty => 'Trống',
      ChargerState.charging => 'Đang sạc',
      ChargerState.full => 'Đã đầy',
      ChargerState.maintenance => 'Bảo trì',
      ChargerState.unknown => 'Không rõ',
    };

/// Màu theo bảng trạng thái dùng chung (đều đạt tương phản ≥ 4.5:1).
ChipColors chargerColors(ChargerState s) => switch (s) {
      ChargerState.empty => StatusPalette.closed,
      ChargerState.charging => StatusPalette.pending,
      ChargerState.full => StatusPalette.done,
      ChargerState.maintenance => StatusPalette.failed,
      ChargerState.unknown => StatusPalette.closed,
    };

String droneStatusLabel(String s) => switch (s) {
      'IDLE' => 'Đang đỗ',
      'CHARGING' => 'Đang sạc',
      'IN_FLIGHT' => 'Đang bay',
      'MAINTENANCE' => 'Bảo trì',
      _ => s,
    };

String dispatchStageLabel(String s) => switch (s) {
      'PLANNED' => 'Xem trước, chưa phân công',
      'ASSIGNED' => 'Đã phân công drone',
      'IN_FLIGHT' => 'Drone đang bay',
      'COMPLETED' => 'Đã hoàn tất',
      'NO_DRONE' => 'Chưa có drone phù hợp',
      'NO_CHARGER' => 'Trạm đích chưa có chỗ sạc',
      _ => 'Chưa tới bước điều phối',
    };

const _simNote = 'Số liệu sạc là giả lập (chưa có thiết bị thật).';

// ---------------------------------------------------------------------------------------------------
// Danh sách trạm

/// Trạm của nhân viên: tình trạng kết nối, sức chứa khung giờ và lối vào chi tiết sạc. Có lối tắt tới
/// bảng điều phối drone.
class StationsScreen extends ConsumerWidget {
  const StationsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stations = ref.watch(assignedStationsProvider);
    Future<void> refresh() async {
      ref.invalidate(assignedStationsProvider);
      try {
        await ref.read(assignedStationsProvider.future);
      } catch (_) {}
    }

    return Scaffold(
        appBar: AppBar(
            title: const Text('Trạm của tôi'),
            leading: IconButton(
                tooltip: 'Quay lại',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => context.canPop() ? context.pop() : context.go('/operator')),
            actions: [
              TextButton.icon(
                  key: const Key('open-fleet'),
                  onPressed: () => context.push('/operator/fleet'),
                  icon: const Icon(Icons.flight_outlined, size: 18),
                  label: const Text('Đội drone')),
            ]),
        body: RefreshIndicator(
            onRefresh: refresh,
            child: stations.when(
                loading: () => const AsyncStateView(loading: true, child: SizedBox.shrink()),
                error: (e, _) => AsyncStateView(
                    error: toApiFailure(e), onRetry: refresh, child: const SizedBox.shrink()),
                data: (list) => AsyncStateView(
                    isEmpty: list.isEmpty,
                    emptyIcon: Icons.warehouse_outlined,
                    emptyTitle: 'Bạn chưa được phân công trạm nào',
                    emptyHint: 'Liên hệ quản lý để được phân công.',
                    child: ListView(
                        physics: const AlwaysScrollableScrollPhysics(),
                        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                        children: [for (final s in list) _StationCard(s)])))));
  }
}

class _StationCard extends StatelessWidget {
  const _StationCard(this.s);
  final AssignedStation s;

  @override
  Widget build(BuildContext context) {
    final (conn, connColor) = switch (s.connection) {
      'ONLINE' => ('Online', StatusPalette.done),
      'OFFLINE' => ('Offline', StatusPalette.failed),
      _ => ('Chưa có dữ liệu', StatusPalette.closed),
    };
    return Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: DecoratedBox(
            decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(AppRadii.card), boxShadow: cardShadow),
            child: Material(
                key: Key('station-${s.id}'),
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(AppRadii.card),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                    onTap: () => context.push('/operator/stations/${s.id}'),
                    child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Row(children: [
                            Expanded(
                                child: Text(s.name,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800))),
                            const SizedBox(width: 8),
                            Flexible(child: _Pill(conn, connColor)),
                          ]),
                          if ((s.address ?? '').isNotEmpty)
                            Padding(
                                padding: const EdgeInsets.only(top: 2, bottom: 8),
                                child: Text(s.address!, style: const TextStyle(color: AppColors.textMuted))),
                          StationCapacityBar(
                              used: s.slotUsed, capacity: s.capacity, onDark: false, label: 'Lịch giao khung hiện tại'),
                          const SizedBox(height: 10),
                          const Row(children: [
                            Icon(Icons.bolt_outlined, size: 18, color: AppColors.primary),
                            SizedBox(width: 6),
                            Expanded(
                                child: Text('Xem thiết bị sạc và drone',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(color: AppColors.primary, fontWeight: FontWeight.w700))),
                            Icon(Icons.chevron_right, color: AppColors.textMuted),
                          ]),
                        ]))))));
  }
}

class _Pill extends StatelessWidget {
  const _Pill(this.text, this.colors);
  final String text;
  final ChipColors colors;
  @override
  Widget build(BuildContext context) => Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: colors.background, borderRadius: BorderRadius.circular(AppRadii.pill)),
      child: Text(text,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(color: colors.foreground, fontSize: 12, fontWeight: FontWeight.w700)));
}

// ---------------------------------------------------------------------------------------------------
// Chi tiết trạm: thiết bị sạc và drone

class StationChargersScreen extends ConsumerWidget {
  const StationChargersScreen({super.key, required this.stationId});
  final String stationId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = ref.watch(stationChargersProvider(stationId));
    Future<void> refresh() async {
      ref.invalidate(stationChargersProvider(stationId));
      try {
        await ref.read(stationChargersProvider(stationId).future);
      } catch (_) {}
    }

    return Scaffold(
        appBar: AppBar(
            title: Text(view.valueOrNull?.stationName ?? 'Thiết bị sạc'),
            leading: IconButton(
                tooltip: 'Quay lại',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => context.canPop() ? context.pop() : context.go('/operator/stations')),
            actions: [IconButton(tooltip: 'Tải lại', onPressed: refresh, icon: const Icon(Icons.refresh))]),
        body: RefreshIndicator(
            onRefresh: refresh,
            child: view.when(
                loading: () => const AsyncStateView(loading: true, child: SizedBox.shrink()),
                error: (e, _) => AsyncStateView(
                    error: toApiFailure(e), onRetry: refresh, child: const SizedBox.shrink()),
                data: (d) => _Body(stationId: stationId, data: d))));
  }
}

class _Body extends ConsumerWidget {
  const _Body({required this.stationId, required this.data});
  final String stationId;
  final StationChargers data;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = data.summary;
    return ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        children: [
          Wrap(spacing: 8, runSpacing: 8, children: [
            _Count(const Key('count-empty'), 'Trống', s.empty, chargerColors(ChargerState.empty)),
            _Count(const Key('count-charging'), 'Đang sạc', s.charging, chargerColors(ChargerState.charging)),
            _Count(const Key('count-full'), 'Đã đầy', s.full, chargerColors(ChargerState.full)),
            _Count(const Key('count-maintenance'), 'Bảo trì', s.maintenance, chargerColors(ChargerState.maintenance)),
          ]),
          const SizedBox(height: 8),
          Text('${s.online}/${s.total} thiết bị đang gửi tín hiệu',
              key: const Key('online-count'), style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
          if (data.simulated)
            const Padding(
                padding: EdgeInsets.only(top: 2),
                child: Text(_simNote,
                    key: Key('sim-note'), style: TextStyle(color: AppColors.textMuted, fontSize: 12))),
          const SizedBox(height: 12),
          if (data.nextSlotAt != null)
            MessageBanner(
                key: const Key('next-slot'),
                text: 'Hết chỗ nhận drone. Dự kiến có chỗ lúc ${hm(data.nextSlotAt)}.',
                color: StatusPalette.pending.background,
                onClose: () {}),
          InfoCard('Thiết bị sạc', [
            LayoutBuilder(builder: (context, c) {
              final width = (c.maxWidth - 24) / 3;
              return Wrap(spacing: 12, runSpacing: 12, children: [
                for (final ch in data.chargers)
                  SizedBox(
                      width: width,
                      child: _ChargerTile(
                          charger: ch,
                          onTap: () => _showCharger(context, ref, ch, data.stationId))),
              ]);
            }),
          ]),
          InfoCard('Drone đang đỗ (chưa cắm sạc)', [
            if (data.idleDrones.isEmpty)
              const Text('Không có drone nào đang đỗ.',
                  key: Key('no-idle'), style: TextStyle(color: AppColors.textMuted))
            else
              for (final d in data.idleDrones)
                ListTile(
                    key: Key('idle-${d.code}'),
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.flight_outlined, color: AppColors.primary),
                    title: Text(d.code, style: const TextStyle(fontWeight: FontWeight.w700)),
                    trailing: Text('${d.batteryPercent}%',
                        style: const TextStyle(fontWeight: FontWeight.w800))),
          ]),
        ]);
  }

  void _showCharger(BuildContext context, WidgetRef ref, Charger c, String stationId) {
    showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        builder: (_) => _ChargerSheet(stationId: stationId, chargerId: c.id));
  }
}

class _Count extends StatelessWidget {
  const _Count(this.keyValue, this.label, this.n, this.colors);
  final Key keyValue;
  final String label;
  final int n;
  final ChipColors colors;
  @override
  Widget build(BuildContext context) => Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(color: colors.background, borderRadius: BorderRadius.circular(AppRadii.button)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Text('$n',
            key: keyValue, style: TextStyle(color: colors.foreground, fontSize: 18, fontWeight: FontWeight.w800)),
        const SizedBox(width: 6),
        Text(label, style: TextStyle(color: colors.foreground, fontSize: 13, fontWeight: FontWeight.w600)),
      ]));
}

class _ChargerTile extends StatelessWidget {
  const _ChargerTile({required this.charger, required this.onTap});
  final Charger charger;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final c = chargerColors(charger.state);
    final pct = charger.batteryPercent;
    return Semantics(
        button: true,
        label: 'Thiết bị ${charger.code}, ${chargerStateLabel(charger.state)}'
            '${pct == null ? '' : ', pin $pct%'}${charger.online ? '' : ', mất tín hiệu'}',
        excludeSemantics: true,
        child: InkWell(
            key: Key('charger-${charger.code}'),
            borderRadius: BorderRadius.circular(AppRadii.button),
            onTap: onTap,
            child: Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                    color: c.background, borderRadius: BorderRadius.circular(AppRadii.button)),
                child: Column(children: [
                  Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                    Text(charger.code,
                        style: TextStyle(color: c.foreground, fontWeight: FontWeight.w800, fontSize: 13)),
                    Icon(charger.online ? Icons.wifi : Icons.wifi_off, size: 14, color: c.foreground),
                  ]),
                  const SizedBox(height: 6),
                  Icon(
                      switch (charger.state) {
                        ChargerState.maintenance => Icons.build_outlined,
                        ChargerState.empty => Icons.power_outlined,
                        _ => Icons.battery_charging_full,
                      },
                      color: c.foreground),
                  const SizedBox(height: 4),
                  Text(pct == null ? chargerStateLabel(charger.state) : '$pct%',
                      style: TextStyle(color: c.foreground, fontWeight: FontWeight.w800)),
                  Text(charger.drone?.code ?? '—', style: TextStyle(color: c.foreground, fontSize: 11)),
                ]))));
  }
}

/// Chi tiết một thiết bị sạc cùng hai thao tác: cho drone rời (chỉ khi đầy) và bảo trì. Luôn đọc từ dữ liệu
/// mới nhất của trạm nên sau thao tác bảng tự cập nhật.
class _ChargerSheet extends ConsumerWidget {
  const _ChargerSheet({required this.stationId, required this.chargerId});
  final String stationId, chargerId;

  Future<void> _act(BuildContext context, WidgetRef ref, Future<ChargingActionResult> Function() run) async {
    final r = await run();
    if (!context.mounted || r.message.isEmpty) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(r.message)));
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final data = ref.watch(stationChargersProvider(stationId)).valueOrNull;
    final busy = ref.watch(chargingActionsProvider);
    final charger = data?.chargers.where((c) => c.id == chargerId).firstOrNull;
    if (charger == null) {
      return const SafeArea(
          child: Padding(padding: EdgeInsets.all(24), child: Text('Không còn thiết bị này.')));
    }
    final actions = ref.read(chargingActionsProvider.notifier);
    final c = chargerColors(charger.state);
    final working = busy.contains('release-$chargerId') || busy.contains('maint-$chargerId');
    return SafeArea(
        child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(
                    child: Text('Thiết bị ${charger.code}',
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800))),
                _Pill(chargerStateLabel(charger.state), c),
              ]),
              const SizedBox(height: 12),
              InfoRow('Kết nối', charger.online ? 'Đang gửi tín hiệu' : 'Mất tín hiệu'),
              InfoRow('Tín hiệu cuối', hm(charger.lastHeartbeatAt)),
              if (charger.drone != null) ...[
                InfoRow('Drone', charger.drone!.code),
                InfoRow('Pin', '${charger.batteryPercent ?? '—'}% (từ ${charger.startPercent ?? '—'}% lên ${charger.targetPercent ?? '—'}%)'),
                InfoRow('Đã sạc', minutesLabel(charger.chargedMinutes)),
                InfoRow('Còn lại', charger.state == ChargerState.full ? 'Đã đầy' : minutesLabel(charger.remainingMinutes)),
                InfoRow('Đầy lúc', hm(charger.readyAt)),
                InfoRow('Nhiệt độ', charger.temperatureC == null ? '—' : '${charger.temperatureC!.toStringAsFixed(1)} °C'),
              ] else
                const Padding(
                    padding: EdgeInsets.symmetric(vertical: 4),
                    child: Text('Không có drone trên thiết bị này.', style: TextStyle(color: AppColors.textMuted))),
              const Padding(
                  padding: EdgeInsets.only(top: 6),
                  child: Text(_simNote, style: TextStyle(color: AppColors.textMuted, fontSize: 12))),
              const SizedBox(height: 14),
              if (charger.drone != null)
                FilledButton(
                    key: const Key('release-drone'),
                    // Server chỉ cho rời khi đầy; nút tắt kèm lý do thay vì để bấm rồi mới báo lỗi.
                    onPressed: (!charger.canRelease || working)
                        ? null
                        : () => _act(context, ref, () => actions.release(stationId, chargerId, charger.code)),
                    child: Text(charger.canRelease ? 'Cho drone rời thiết bị' : 'Chưa sạc đầy, chưa cho rời')),
              if (charger.drone == null || charger.state == ChargerState.maintenance) ...[
                const SizedBox(height: 8),
                OutlinedButton(
                    key: const Key('toggle-maintenance'),
                    onPressed: working
                        ? null
                        : () => _act(
                            context,
                            ref,
                            () => actions.setMaintenance(
                                stationId, chargerId, charger.code, charger.state != ChargerState.maintenance)),
                    child: Text(charger.state == ChargerState.maintenance ? 'Đưa vào hoạt động lại' : 'Chuyển sang bảo trì')),
              ] else
                const Padding(
                    padding: EdgeInsets.only(top: 8),
                    child: Text('Muốn bảo trì thiết bị này, hãy cho drone rời trước.',
                        key: Key('maintenance-hint'), style: TextStyle(color: AppColors.textMuted, fontSize: 13))),
            ])));
  }
}

// ---------------------------------------------------------------------------------------------------
// Đội drone và điều phối

class FleetScreen extends ConsumerWidget {
  const FleetScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final board = ref.watch(dispatchBoardProvider);
    Future<void> refresh() async {
      ref.invalidate(dispatchBoardProvider);
      try {
        await ref.read(dispatchBoardProvider.future);
      } catch (_) {}
    }

    return Scaffold(
        appBar: AppBar(
            title: const Text('Đội drone'),
            leading: IconButton(
                tooltip: 'Quay lại',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => context.canPop() ? context.pop() : context.go('/operator/stations')),
            actions: [IconButton(tooltip: 'Tải lại', onPressed: refresh, icon: const Icon(Icons.refresh))]),
        body: RefreshIndicator(
            onRefresh: refresh,
            child: board.when(
                loading: () => const AsyncStateView(loading: true, child: SizedBox.shrink()),
                error: (e, _) => AsyncStateView(
                    error: toApiFailure(e), onRetry: refresh, child: const SizedBox.shrink()),
                data: (b) => ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                    children: [
                      const Padding(
                          padding: EdgeInsets.only(bottom: 8),
                          child: Text('Mốc thời gian là ước tính theo công thức, không phải theo dõi thật.',
                              key: Key('fleet-note'),
                              style: TextStyle(color: AppColors.textMuted, fontSize: 13))),
                      InfoCard('Đơn cần drone', [
                        if (b.orders.isEmpty)
                          const Text('Không có đơn nào cần hoặc đang dùng drone.',
                              key: Key('no-dispatch'), style: TextStyle(color: AppColors.textMuted))
                        else
                          for (final r in b.orders) _DispatchTile(r),
                      ]),
                      InfoCard('Đội drone (${b.fleet.length})', [
                        if (b.fleet.isEmpty)
                          const Text('Chưa có drone nào.', style: TextStyle(color: AppColors.textMuted))
                        else
                          for (final d in b.fleet)
                            ListTile(
                                key: Key('fleet-${d.code}'),
                                dense: true,
                                contentPadding: EdgeInsets.zero,
                                leading: const Icon(Icons.flight_outlined, color: AppColors.primary),
                                title: Text(d.code, style: const TextStyle(fontWeight: FontWeight.w700)),
                                subtitle: Text('${droneStatusLabel(d.status)} · ${d.stationName.isEmpty ? 'đang bay' : d.stationName}'),
                                trailing: Text('${d.batteryPercent}%', style: const TextStyle(fontWeight: FontWeight.w800))),
                      ]),
                    ]))));
  }
}

class _DispatchTile extends ConsumerWidget {
  const _DispatchTile(this.row);
  final DispatchRow row;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final p = row.plan;
    final busy = ref.watch(chargingActionsProvider).contains('assign-${row.orderId}');
    // Chỉ đề nghị phân công khi đơn chưa có drone và chưa bay; server vẫn là nơi quyết định.
    final canAssign = !p.assigned && const {'PLANNED', 'NO_DRONE', 'NO_CHARGER'}.contains(p.stage);
    return Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
                child: Text('Đơn ${row.shortCode}', style: const TextStyle(fontWeight: FontWeight.w800))),
            Text(dispatchStageLabel(p.stage),
                key: Key('stage-${row.shortCode}'),
                style: const TextStyle(color: AppColors.primary, fontSize: 12, fontWeight: FontWeight.w700)),
          ]),
          Text('${row.pickupStationName} → ${row.dropoffStationName}',
              style: const TextStyle(color: AppColors.textMuted)),
          if (p.droneCode != null)
            Text('Drone ${p.droneCode}${p.originStationName == null ? '' : ' từ ${p.originStationName}'}'
                '${p.reservedChargerCode == null ? '' : ' · sạc ${p.reservedChargerCode}'}'),
          if (p.plannedDepartureAt != null)
            Text('Khởi hành dự kiến ${hm(p.plannedDepartureAt)}, đến ${hm(p.plannedArrivalAt)}'),
          if ((p.waitMinutes ?? 0) > 0)
            Text('Chờ ${minutesLabel(p.waitMinutes)}${(p.waitReason ?? '').isEmpty ? '' : ': ${p.waitReason}'}',
                style: TextStyle(color: StatusPalette.pending.foreground)),
          if ((p.message ?? '').isNotEmpty)
            Text(p.message!, style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
          if (canAssign)
            Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                    key: Key('assign-${row.shortCode}'),
                    onPressed: busy
                        ? null
                        : () async {
                            final r = await ref.read(chargingActionsProvider.notifier).assign(row.orderId);
                            if (!context.mounted || r.message.isEmpty) return;
                            ScaffoldMessenger.of(context)
                              ..hideCurrentSnackBar()
                              ..showSnackBar(SnackBar(content: Text(r.message)));
                          },
                    child: Text(busy ? 'Đang tính…' : 'Phân công / tính lại'))),
          const Divider(height: 16),
        ]));
  }
}
