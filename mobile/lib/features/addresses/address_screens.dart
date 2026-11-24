import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_failure.dart';
import '../orders/customer_order_models.dart';
import 'address_controller.dart';

/// Sổ địa chỉ. Ở chế độ [pick] chạm vào một địa chỉ sẽ trả nó về màn gọi
/// (`context.pop(address)`); ở chế độ thường dùng để xem, sửa, xóa.
class AddressListScreen extends ConsumerWidget {
  const AddressListScreen({super.key, this.pick = false, this.selectedId});
  final bool pick;
  final String? selectedId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(addressesProvider);
    final controller = ref.read(addressesProvider.notifier);
    return Scaffold(
        appBar: AppBar(
            title: Text(pick ? 'Chọn địa chỉ người nhận' : 'Địa chỉ của tôi'),
            actions: [
              IconButton(
                  tooltip: 'Tải lại',
                  onPressed: controller.reload,
                  icon: const Icon(Icons.refresh))
            ]),
        floatingActionButton: FloatingActionButton.extended(
            key: const Key('add-address'),
            onPressed: () async {
              final created = await openAddressForm(context);
              if (created != null && pick && context.mounted) {
                context.pop(created); // vừa thêm xong thì dùng luôn
              }
            },
            icon: const Icon(Icons.add_location_alt_outlined),
            label: const Text('Thêm địa chỉ')),
        body: Column(children: [
          if (state.actionError != null)
            Container(
                key: const Key('address-action-error'),
                width: double.infinity,
                color: Theme.of(context).colorScheme.errorContainer,
                padding: const EdgeInsets.only(left: 12, top: 4, bottom: 4),
                child: Row(children: [
                  Expanded(child: Text(state.actionError!.message)),
                  IconButton(
                      tooltip: 'Đóng',
                      onPressed: controller.dismissError,
                      icon: const Icon(Icons.close)),
                ])),
          Expanded(
              child: RefreshIndicator(
                  onRefresh: controller.reload,
                  child: _body(context, ref, state, controller))),
        ]));
  }

  Widget _body(BuildContext context, WidgetRef ref, AddressListState state,
      AddressController controller) {
    if (state.loading) return const Center(child: CircularProgressIndicator());
    if (state.error != null) {
      return ListView(children: [
        Padding(
            padding: const EdgeInsets.all(24),
            child: Column(children: [
              Text(state.error!.message, key: const Key('address-error')),
              const SizedBox(height: 12),
              FilledButton(
                  onPressed: controller.reload, child: const Text('Thử lại')),
            ]))
      ]);
    }
    if (state.items.isEmpty) {
      return ListView(children: const [
        Padding(
            padding: EdgeInsets.all(32),
            child: Text('Bạn chưa có địa chỉ nào. Hãy thêm địa chỉ người nhận.',
                key: Key('address-empty'), textAlign: TextAlign.center))
      ]);
    }
    return ListView.separated(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 88),
        itemCount: state.items.length,
        separatorBuilder: (_, __) => const SizedBox(height: 8),
        itemBuilder: (context, i) {
          final a = state.items[i];
          return Card(
              child: ListTile(
                  key: Key('address-${a.id}'),
                  selected: a.id == selectedId,
                  title: Text(a.label),
                  subtitle: Text('${a.recipientName} · ${a.phone}\n${a.addressLine}'),
                  isThreeLine: true,
                  onTap: pick ? () => context.pop(a) : null,
                  trailing: PopupMenuButton<String>(
                      key: Key('address-menu-${a.id}'),
                      tooltip: 'Tùy chọn',
                      onSelected: (v) async {
                        if (v == 'edit') {
                          await openAddressForm(context, existing: a);
                        } else if (v == 'delete') {
                          await _confirmDelete(context, controller, a);
                        }
                      },
                      itemBuilder: (_) => const [
                            PopupMenuItem(value: 'edit', child: Text('Sửa')),
                            PopupMenuItem(value: 'delete', child: Text('Xóa')),
                          ])));
        });
  }

  Future<void> _confirmDelete(
      BuildContext context, AddressController controller, Address a) async {
    final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
                title: const Text('Xóa địa chỉ?'),
                content: Text('Xóa "${a.label}" khỏi sổ địa chỉ? Các đơn đã tạo không bị ảnh hưởng.'),
                actions: [
                  TextButton(
                      key: const Key('delete-dismiss'),
                      onPressed: () => Navigator.of(ctx).pop(false),
                      child: const Text('Giữ lại')),
                  FilledButton(
                      key: const Key('delete-confirm'),
                      onPressed: () => Navigator.of(ctx).pop(true),
                      child: const Text('Xóa')),
                ]));
    if (ok == true) await controller.remove(a.id);
  }
}

/// Mở form thêm/sửa địa chỉ; trả về địa chỉ đã lưu hoặc null nếu hủy.
Future<Address?> openAddressForm(BuildContext context, {Address? existing}) =>
    Navigator.of(context).push<Address>(MaterialPageRoute(
        builder: (_) => AddressFormScreen(existing: existing)));

class AddressFormScreen extends ConsumerStatefulWidget {
  const AddressFormScreen({super.key, this.existing});
  final Address? existing;
  @override
  ConsumerState<AddressFormScreen> createState() => _AddressFormScreenState();
}

class _AddressFormScreenState extends ConsumerState<AddressFormScreen> {
  final _form = GlobalKey<FormState>();
  late final _label = TextEditingController(text: widget.existing?.label);
  late final _name = TextEditingController(text: widget.existing?.recipientName);
  late final _phone = TextEditingController(text: widget.existing?.phone);
  late final _line = TextEditingController(text: widget.existing?.addressLine);
  late final _lat = TextEditingController(
      text: widget.existing == null ? '' : '${widget.existing!.latitude}');
  late final _lng = TextEditingController(
      text: widget.existing == null ? '' : '${widget.existing!.longitude}');
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    for (final c in [_label, _name, _phone, _line, _lat, _lng]) {
      c.dispose();
    }
    super.dispose();
  }

  double _parse(TextEditingController c) =>
      double.parse(c.text.trim().replaceAll(',', '.'));

  Future<void> _save() async {
    if (_saving) return; // chặn bấm đúp
    if (!_form.currentState!.validate()) return;
    final input = AddressInput(
        label: _label.text.trim(),
        recipientName: _name.text.trim(),
        phone: _phone.text.trim(),
        addressLine: _line.text.trim(),
        latitude: _parse(_lat),
        longitude: _parse(_lng));
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final controller = ref.read(addressesProvider.notifier);
      final saved = widget.existing == null
          ? await controller.add(input)
          : await controller.update(widget.existing!.id, input);
      if (mounted) Navigator.of(context).pop(saved);
    } catch (e) {
      // Giữ nguyên dữ liệu đã nhập để người dùng sửa và gửi lại.
      if (mounted) {
        setState(() {
          _saving = false;
          _error = toApiFailure(e).message;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(
          title: Text(widget.existing == null ? 'Thêm địa chỉ' : 'Sửa địa chỉ')),
      body: Form(
          key: _form,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            if (_error != null)
              Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Text(_error!,
                      key: const Key('address-form-error'),
                      style: TextStyle(color: Theme.of(context).colorScheme.error))),
            _field(_label, 'Nhãn (ví dụ: Nhà, Công ty)', 'address-label',
                (v) => AddressValidation.required(v, name: 'Nhãn')),
            _field(_name, 'Tên người nhận', 'address-name',
                (v) => AddressValidation.required(v, name: 'Tên người nhận')),
            _field(_phone, 'Số điện thoại', 'address-phone', AddressValidation.phone,
                keyboard: TextInputType.phone),
            _field(_line, 'Địa chỉ', 'address-line',
                (v) => AddressValidation.required(v, name: 'Địa chỉ')),
            _field(
                _lat,
                'Vĩ độ (-90 đến 90)',
                'address-lat',
                (v) => AddressValidation.coordinate(v,
                    min: -90, max: 90, name: 'Vĩ độ'),
                keyboard: const TextInputType.numberWithOptions(
                    decimal: true, signed: true)),
            _field(
                _lng,
                'Kinh độ (-180 đến 180)',
                'address-lng',
                (v) => AddressValidation.coordinate(v,
                    min: -180, max: 180, name: 'Kinh độ'),
                keyboard: const TextInputType.numberWithOptions(
                    decimal: true, signed: true)),
            const SizedBox(height: 8),
            FilledButton(
                key: const Key('save-address'),
                onPressed: _saving ? null : _save,
                child: _saving
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Text('Lưu địa chỉ')),
          ])));

  Widget _field(TextEditingController c, String label, String key,
          String? Function(String?) validator,
          {TextInputType? keyboard}) =>
      Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: TextFormField(
              key: Key(key),
              controller: c,
              keyboardType: keyboard,
              decoration: InputDecoration(
                  labelText: label, border: const OutlineInputBorder()),
              validator: validator));
}
