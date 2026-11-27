import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../../core/theme/app_theme.dart';
import '../../core/ui/async_state_view.dart';
import 'dispatcher_api.dart';
import 'dispatcher_controllers.dart';

String two(int n) => n.toString().padLeft(2, '0');

/// "HH:mm" theo giờ Việt Nam.
String vnHm(DateTime t) {
  final d = vnTime(t);
  return '${two(d.hour)}:${two(d.minute)}';
}

/// Ngày ngắn theo giờ Việt Nam, ví dụ "T6 27/11".
String vnDayLabel(DateTime t) {
  final d = vnTime(t);
  const names = ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'];
  return '${names[d.weekday - 1]} ${two(d.day)}/${two(d.month)}';
}

/// Bảng chọn khung giờ của một trạm: chọn ngày, thấy số chỗ còn lại từng khung, chỉ chọn được khung
/// còn chỗ. Trả về thời điểm bắt đầu (UTC) của khung đã chọn, hoặc null khi đóng. Server vẫn kiểm tra
/// lại (khung có thể vừa đầy), nên lỗi 409 khi gửi được hiện trên màn chi tiết.
class SlotPickerSheet extends ConsumerStatefulWidget {
  const SlotPickerSheet(
      {super.key, required this.stationId, required this.stationName, this.title = 'Chọn khung giờ'});
  final String stationId, stationName, title;

  /// Số ngày cho chọn (hôm nay và các ngày kế tiếp). Server quyết định khung nào đặt được.
  static const days = 4;

  @override
  ConsumerState<SlotPickerSheet> createState() => _SlotPickerSheetState();
}

class _SlotPickerSheetState extends ConsumerState<SlotPickerSheet> {
  int _day = 0;
  DateTime? _picked;

  late final DateTime _today = DateTime.now();

  String _date(int offset) => vnDateParam(_today.add(Duration(days: offset)));

  @override
  Widget build(BuildContext context) {
    final key = AvailabilityKey(widget.stationId, _date(_day));
    final avail = ref.watch(availabilityProvider(key));
    return SafeArea(
        child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.85),
            child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
                child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(widget.title,
                      style: Theme.of(context)
                          .textTheme
                          .titleLarge
                          ?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 4),
                  Text('Trạm gửi: ${widget.stationName}',
                      style: const TextStyle(color: AppColors.textMuted)),
                  const SizedBox(height: 12),
                  Wrap(spacing: 8, runSpacing: 4, children: [
                    for (var i = 0; i < SlotPickerSheet.days; i++)
                      ChoiceChip(
                          key: Key('slot-day-$i'),
                          label: Text(i == 0
                              ? 'Hôm nay'
                              : vnDayLabel(_today.add(Duration(days: i)))),
                          selected: _day == i,
                          showCheckmark: false,
                          backgroundColor: AppColors.surface,
                          selectedColor: AppColors.primary,
                          labelStyle: TextStyle(
                              fontWeight: FontWeight.w600,
                              color: _day == i ? Colors.white : AppColors.text),
                          onSelected: (_) => setState(() {
                                _day = i;
                                _picked = null;
                              })),
                  ]),
                  const SizedBox(height: 12),
                  Flexible(
                      child: SingleChildScrollView(
                          child: avail.when(
                              loading: () => const Padding(
                                  padding: EdgeInsets.symmetric(vertical: 24),
                                  child: Center(child: CircularProgressIndicator())),
                              error: (e, _) => AsyncStateView(
                                  error: toApiFailure(e),
                                  onRetry: () => ref.invalidate(availabilityProvider(key)),
                                  child: const SizedBox.shrink()),
                              data: (a) => _grid(a)))),
                  const SizedBox(height: 12),
                  FilledButton(
                      key: const Key('slot-confirm'),
                      onPressed: _picked == null ? null : () => Navigator.of(context).pop(_picked),
                      child: Text(_picked == null
                          ? 'Chọn một khung giờ'
                          : 'Chọn khung ${vnHm(_picked!)}')),
                ]))));
  }

  /// Khung ngoài thời gian cho phép đặt (đã qua, quá xa) còn chỗ nhưng `bookable=false`: ẩn đi cho gọn.
  /// Khung đã đầy vẫn hiện để điều phối viên biết vì sao không chọn được.
  static List<SlotInfo> visibleSlots(List<SlotInfo> all) =>
      [for (final s in all) if (s.bookable || s.free == 0) s];

  Widget _grid(StationAvailability a) {
    final slots = visibleSlots(a.slots);
    if (slots.isEmpty) {
      return const Padding(
          padding: EdgeInsets.symmetric(vertical: 24),
          child: Text('Không còn khung giờ nào đặt được trong ngày này. Thử chọn ngày khác.',
              key: Key('slot-empty'), style: TextStyle(color: AppColors.textMuted)));
    }
    final open = slots.where((s) => s.bookable).length;
    // Ba cột đều nhau trong bề rộng của bảng (trừ lề 20 hai bên và hai khoảng cách 8).
    final width = (MediaQuery.sizeOf(context).width - 40 - 16) / 3;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('$open khung còn chỗ',
          key: const Key('slot-summary'),
          style: const TextStyle(color: AppColors.textMuted, fontSize: 13)),
      const SizedBox(height: 8),
      Wrap(spacing: 8, runSpacing: 8, children: [
        for (final s in slots)
          _SlotChip(
              slot: s,
              width: width,
              selected: _picked == s.start,
              onTap: s.bookable ? () => setState(() => _picked = s.start) : null),
      ]),
    ]);
  }
}

class _SlotChip extends StatelessWidget {
  const _SlotChip(
      {required this.slot, required this.selected, required this.onTap, required this.width});
  final SlotInfo slot;
  final double width;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    final fg = selected
        ? Colors.white
        : enabled
            ? AppColors.text
            : AppColors.textMuted;
    return Semantics(
        button: true,
        enabled: enabled,
        selected: selected,
        label: '${vnHm(slot.start)}, ${enabled ? 'còn ${slot.free} chỗ' : 'không đặt được'}',
        excludeSemantics: true,
        child: InkWell(
            key: Key('slot-${slot.start.toUtc().toIso8601String()}'),
            borderRadius: BorderRadius.circular(AppRadii.button),
            onTap: onTap,
            child: Container(
                width: width,
                padding: const EdgeInsets.symmetric(vertical: 10),
                decoration: BoxDecoration(
                    color: selected
                        ? AppColors.primary
                        : enabled
                            ? AppColors.surface
                            : AppColors.background,
                    border: Border.all(
                        color: selected ? AppColors.primary : AppColors.border),
                    borderRadius: BorderRadius.circular(AppRadii.button)),
                child: Column(children: [
                  Text(vnHm(slot.start),
                      style: TextStyle(color: fg, fontWeight: FontWeight.w800, fontSize: 15)),
                  const SizedBox(height: 2),
                  Text(enabled ? 'còn ${slot.free} chỗ' : (slot.free == 0 ? 'đã đầy' : 'không đặt được'),
                      style: TextStyle(
                          color: selected ? Colors.white70 : AppColors.textMuted,
                          fontSize: 11)),
                ]))));
  }
}

/// Bảng nhập lý do (từ chối đơn, hoàn hàng). Trả về lý do đã cắt khoảng trắng, hoặc null khi đóng.
class ReasonSheet extends StatefulWidget {
  const ReasonSheet(
      {super.key,
      required this.title,
      required this.hint,
      required this.confirmLabel,
      this.maxLength = 500});
  final String title, hint, confirmLabel;
  final int maxLength;

  @override
  State<ReasonSheet> createState() => _ReasonSheetState();
}

class _ReasonSheetState extends State<ReasonSheet> {
  final _text = TextEditingController();

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => SafeArea(
      child: Padding(
          padding: EdgeInsets.fromLTRB(
              20, 16, 20, 16 + MediaQuery.viewInsetsOf(context).bottom),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(widget.title,
                style: Theme.of(context)
                    .textTheme
                    .titleLarge
                    ?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 12),
            TextField(
                key: const Key('reason-field'),
                controller: _text,
                autofocus: true,
                minLines: 3,
                maxLines: 5,
                maxLength: widget.maxLength,
                decoration: InputDecoration(hintText: widget.hint),
                onChanged: (_) => setState(() {})),
            const SizedBox(height: 8),
            FilledButton(
                key: const Key('reason-confirm'),
                onPressed: _text.text.trim().isEmpty
                    ? null
                    : () => Navigator.of(context).pop(_text.text.trim()),
                child: Text(widget.confirmLabel)),
          ])));
}

/// Kết quả của bảng báo thất bại.
class FailureChoice {
  const FailureChoice(this.code, this.note);
  final String code;
  final String? note;
}

/// Bảng chọn lý do thất bại (mã cố định của backend); chọn "Lý do khác" thì bắt buộc ghi chú.
class FailureSheet extends StatefulWidget {
  const FailureSheet({super.key});
  @override
  State<FailureSheet> createState() => _FailureSheetState();
}

class _FailureSheetState extends State<FailureSheet> {
  String? _code;
  final _note = TextEditingController();

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  bool get _valid => _code != null && (_code != 'OTHER' || _note.text.trim().isNotEmpty);

  @override
  Widget build(BuildContext context) => SafeArea(
      child: SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
              20, 16, 20, 16 + MediaQuery.viewInsetsOf(context).bottom),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Báo giao thất bại',
                style: Theme.of(context)
                    .textTheme
                    .titleLarge
                    ?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 4),
            const Text('Chọn lý do. Đơn sẽ chuyển sang "Giao thất bại".',
                style: TextStyle(color: AppColors.textMuted)),
            const SizedBox(height: 8),
            RadioGroup<String>(
                groupValue: _code,
                onChanged: (v) => setState(() => _code = v),
                child: Column(children: [
                  for (final e in failureReasons.entries)
                    RadioListTile<String>(
                        key: Key('failure-${e.key}'),
                        contentPadding: EdgeInsets.zero,
                        value: e.key,
                        title: Text(e.value)),
                ])),
            if (_code == 'OTHER')
              TextField(
                  key: const Key('failure-note'),
                  controller: _note,
                  maxLength: 500,
                  minLines: 2,
                  maxLines: 4,
                  decoration: const InputDecoration(hintText: 'Ghi chú (bắt buộc)'),
                  onChanged: (_) => setState(() {})),
            const SizedBox(height: 8),
            FilledButton(
                key: const Key('failure-confirm'),
                style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
                onPressed: _valid
                    ? () => Navigator.of(context)
                        .pop(FailureChoice(_code!, _note.text.trim().isEmpty ? null : _note.text.trim()))
                    : null,
                child: const Text('Báo thất bại')),
          ])));
}
