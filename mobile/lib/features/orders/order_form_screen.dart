import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_failure.dart';
import '../addresses/address_controller.dart';
import 'customer_order_models.dart';
import 'order_detail_controller.dart';
import 'order_form_controller.dart';
import 'photo_picker.dart';

/// Tạo đơn mới. Route `/customer/new-order`. Chọn trạm và địa chỉ từ dữ liệu
/// thật của server; mọi kiểm tra nghiệp vụ do server quyết định.
class OrderFormScreen extends ConsumerStatefulWidget {
  const OrderFormScreen({super.key});
  @override
  ConsumerState<OrderFormScreen> createState() => _OrderFormScreenState();
}

class _OrderFormScreenState extends ConsumerState<OrderFormScreen> {
  final _form = GlobalKey<FormState>();
  final _desc = TextEditingController();
  final _weight = TextEditingController();
  final _length = TextEditingController();
  final _width = TextEditingController();
  final _height = TextEditingController();

  @override
  void dispose() {
    for (final c in [_desc, _weight, _length, _width, _height]) {
      c.dispose();
    }
    super.dispose();
  }

  double _num(TextEditingController c) =>
      double.parse(c.text.trim().replaceAll(',', '.'));

  Future<void> _submit() async {
    final controller = ref.read(orderFormProvider.notifier);
    if (ref.read(orderFormProvider).submitting) return;
    if (!_form.currentState!.validate()) return;
    final ok = await controller.submit(OrderFormValues(
        description: _desc.text,
        weightGrams: int.parse(_weight.text.trim()),
        lengthCm: _num(_length),
        widthCm: _num(_width),
        heightCm: _num(_height)));
    if (!ok || !mounted) return;
    final created = ref.read(orderFormProvider).created;
    if (created == null) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(created.replayed
            ? 'Đơn này đã được tạo trước đó.'
            : 'Đã tạo đơn thành công.')));
    context.go('/customer/orders/${created.order.id}/detail');
  }

  Future<void> _pickAddress() async {
    final selected = ref.read(orderFormProvider).address?.id;
    final picked = await context.push<Address>(
        '/customer/addresses?pick=1${selected == null ? '' : '&selected=$selected'}');
    if (picked != null) ref.read(orderFormProvider.notifier).setAddress(picked);
  }

  void _photoSheet() => showModalBottomSheet<void>(
      context: context,
      builder: (ctx) => SafeArea(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
            ListTile(
                key: const Key('photo-gallery'),
                leading: const Icon(Icons.photo_library_outlined),
                title: const Text('Chọn từ thư viện'),
                onTap: () {
                  Navigator.of(ctx).pop();
                  ref.read(orderFormProvider.notifier).addPhoto(PhotoSource.gallery);
                }),
            ListTile(
                key: const Key('photo-camera'),
                leading: const Icon(Icons.photo_camera_outlined),
                title: const Text('Chụp ảnh'),
                onTap: () {
                  Navigator.of(ctx).pop();
                  ref.read(orderFormProvider.notifier).addPhoto(PhotoSource.camera);
                }),
          ])));

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(orderFormProvider);
    final controller = ref.read(orderFormProvider.notifier);
    final stations = ref.watch(stationsProvider);
    final theme = Theme.of(context);
    final busy = state.submitting;
    return Scaffold(
        appBar: AppBar(title: const Text('Tạo đơn mới')),
        body: AbsorbPointer(
            absorbing: busy,
            child: Form(
                key: _form,
                child: ListView(padding: const EdgeInsets.all(16), children: [
                  if (state.error != null) _ErrorBox(state.error!),
                  Text('Lộ trình', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 8),
                  stations.when(
                      loading: () => const Padding(
                          padding: EdgeInsets.all(12),
                          child: Center(child: CircularProgressIndicator())),
                      error: (e, _) => Column(children: [
                            Text(toApiFailure(e).message,
                                key: const Key('stations-error')),
                            TextButton(
                                onPressed: () => ref.invalidate(stationsProvider),
                                child: const Text('Tải lại danh sách trạm')),
                          ]),
                      data: (all) {
                        final active = all.where((s) => s.isActive).toList();
                        return Column(children: [
                          _stationField('Trạm gửi', 'pickup-station', active,
                              state.pickupId, controller.setPickup),
                          const SizedBox(height: 12),
                          _stationField('Trạm nhận', 'dropoff-station', active,
                              state.dropoffId, controller.setDropoff,
                              exclude: state.pickupId),
                        ]);
                      }),
                  const SizedBox(height: 16),
                  Text('Người nhận', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 8),
                  Card(
                      child: ListTile(
                          key: const Key('pick-address'),
                          leading: const Icon(Icons.location_on_outlined),
                          title: Text(state.address == null
                              ? 'Chọn địa chỉ người nhận'
                              : '${state.address!.label} · ${state.address!.recipientName}'),
                          subtitle: state.address == null
                              ? null
                              : Text(
                                  '${state.address!.phone}\n${state.address!.addressLine}'),
                          isThreeLine: state.address != null,
                          trailing: const Icon(Icons.chevron_right),
                          onTap: _pickAddress)),
                  const SizedBox(height: 16),
                  Text('Gói hàng', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 8),
                  TextFormField(
                      key: const Key('package-description'),
                      controller: _desc,
                      maxLength: 500,
                      decoration: const InputDecoration(
                          labelText: 'Mô tả gói hàng',
                          border: OutlineInputBorder()),
                      validator: (v) =>
                          AddressValidation.required(v, name: 'Mô tả gói hàng')),
                  const SizedBox(height: 4),
                  _numberField(_weight, 'Trọng lượng (gam)', 'package-weight',
                      integer: true),
                  Row(children: [
                    Expanded(child: _numberField(_length, 'Dài (cm)', 'package-length')),
                    const SizedBox(width: 8),
                    Expanded(child: _numberField(_width, 'Rộng (cm)', 'package-width')),
                    const SizedBox(width: 8),
                    Expanded(child: _numberField(_height, 'Cao (cm)', 'package-height')),
                  ]),
                  const SizedBox(height: 8),
                  Text('Ảnh gói hàng (tùy chọn, tối đa $maxPackagePhotos ảnh)',
                      style: theme.textTheme.titleSmall),
                  const SizedBox(height: 8),
                  _photos(state, controller),
                  if (state.photoError != null)
                    Padding(
                        padding: const EdgeInsets.only(top: 8),
                        child: Text(state.photoError!,
                            key: const Key('photo-error'),
                            style: TextStyle(color: theme.colorScheme.error))),
                  const SizedBox(height: 24),
                  FilledButton(
                      key: const Key('submit-order'),
                      // Vô hiệu hóa khi đang gửi hoặc ảnh đang tải: chặn bấm đúp.
                      onPressed: busy || state.uploading ? null : _submit,
                      child: busy
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : Text(state.error?.outcomeUnknown == true ||
                                  state.error?.kind == FailureKind.offline
                              ? 'Gửi lại'
                              : 'Tạo đơn')),
                ]))));
  }

  Widget _stationField(String label, String key, List<Station> stations,
          String? value, ValueChanged<String?> onChanged, {String? exclude}) =>
      DropdownButtonFormField<String>(
          key: Key(key),
          initialValue: stations.any((s) => s.id == value) ? value : null,
          isExpanded: true,
          decoration:
              InputDecoration(labelText: label, border: const OutlineInputBorder()),
          items: [
            for (final s in stations)
              DropdownMenuItem(
                  value: s.id,
                  enabled: s.id != exclude,
                  child: Text(s.name, overflow: TextOverflow.ellipsis)),
          ],
          validator: (v) => v == null ? 'Cần chọn ${label.toLowerCase()}.' : null,
          onChanged: onChanged);

  Widget _numberField(TextEditingController c, String label, String key,
          {bool integer = false}) =>
      Padding(
          padding: const EdgeInsets.only(top: 8),
          child: TextFormField(
              key: Key(key),
              controller: c,
              keyboardType: TextInputType.numberWithOptions(decimal: !integer),
              inputFormatters: [
                FilteringTextInputFormatter.allow(
                    integer ? RegExp(r'[0-9]') : RegExp(r'[0-9.,]'))
              ],
              decoration: InputDecoration(
                  labelText: label, border: const OutlineInputBorder()),
              validator: (v) {
                final t = v?.trim().replaceAll(',', '.') ?? '';
                if (t.isEmpty) return 'Cần nhập.';
                final n = double.tryParse(t);
                if (n == null || n.isNaN || n.isInfinite || n <= 0) {
                  return 'Phải lớn hơn 0.';
                }
                return null;
              }));

  Widget _photos(OrderFormState state, OrderFormController controller) =>
      Wrap(spacing: 8, runSpacing: 8, children: [
        for (final p in state.photos)
          _PhotoTile(
              photo: p,
              onRemove: () => controller.removePhoto(p.id),
              onRetry: () => controller.retryPhoto(p.id)),
        if (state.photos.length < maxPackagePhotos)
          InkWell(
              key: const Key('add-photo'),
              onTap: _photoSheet,
              child: Container(
                  width: 88,
                  height: 88,
                  decoration: BoxDecoration(
                      border: Border.all(color: Theme.of(context).dividerColor),
                      borderRadius: BorderRadius.circular(8)),
                  child: const Icon(Icons.add_a_photo_outlined))),
      ]);
}

class _PhotoTile extends StatelessWidget {
  const _PhotoTile(
      {required this.photo, required this.onRemove, required this.onRetry});
  final PhotoAttachment photo;
  final VoidCallback onRemove, onRetry;

  @override
  Widget build(BuildContext context) => SizedBox(
      width: 88,
      height: 88,
      child: Stack(fit: StackFit.expand, children: [
        ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: Image.memory(photo.bytes,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => const ColoredBox(
                    color: Colors.black12, child: Icon(Icons.broken_image)))),
        if (photo.status == PhotoStatus.uploading)
          const ColoredBox(
              color: Colors.black38,
              child: Center(
                  child: SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2)))),
        if (photo.status == PhotoStatus.failed)
          ColoredBox(
              color: Colors.black54,
              child: Tooltip(
                  message: photo.failure?.message ?? 'Tải ảnh không thành công',
                  child: IconButton(
                      key: Key('retry-photo-${photo.id}'),
                      onPressed: onRetry,
                      color: Colors.white,
                      icon: const Icon(Icons.refresh)))),
        Positioned(
            top: 0,
            right: 0,
            child: InkWell(
                key: Key('remove-photo-${photo.id}'),
                onTap: onRemove,
                child: const CircleAvatar(
                    radius: 11,
                    backgroundColor: Colors.black54,
                    child: Icon(Icons.close, size: 14, color: Colors.white)))),
      ]));
}

class _ErrorBox extends StatelessWidget {
  const _ErrorBox(this.failure);
  final ApiFailure failure;
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final hint = switch (failure.kind) {
      FailureKind.offline => 'Đơn CHƯA được tạo. Kiểm tra mạng rồi bấm "Gửi lại".',
      FailureKind.timeout =>
        'Có thể đơn đã được tạo. Bấm "Gửi lại" là an toàn (không tạo trùng) hoặc kiểm tra danh sách đơn.',
      _ => null,
    };
    return Container(
        key: const Key('form-error'),
        margin: const EdgeInsets.only(bottom: 12),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
            color: scheme.errorContainer, borderRadius: BorderRadius.circular(8)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(failure.message),
          if (hint != null) ...[const SizedBox(height: 4), Text(hint)],
        ]));
  }
}
