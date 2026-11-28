import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../../core/theme/app_theme.dart';
import '../../core/ui/app_widgets.dart';
import '../../core/ui/async_state_view.dart';
import '../auth/session.dart';
import '../operator/operator_models.dart' show DeliveryConfirmation;

/// Bằng chứng giao hàng của đơn đã giao: phương thức xác nhận, thời điểm, ghi chú và ảnh chụp tại trạm.
class DeliveryEvidence {
  const DeliveryEvidence(this.confirmation, this.photoUrl);
  final DeliveryConfirmation confirmation;

  /// Liên kết ảnh có thời hạn do máy chủ cấp (không bao giờ lưu lại); null khi không lấy được ảnh.
  final String? photoUrl;
}

/// `GET /v1/orders/{id}/confirmation`, rồi xin liên kết xem ảnh `GET /v1/files/{id}/download-link`.
/// Không lấy được liên kết ảnh thì vẫn hiện phần chữ (ảnh là phần bổ sung, không phải điều kiện).
final deliveryEvidenceProvider =
    FutureProvider.autoDispose.family<DeliveryEvidence, String>((ref, orderId) async {
  final dio = ref.read(sessionProvider).api;
  final conf = await guardApi(() async {
    final res = await dio.get<Map<String, dynamic>>('/v1/orders/$orderId/confirmation');
    return DeliveryConfirmation.fromJson(res.data!);
  });
  String? url;
  try {
    url = await guardApi(() async {
      final res = await dio.get<Map<String, dynamic>>('/v1/files/${conf.photoFileId}/download-link');
      return res.data!['url'] as String?;
    });
  } on ApiFailure {
    url = null;
  }
  return DeliveryEvidence(conf, url);
});

String confirmMethodLabel(String method) => switch (method) {
      'OTP' => 'Mã OTP',
      'QR' => 'Mã QR',
      _ => method,
    };

String _fmt(DateTime? t) {
  if (t == null) return '—';
  String two(int n) => n.toString().padLeft(2, '0');
  final l = t.toLocal();
  return '${two(l.hour)}:${two(l.minute)} ${two(l.day)}/${two(l.month)}/${l.year}';
}

class DeliveryEvidenceCard extends ConsumerWidget {
  const DeliveryEvidenceCard({super.key, required this.orderId});
  final String orderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final evidence = ref.watch(deliveryEvidenceProvider(orderId));
    return InfoCard('Bằng chứng giao hàng', key: const Key('delivery-evidence'), [
      evidence.when(
          loading: () => const SkeletonBox(height: 160, radius: 14),
          error: (e, _) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(toApiFailure(e).message, key: const Key('evidence-error')),
                TextButton(
                    key: const Key('evidence-retry'),
                    onPressed: () => ref.invalidate(deliveryEvidenceProvider(orderId)),
                    child: const Text('Thử lại')),
              ]),
          data: (ev) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                InfoRow('Xác nhận bằng', confirmMethodLabel(ev.confirmation.method)),
                InfoRow('Thời điểm', _fmt(ev.confirmation.confirmedAt)),
                if ((ev.confirmation.note ?? '').isNotEmpty) InfoRow('Ghi chú', ev.confirmation.note!),
                const SizedBox(height: 8),
                if (ev.photoUrl == null)
                  const Text('Không tải được ảnh xác nhận.',
                      key: Key('evidence-no-photo'), style: TextStyle(color: AppColors.textMuted))
                else
                  ClipRRect(
                      borderRadius: BorderRadius.circular(AppRadii.button),
                      child: Image.network(ev.photoUrl!,
                          key: const Key('evidence-photo'),
                          height: 200,
                          width: double.infinity,
                          fit: BoxFit.cover,
                          loadingBuilder: (_, child, progress) =>
                              progress == null ? child : const SkeletonBox(height: 200, radius: 0),
                          errorBuilder: (_, __, ___) => const SizedBox(
                              height: 80,
                              child: Center(
                                  child: Text('Không tải được ảnh xác nhận.',
                                      key: Key('evidence-photo-error'),
                                      style: TextStyle(color: AppColors.textMuted)))))),
              ])),
    ]);
  }
}
