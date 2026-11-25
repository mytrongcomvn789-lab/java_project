/// Model cho luồng vận hành trạm (6.4): nhận kiện tại trạm gửi (4.5) và xác nhận giao
/// cho khách tại trạm nhận (4.7). Khớp DTO backend: StationMonitoringView, ReceiptContext,
/// ReceivePackageRequest, ConfirmDeliveryRequest, DeliveryConfirmationView.
library;

import '../orders/customer_order_models.dart' show OrderDetail;

DateTime? _time(Object? v) => v is String ? DateTime.tryParse(v)?.toLocal() : null;

/// Một trạm mà server cho operator thao tác (`GET /v1/stations/monitoring` với vai trò
/// STATION_OPERATOR chỉ trả các trạm được phân công).
class AssignedStation {
  const AssignedStation(
      {required this.id,
      required this.name,
      required this.status,
      this.address,
      this.capacity = 0,
      this.slotUsed = 0,
      this.connection = 'UNKNOWN'});
  final String id, name, status;

  /// Trạm tạo trước khi có cột địa chỉ thì trống; hiển thị tên trạm thay thế.
  final String? address;

  /// Số lịch giao tối đa mỗi khung giờ và số chỗ đang giữ trong khung giờ hiện tại (4.2).
  final int capacity, slotUsed;

  /// `ONLINE`, `OFFLINE` hoặc `UNKNOWN`. Backend chưa có nguồn heartbeat nên thường là UNKNOWN
  /// và app không được giả là online.
  final String connection;

  bool get isActive => status == 'ACTIVE';

  factory AssignedStation.fromJson(Map<String, dynamic> json) {
    final conn = json['connection'];
    final slot = json['slot'];
    return AssignedStation(
        id: json['id'] as String,
        name: json['name'] as String? ?? '',
        status: json['status'] as String? ?? '',
        address: json['address'] as String?,
        capacity: (json['capacity'] as num?)?.toInt() ?? 0,
        slotUsed: slot is Map ? (slot['used'] as num?)?.toInt() ?? 0 : 0,
        connection:
            conn is Map ? conn['state'] as String? ?? 'UNKNOWN' : 'UNKNOWN');
  }
}

/// Lần giao đang hiệu lực của đơn; `id` bắt buộc khi nhận kiện.
class ReceiptAttempt {
  const ReceiptAttempt(
      {required this.id, required this.attemptNo, this.slotStart, this.slotEnd});
  final String id;
  final int attemptNo;
  final DateTime? slotStart, slotEnd;

  factory ReceiptAttempt.fromJson(Map<String, dynamic> json) => ReceiptAttempt(
      id: json['id'] as String,
      attemptNo: (json['attemptNo'] as num?)?.toInt() ?? 1,
      slotStart: _time(json['slotStart']),
      slotEnd: _time(json['slotEnd']));
}

/// `GET /v1/orders/{id}/receipt-context`: đủ thông tin để nhân viên kiểm tra kiện rồi nhận.
/// `order.version` (cũng là ETag) dùng làm `If-Match`.
class ReceiptContext {
  const ReceiptContext(
      {required this.order,
      required this.senderName,
      required this.packageId,
      required this.attempt});
  final OrderDetail order;
  final String senderName, packageId;
  final ReceiptAttempt attempt;

  factory ReceiptContext.fromJson(Map<String, dynamic> json) => ReceiptContext(
      order: OrderDetail.fromJson(json['order'] as Map<String, dynamic>),
      senderName: json['senderName'] as String? ?? '',
      packageId: json['packageId'] as String,
      attempt: ReceiptAttempt.fromJson(json['attempt'] as Map<String, dynamic>));
}

/// Yêu cầu nhận kiện (`POST /v1/orders/{id}/receipts`). Là một giá trị thuần, có thể lưu vào
/// hàng đợi để 6.5 gửi lại. Người thao tác và thời điểm luôn do server lấy từ token, không gửi.
///
/// Không an toàn để replay: gửi lại sau khi đã thành công trả 412 (phiên bản cũ) hoặc 409
/// (đơn đã PREPARING). Khi mất kết quả (timeout), phải tải lại đơn để biết đã nhận chưa.
class ReceiveRequest {
  const ReceiveRequest(
      {required this.orderId,
      required this.version,
      required this.attemptId,
      required this.packageId,
      this.note});
  final String orderId, attemptId, packageId;

  /// Phiên bản đơn đang xem, gửi qua `If-Match`.
  final int version;
  final String? note;

  String get ifMatch => '"$version"';

  /// Body đúng hợp đồng; ghi chú trống thì bỏ hẳn (server từ chối trường lạ nhưng nhận thiếu).
  Map<String, dynamic> toBody() {
    final n = note?.trim();
    return {
      'attemptId': attemptId,
      'packageId': packageId,
      if (n != null && n.isNotEmpty) 'note': n,
    };
  }

  factory ReceiveRequest.fromContext(ReceiptContext c, {String? note}) =>
      ReceiveRequest(
          orderId: c.order.id,
          version: c.order.version,
          attemptId: c.attempt.id,
          packageId: c.packageId,
          note: note);
}

enum CredentialKind { otp, qr }

/// Mã nhận hàng của khách: OTP nhập tay hoặc chuỗi đọc từ mã QR. Server yêu cầu ĐÚNG MỘT
/// trong hai. `toString` che giá trị để mã không lọt vào log.
class PickupCredential {
  const PickupCredential._(this.kind, this.value);
  final CredentialKind kind;
  final String value;

  /// OTP nhập tay. Chỉ cắt khoảng trắng, không tự kiểm tra đúng sai (server quyết định).
  factory PickupCredential.otp(String raw) =>
      PickupCredential._(CredentialKind.otp, raw.trim());

  /// Chuỗi đọc từ QR (`qrPayload` của khách, dạng `challengeId.chữ-ký`).
  factory PickupCredential.qr(String raw) =>
      PickupCredential._(CredentialKind.qr, raw.trim());

  /// Kiểm tra nhanh để báo sớm khi người dùng chưa nhập đủ (không thay cho server).
  static String? validateOtp(String raw) {
    final t = raw.trim();
    if (t.isEmpty) return 'Nhập mã OTP của khách.';
    if (!RegExp(r'^[0-9]+$').hasMatch(t)) return 'OTP chỉ gồm chữ số.';
    if (t.length < 4 || t.length > 16) return 'OTP chưa đủ số chữ số.';
    return null;
  }

  /// Chuỗi từ QR có đúng hình dạng mã nhận hàng không (`uuid.chữ-ký`, không khoảng trắng,
  /// tối đa 200 ký tự như server). Dùng để bỏ qua nhanh các mã QR không liên quan; chỉ là
  /// kiểm tra hình dạng, server mới xác minh chữ ký. Trả null nếu không phải.
  static PickupCredential? fromScan(String? raw) {
    final t = raw?.trim() ?? '';
    if (t.isEmpty || t.length > 200 || RegExp(r'\s').hasMatch(t)) return null;
    if (!RegExp(r'^[0-9a-fA-F-]{36}\.[A-Za-z0-9_\-]{16,}$').hasMatch(t)) return null;
    return PickupCredential._(CredentialKind.qr, t);
  }

  /// Phần credential của body: `{otp: ...}` hoặc `{qrToken: ...}` (đúng một trường).
  Map<String, dynamic> toBody() =>
      kind == CredentialKind.otp ? {'otp': value} : {'qrToken': value};

  @override
  String toString() => 'PickupCredential($kind, ***)';
}

/// Yêu cầu xác nhận giao (`POST /v1/orders/{id}/confirmations`), giá trị thuần để 6.5 lưu và
/// gửi lại. An toàn để gửi lại với CÙNG `version`, `photoFileId` và mã: server trả lại kết quả
/// cũ kèm `Idempotent-Replayed: true`. Đổi ảnh hoặc mã khi gửi lại sẽ bị từ chối.
/// Không được lưu mã (OTP/QR) vào bộ nhớ không mã hóa.
class ConfirmRequest {
  const ConfirmRequest(
      {required this.orderId,
      required this.version,
      required this.photoFileId,
      required this.credential,
      this.note});
  final String orderId, photoFileId;
  final int version;
  final PickupCredential credential;
  final String? note;

  String get ifMatch => '"$version"';

  Map<String, dynamic> toBody() {
    final n = note?.trim();
    return {
      ...credential.toBody(),
      'photoFileId': photoFileId,
      if (n != null && n.isNotEmpty) 'note': n,
    };
  }

  @override
  String toString() =>
      'ConfirmRequest(order=$orderId, v=$version, photo=$photoFileId, credential=***)';
}

/// Yêu cầu xác nhận đã nhận hàng hoàn (`POST /v1/orders/{id}/complete-return`, 4.8):
/// RETURNING → RETURNED, chỉ operator trạm GỬI. Không replay được: gửi lại sau khi đã thành
/// công trả 412 hoặc 409, nên khi mất kết quả phải tải lại đơn để biết trạng thái thật.
class CompleteReturnRequest {
  const CompleteReturnRequest(
      {required this.orderId, required this.version, this.note});
  final String orderId;
  final int version;
  final String? note;

  String get ifMatch => '"$version"';

  /// Body tùy chọn: chỉ có `note` (server từ chối trường lạ). Ghi chú trống thì không gửi body.
  Map<String, dynamic>? toBody() {
    final n = note?.trim();
    return n == null || n.isEmpty ? null : {'note': n};
  }
}

/// Bằng chứng giao hàng (`DeliveryConfirmationView`).
class DeliveryConfirmation {
  const DeliveryConfirmation(
      {required this.id,
      required this.orderId,
      required this.orderVersion,
      required this.method,
      required this.confirmedBy,
      required this.stationId,
      required this.photoFileId,
      this.note,
      this.confirmedAt});
  final String id, orderId, method, confirmedBy, stationId, photoFileId;

  /// Phiên bản đơn SAU khi xác nhận.
  final int orderVersion;
  final String? note;
  final DateTime? confirmedAt;

  factory DeliveryConfirmation.fromJson(Map<String, dynamic> json) =>
      DeliveryConfirmation(
          id: json['id'] as String,
          orderId: json['orderId'] as String,
          orderVersion: (json['orderVersion'] as num).toInt(),
          method: json['method'] as String? ?? '',
          confirmedBy: json['confirmedBy'] as String? ?? '',
          stationId: json['stationId'] as String? ?? '',
          photoFileId: json['photoFileId'] as String? ?? '',
          note: json['note'] as String?,
          confirmedAt: _time(json['confirmedAt']));
}

/// Kết quả xác nhận giao. [replayed] = true khi server trả lại kết quả của lần gửi trước
/// (`Idempotent-Replayed: true`): đơn đã được giao rồi, không giao lần hai.
class ConfirmResult {
  const ConfirmResult(this.confirmation, {required this.replayed});
  final DeliveryConfirmation confirmation;
  final bool replayed;
}
