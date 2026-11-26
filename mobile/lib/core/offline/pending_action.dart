import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Thao tác vận hành đã làm trên máy nhưng chưa gửi được lên server (6.5).
enum PendingKind { receive, confirm, completeReturn }

/// `waiting`: sẽ tự gửi lại khi có mạng. `needsAttention`: server từ chối, người dùng phải
/// xem và quyết định (mở lại đơn hoặc bỏ yêu cầu), app không tự thử lại nữa.
enum PendingStatus { waiting, needsAttention }

/// Một thao tác trong hàng đợi. `payload` chứa đúng dữ liệu của request (có thể gồm OTP hoặc
/// mã QR) nên chỉ được ghi vào kho mã hóa; `toString` che nội dung để không lọt vào log.
class PendingAction {
  const PendingAction(
      {required this.id,
      required this.userId,
      required this.kind,
      required this.orderId,
      required this.payload,
      required this.createdAt,
      this.attempts = 0,
      this.status = PendingStatus.waiting,
      this.message});

  final String id, userId, orderId;
  final PendingKind kind;
  final Map<String, dynamic> payload;
  final DateTime createdAt;

  /// Số lần đã thử gửi mà gặp lỗi tạm thời.
  final int attempts;
  final PendingStatus status;

  /// Lý do cần người dùng xem (chỉ có khi `needsAttention`).
  final String? message;

  PendingAction copyWith(
          {int? attempts, PendingStatus? status, String? message}) =>
      PendingAction(
          id: id,
          userId: userId,
          kind: kind,
          orderId: orderId,
          payload: payload,
          createdAt: createdAt,
          attempts: attempts ?? this.attempts,
          status: status ?? this.status,
          message: message ?? this.message);

  String get kindLabel => switch (kind) {
        PendingKind.receive => 'Nhận kiện',
        PendingKind.confirm => 'Xác nhận giao',
        PendingKind.completeReturn => 'Nhận hàng hoàn',
      };

  Map<String, dynamic> toJson() => {
        'id': id,
        'userId': userId,
        'kind': kind.name,
        'orderId': orderId,
        'payload': payload,
        'createdAt': createdAt.toUtc().toIso8601String(),
        'attempts': attempts,
        'status': status.name,
        if (message != null) 'message': message,
      };

  /// Trả null nếu bản ghi hỏng hoặc thiếu trường (bỏ bản ghi đó thay vì làm sập app).
  static PendingAction? tryFromJson(Object? raw) {
    try {
      final j = raw as Map<String, dynamic>;
      final kind = PendingKind.values.byName(j['kind'] as String);
      return PendingAction(
          id: j['id'] as String,
          userId: j['userId'] as String,
          kind: kind,
          orderId: j['orderId'] as String,
          payload: Map<String, dynamic>.from(j['payload'] as Map),
          createdAt: DateTime.parse(j['createdAt'] as String),
          attempts: (j['attempts'] as num?)?.toInt() ?? 0,
          status: PendingStatus.values.byName(j['status'] as String? ?? 'waiting'),
          message: j['message'] as String?);
    } catch (_) {
      return null;
    }
  }

  @override
  String toString() => 'PendingAction($kind, order=$orderId, $status, payload=***)';
}

/// Nơi lưu hàng đợi, tách theo tài khoản để người đăng nhập sau không thấy việc của người trước.
abstract class PendingStore {
  Future<List<PendingAction>> read(String userId);
  Future<void> write(String userId, List<PendingAction> actions);
}

/// Lưu trong kho mã hóa của hệ điều hành (Keystore/Keychain), cùng nơi giữ token. OTP và mã QR
/// trong yêu cầu xác nhận giao không bao giờ nằm trong bộ nhớ thường.
class SecurePendingStore implements PendingStore {
  const SecurePendingStore();
  final FlutterSecureStorage _storage = const FlutterSecureStorage();
  static String _key(String userId) => 'smartdrone.pending.v1.$userId';

  @override
  Future<List<PendingAction>> read(String userId) async {
    final raw = await _storage.read(key: _key(userId));
    if (raw == null) return const [];
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return [
        for (final e in list)
          if (PendingAction.tryFromJson(e) case final a?)
            if (a.userId == userId) a
      ];
    } catch (_) {
      await _storage.delete(key: _key(userId));
      return const [];
    }
  }

  @override
  Future<void> write(String userId, List<PendingAction> actions) async {
    if (actions.isEmpty) {
      await _storage.delete(key: _key(userId));
      return;
    }
    await _storage.write(
        key: _key(userId), value: jsonEncode([for (final a in actions) a.toJson()]));
  }
}
