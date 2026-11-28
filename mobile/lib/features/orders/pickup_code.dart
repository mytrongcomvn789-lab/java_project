import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../auth/session.dart';

/// Mã nhận hàng của khách (`GET /v1/orders/{id}/pickup-code`, chỉ khi đơn ở trạng thái AWAITING_PICKUP).
/// Mã chỉ sống trong bộ nhớ: không ghi đĩa, không ghi log ([toString] không lộ mã).
class PickupCode {
  const PickupCode(
      {required this.orderId,
      required this.otp,
      required this.qrPayload,
      required this.expiresAt});
  final String orderId, otp, qrPayload;
  final DateTime expiresAt;

  bool expiredAt(DateTime now) => !expiresAt.isAfter(now);

  /// "123456" -> "123 456" cho dễ đọc.
  String get otpSpaced => otp.length == 6 ? '${otp.substring(0, 3)} ${otp.substring(3)}' : otp;

  factory PickupCode.fromJson(Map<String, dynamic> json) => PickupCode(
      orderId: json['orderId'] as String,
      otp: json['otp'] as String,
      qrPayload: json['qrPayload'] as String,
      expiresAt: DateTime.parse(json['expiresAt'] as String).toLocal());

  @override
  String toString() => 'PickupCode(orderId: $orderId, expiresAt: $expiresAt)';
}

/// Lấy mã hiện hành; server dùng lại mã còn hạn, hết hạn thì cấp mới. autoDispose: rời màn là bỏ mã.
final pickupCodeProvider = FutureProvider.autoDispose.family<PickupCode, String>((ref, orderId) {
  final dio = ref.read(sessionProvider).api;
  return guardApi(() async {
    final res = await dio.get<Map<String, dynamic>>('/v1/orders/$orderId/pickup-code');
    return PickupCode.fromJson(res.data!);
  });
});
