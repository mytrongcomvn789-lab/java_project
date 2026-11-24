import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../orders/photo_picker.dart' show PhotoSource;
import 'confirmation_photo_controller.dart';

/// Khu vực ảnh xác nhận giao hàng: chụp hoặc chọn ảnh, xem trước, thử lại, thay, xóa.
/// Dùng ở màn xác nhận giao với `ref.watch(confirmationPhotoProvider(orderId)).fileId`.
class ConfirmationPhotoPicker extends ConsumerWidget {
  const ConfirmationPhotoPicker({super.key, required this.orderId});
  final String orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(confirmationPhotoProvider(orderId));
    final controller = ref.read(confirmationPhotoProvider(orderId).notifier);
    final theme = Theme.of(context);
    final photo = state.photo;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (state.permissionDenied != null)
        _Notice(
            key: const Key('photo-permission'),
            color: theme.colorScheme.errorContainer,
            text: state.permissionDenied == PhotoSource.camera
                ? 'Ứng dụng chưa được cấp quyền camera. Vào Cài đặt > Ứng dụng > SmartDroneDelivery > Quyền để cho phép, hoặc chọn ảnh từ thư viện.'
                : 'Ứng dụng chưa được cấp quyền truy cập ảnh. Vào Cài đặt > Ứng dụng > SmartDroneDelivery > Quyền để cho phép, hoặc chụp ảnh bằng camera.',
            onClose: controller.dismissMessages),
      if (state.pickError != null)
        _Notice(
            key: const Key('photo-error'),
            color: theme.colorScheme.errorContainer,
            text: state.pickError!,
            onClose: controller.dismissMessages),
      if (photo == null)
        Container(
            key: const Key('photo-empty'),
            height: 160,
            width: double.infinity,
            decoration: BoxDecoration(
                border: Border.all(color: theme.dividerColor),
                borderRadius: BorderRadius.circular(8)),
            child: const Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.photo_camera_outlined, size: 40),
                  SizedBox(height: 8),
                  Text('Chưa có ảnh xác nhận'),
                ]))
      else
        ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: Stack(alignment: Alignment.center, children: [
              Image.memory(photo.bytes,
                  key: const Key('photo-preview'),
                  height: 200,
                  width: double.infinity,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => const SizedBox(
                      height: 200, child: Center(child: Icon(Icons.broken_image)))),
              if (photo.status == ConfirmPhotoStatus.uploading)
                Container(
                    key: const Key('photo-uploading'),
                    height: 200,
                    width: double.infinity,
                    color: Colors.black38,
                    child: const Center(child: CircularProgressIndicator())),
              if (photo.status == ConfirmPhotoStatus.done)
                const Positioned(
                    top: 8,
                    right: 8,
                    child: Chip(
                        key: Key('photo-done'),
                        avatar: Icon(Icons.check_circle, color: Colors.green),
                        label: Text('Đã tải lên'))),
            ])),
      if (photo != null && photo.status == ConfirmPhotoStatus.failed) ...[
        const SizedBox(height: 8),
        Text(photo.failure?.message ?? 'Tải ảnh lên không thành công.',
            key: const Key('photo-failure'),
            style: TextStyle(color: theme.colorScheme.error)),
      ],
      const SizedBox(height: 8),
      Wrap(spacing: 8, runSpacing: 4, children: [
        OutlinedButton.icon(
            key: const Key('photo-camera'),
            onPressed: state.uploading ? null : () => controller.pick(PhotoSource.camera),
            icon: const Icon(Icons.photo_camera_outlined),
            label: Text(photo == null ? 'Chụp ảnh' : 'Chụp lại')),
        OutlinedButton.icon(
            key: const Key('photo-gallery'),
            onPressed: state.uploading ? null : () => controller.pick(PhotoSource.gallery),
            icon: const Icon(Icons.photo_library_outlined),
            label: const Text('Chọn từ thư viện')),
        // Chỉ thử lại khi lỗi tạm thời; ảnh quá lớn hay sai loại phải chụp lại.
        if (photo != null &&
            photo.status == ConfirmPhotoStatus.failed &&
            (photo.failure?.isRetryable ?? true))
          FilledButton.icon(
              key: const Key('photo-retry'),
              onPressed: controller.retry,
              icon: const Icon(Icons.refresh),
              label: const Text('Thử lại')),
        if (photo != null)
          TextButton.icon(
              key: const Key('photo-remove'),
              onPressed: state.uploading ? null : controller.remove,
              icon: const Icon(Icons.delete_outline),
              label: const Text('Xóa ảnh')),
      ]),
    ]);
  }
}

class _Notice extends StatelessWidget {
  const _Notice(
      {super.key, required this.color, required this.text, required this.onClose});
  final Color color;
  final String text;
  final VoidCallback onClose;
  @override
  Widget build(BuildContext context) => Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.only(left: 12, top: 4, bottom: 4),
      decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(8)),
      child: Row(children: [
        Expanded(child: Text(text)),
        IconButton(
            tooltip: 'Đóng', onPressed: onClose, icon: const Icon(Icons.close)),
      ]));
}
