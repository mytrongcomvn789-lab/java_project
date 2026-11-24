/// Model cho luồng tạo/xem/hủy đơn của khách (task 6.2). Khớp DTO backend:
/// OrderSummary, OrderDetail, OrderRequest, AddressView, StationView, UploadTicketResponse.
/// Phần theo dõi realtime (6.3) nằm ở order_models.dart.
library;

import 'order_models.dart' show labelOfStatus;

DateTime? _time(Object? v) => v is String ? DateTime.tryParse(v)?.toLocal() : null;

/// Chỉ đơn PENDING mới được khách hủy (nguồn quyết định vẫn là server;
/// giá trị này chỉ để quyết định có HIỆN nút hay không).
bool canCancelStatus(String status) => status == 'PENDING';

class OrderSummary {
  const OrderSummary(
      {required this.id,
      required this.status,
      required this.statusLabel,
      required this.pickupStationId,
      required this.dropoffStationId,
      required this.recipientName,
      required this.recipientAddressLine,
      this.packageDescription,
      this.packageWeightGrams,
      this.createdAt,
      this.updatedAt});
  final String id, status, statusLabel;
  final String pickupStationId, dropoffStationId;
  final String recipientName, recipientAddressLine;
  final String? packageDescription;
  final int? packageWeightGrams;
  final DateTime? createdAt, updatedAt;

  String get shortCode => id.length >= 8 ? id.substring(0, 8).toUpperCase() : id;

  factory OrderSummary.fromJson(Map<String, dynamic> json) => OrderSummary(
      id: json['id'] as String,
      status: json['status'] as String,
      statusLabel:
          json['statusLabel'] as String? ?? labelOfStatus(json['status'] as String),
      pickupStationId: json['pickupStationId'] as String? ?? '',
      dropoffStationId: json['dropoffStationId'] as String? ?? '',
      recipientName: json['recipientName'] as String? ?? '',
      recipientAddressLine: json['recipientAddressLine'] as String? ?? '',
      packageDescription: json['packageDescription'] as String?,
      packageWeightGrams: (json['packageWeightGrams'] as num?)?.toInt(),
      createdAt: _time(json['createdAt']),
      updatedAt: _time(json['updatedAt']));
}

class OrderPage {
  const OrderPage(
      {required this.items,
      required this.page,
      required this.pageSize,
      required this.total});
  final List<OrderSummary> items;
  final int page, pageSize, total;

  /// Còn trang sau hay không (dựa vào tổng số, không đoán theo độ dài trang).
  bool get hasMore => page * pageSize < total;

  factory OrderPage.fromJson(Map<String, dynamic> json) => OrderPage(
      items: [
        for (final e in (json['items'] as List<dynamic>? ?? const []))
          OrderSummary.fromJson(e as Map<String, dynamic>)
      ],
      page: (json['page'] as num?)?.toInt() ?? 1,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 0,
      total: (json['total'] as num?)?.toInt() ?? 0);
}

class OrderRecipient {
  const OrderRecipient(
      {required this.name,
      required this.phone,
      required this.addressLine,
      required this.latitude,
      required this.longitude});
  final String name, phone, addressLine;
  final double latitude, longitude;

  factory OrderRecipient.fromJson(Map<String, dynamic> json) => OrderRecipient(
      name: json['name'] as String? ?? '',
      phone: json['phone'] as String? ?? '',
      addressLine: json['addressLine'] as String? ?? '',
      latitude: (json['latitude'] as num?)?.toDouble() ?? 0,
      longitude: (json['longitude'] as num?)?.toDouble() ?? 0);
}

class PackageInfo {
  const PackageInfo(
      {required this.description,
      required this.weightGrams,
      required this.lengthCm,
      required this.widthCm,
      required this.heightCm,
      required this.photoFileIds});
  final String description;
  final int weightGrams;
  final double lengthCm, widthCm, heightCm;
  final List<String> photoFileIds;

  factory PackageInfo.fromJson(Map<String, dynamic> json) => PackageInfo(
      description: json['description'] as String? ?? '',
      weightGrams: (json['weightGrams'] as num?)?.toInt() ?? 0,
      lengthCm: (json['lengthCm'] as num?)?.toDouble() ?? 0,
      widthCm: (json['widthCm'] as num?)?.toDouble() ?? 0,
      heightCm: (json['heightCm'] as num?)?.toDouble() ?? 0,
      photoFileIds: [
        for (final e in (json['photoFileIds'] as List<dynamic>? ?? const []))
          e as String
      ]);
}

class OrderHistoryEntry {
  const OrderHistoryEntry(
      {required this.eventId,
      required this.orderVersion,
      required this.toStatus,
      this.fromStatus,
      this.reason,
      this.occurredAt});
  final String eventId, toStatus;
  final String? fromStatus, reason;
  final int orderVersion;
  final DateTime? occurredAt;

  String get toLabel => labelOfStatus(toStatus);

  factory OrderHistoryEntry.fromJson(Map<String, dynamic> json) =>
      OrderHistoryEntry(
          eventId: json['eventId'] as String,
          orderVersion: (json['orderVersion'] as num?)?.toInt() ?? 0,
          fromStatus: json['fromStatus'] as String?,
          toStatus: json['toStatus'] as String,
          reason: json['reason'] as String?,
          occurredAt: _time(json['occurredAt']));
}

/// Chi tiết đơn + `version` (cũng là ETag) dùng cho `If-Match` khi hủy.
class OrderDetail {
  const OrderDetail(
      {required this.id,
      required this.version,
      required this.status,
      required this.statusLabel,
      required this.pickupStationId,
      required this.dropoffStationId,
      required this.recipient,
      required this.package,
      required this.history,
      this.createdAt,
      this.updatedAt});
  final String id, status, statusLabel;
  final String pickupStationId, dropoffStationId;
  final int version;
  final OrderRecipient recipient;
  final PackageInfo package;
  final List<OrderHistoryEntry> history;
  final DateTime? createdAt, updatedAt;

  String get shortCode => id.length >= 8 ? id.substring(0, 8).toUpperCase() : id;
  bool get canCancel => canCancelStatus(status);

  /// Dùng khi hủy xong: server trả OrderView (không có history) nên giữ
  /// lịch sử cũ và cập nhật trạng thái/phiên bản.
  OrderDetail withView(OrderDetail view) => OrderDetail(
      id: view.id,
      version: view.version,
      status: view.status,
      statusLabel: view.statusLabel,
      pickupStationId: view.pickupStationId,
      dropoffStationId: view.dropoffStationId,
      recipient: view.recipient,
      package: view.package,
      history: history,
      createdAt: view.createdAt,
      updatedAt: view.updatedAt);

  factory OrderDetail.fromJson(Map<String, dynamic> json) => OrderDetail(
      id: json['id'] as String,
      version: (json['version'] as num).toInt(),
      status: json['status'] as String,
      statusLabel:
          json['statusLabel'] as String? ?? labelOfStatus(json['status'] as String),
      pickupStationId: json['pickupStationId'] as String? ?? '',
      dropoffStationId: json['dropoffStationId'] as String? ?? '',
      recipient:
          OrderRecipient.fromJson(json['recipient'] as Map<String, dynamic>),
      package: PackageInfo.fromJson(json['package'] as Map<String, dynamic>),
      history: [
        for (final e in (json['history'] as List<dynamic>? ?? const []))
          OrderHistoryEntry.fromJson(e as Map<String, dynamic>)
      ],
      createdAt: _time(json['createdAt']),
      updatedAt: _time(json['updatedAt']));
}

class Address {
  const Address(
      {required this.id,
      required this.label,
      required this.recipientName,
      required this.phone,
      required this.addressLine,
      required this.latitude,
      required this.longitude});
  final String id, label, recipientName, phone, addressLine;
  final double latitude, longitude;

  factory Address.fromJson(Map<String, dynamic> json) => Address(
      id: json['id'] as String,
      label: json['label'] as String? ?? '',
      recipientName: json['recipientName'] as String? ?? '',
      phone: json['phone'] as String? ?? '',
      addressLine: json['addressLine'] as String? ?? '',
      latitude: (json['latitude'] as num?)?.toDouble() ?? 0,
      longitude: (json['longitude'] as num?)?.toDouble() ?? 0);
}

class AddressPage {
  const AddressPage(
      {required this.items,
      required this.page,
      required this.pageSize,
      required this.total});
  final List<Address> items;
  final int page, pageSize, total;
  bool get hasMore => page * pageSize < total;

  factory AddressPage.fromJson(Map<String, dynamic> json) => AddressPage(
      items: [
        for (final e in (json['items'] as List<dynamic>? ?? const []))
          Address.fromJson(e as Map<String, dynamic>)
      ],
      page: (json['page'] as num?)?.toInt() ?? 1,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 0,
      total: (json['total'] as num?)?.toInt() ?? 0);
}

/// Dữ liệu gửi lên khi thêm địa chỉ (AddressInput). Server kiểm tra lại mọi ràng buộc.
class AddressInput {
  const AddressInput(
      {required this.label,
      required this.recipientName,
      required this.phone,
      required this.addressLine,
      required this.latitude,
      required this.longitude});
  final String label, recipientName, phone, addressLine;
  final double latitude, longitude;

  Map<String, dynamic> toJson() => {
        'label': label,
        'recipientName': recipientName,
        'phone': phone,
        'addressLine': addressLine,
        'latitude': latitude,
        'longitude': longitude,
      };
}

class Station {
  const Station(
      {required this.id,
      required this.name,
      required this.latitude,
      required this.longitude,
      required this.status});
  final String id, name, status;
  final double latitude, longitude;

  /// Chỉ trạm ACTIVE mới chọn làm trạm gửi/nhận được (server vẫn kiểm tra lại).
  bool get isActive => status == 'ACTIVE';

  factory Station.fromJson(Map<String, dynamic> json) => Station(
      id: json['id'] as String,
      name: json['name'] as String? ?? '',
      status: json['status'] as String? ?? '',
      latitude: (json['latitude'] as num?)?.toDouble() ?? 0,
      longitude: (json['longitude'] as num?)?.toDouble() ?? 0);
}

/// Nội dung tạo đơn (OrderRequest). Không chứa trạng thái/chủ đơn: server từ chối trường thừa.
class CreateOrderInput {
  const CreateOrderInput(
      {required this.pickupStationId,
      required this.dropoffStationId,
      required this.recipientAddressId,
      required this.description,
      required this.weightGrams,
      required this.lengthCm,
      required this.widthCm,
      required this.heightCm,
      this.photoFileIds = const []});
  final String pickupStationId, dropoffStationId, recipientAddressId;
  final String description;
  final int weightGrams;
  final double lengthCm, widthCm, heightCm;
  final List<String> photoFileIds;

  Map<String, dynamic> toJson() => {
        'pickupStationId': pickupStationId,
        'dropoffStationId': dropoffStationId,
        'recipientAddressId': recipientAddressId,
        'package': {
          'description': description,
          'weightGrams': weightGrams,
          'lengthCm': lengthCm,
          'widthCm': widthCm,
          'heightCm': heightCm,
          'photoFileIds': photoFileIds,
        },
      };
}

/// Kết quả tạo đơn. [replayed] = true khi server trả lại đơn cũ của cùng
/// Idempotency-Key (không tạo thêm).
class CreatedOrder {
  const CreatedOrder(this.order, {required this.replayed});
  final OrderDetail order;
  final bool replayed;
}

/// Quyền tải ảnh lên có hạn do `POST /v1/files/upload-tickets` cấp.
class UploadTicket {
  const UploadTicket(
      {required this.fileId,
      required this.url,
      required this.method,
      required this.fileFieldName,
      required this.fields,
      required this.maxSizeBytes});
  final String fileId, url, method, fileFieldName;
  final Map<String, String> fields;
  final int maxSizeBytes;

  factory UploadTicket.fromJson(Map<String, dynamic> json) {
    final upload = json['upload'] as Map<String, dynamic>;
    return UploadTicket(
        fileId: json['fileId'] as String,
        url: upload['url'] as String,
        method: upload['method'] as String? ?? 'POST',
        fileFieldName: upload['fileFieldName'] as String? ?? 'file',
        fields: {
          for (final e in (upload['fields'] as Map<String, dynamic>? ?? const {}).entries)
            e.key: '${e.value}'
        },
        maxSizeBytes: (json['maxSizeBytes'] as num?)?.toInt() ?? 0);
  }
}
