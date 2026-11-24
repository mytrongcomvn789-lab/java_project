import 'dart:convert';

/// Vietnamese status labels, identical to the backend `OrderStatus.label()`.
/// Used only to render a realtime event instantly; REST data overwrites it.
const statusLabels = <String, String>{
  'PENDING': 'Chờ duyệt',
  'APPROVED': 'Đã duyệt',
  'REJECTED': 'Bị từ chối',
  'SCHEDULED': 'Đã lên lịch',
  'PREPARING': 'Đang chuẩn bị',
  'IN_TRANSIT': 'Đang giao',
  'AWAITING_PICKUP': 'Chờ nhận hàng',
  'DELIVERED': 'Đã giao',
  'CANCELLED': 'Đã hủy',
  'FAILED': 'Giao thất bại',
  'RETURNING': 'Đang hoàn hàng',
  'RETURNED': 'Đã hoàn hàng',
};
const terminalStatuses = {
  'REJECTED',
  'DELIVERED',
  'CANCELLED',
  'RETURNED',
};

String labelOfStatus(String status) => statusLabels[status] ?? status;

DateTime? _time(Object? v) => v is String ? DateTime.tryParse(v)?.toLocal() : null;

/// `ORDER_STATUS_CHANGED` envelope pushed on `/topic/orders/{orderId}`.
class OrderRealtimeEvent {
  const OrderRealtimeEvent(
      {required this.eventId,
      required this.orderId,
      required this.event,
      required this.fromStatus,
      required this.status,
      required this.version,
      required this.occurredAt});
  final String eventId, orderId, event, status;
  final String? fromStatus;
  final int version;
  final DateTime? occurredAt;

  /// Returns null for anything that is not a well-formed status event, so a
  /// malformed or unknown-schema frame is ignored instead of crashing the UI.
  static OrderRealtimeEvent? tryParse(String body) {
    try {
      final json = jsonDecode(body);
      if (json is! Map<String, dynamic>) return null;
      if (json['type'] != 'ORDER_STATUS_CHANGED') return null;
      final eventId = json['eventId'], orderId = json['orderId'];
      final status = json['status'], version = json['version'];
      if (eventId is! String ||
          orderId is! String ||
          status is! String ||
          version is! int) {
        return null;
      }
      return OrderRealtimeEvent(
          eventId: eventId,
          orderId: orderId,
          event: json['event'] as String? ?? '',
          fromStatus: json['fromStatus'] as String?,
          status: status,
          version: version,
          occurredAt: _time(json['occurredAt']));
    } on FormatException {
      return null;
    }
  }
}

class TimelineEntry {
  const TimelineEntry(
      {required this.eventId,
      required this.version,
      required this.toStatus,
      required this.label,
      this.fromStatus,
      this.action,
      this.occurredAt,
      this.fromRealtime = false});
  final String eventId, toStatus, label;
  final String? fromStatus, action;
  final int version;
  final DateTime? occurredAt;

  /// True until REST confirms the entry (it then replaces this one).
  final bool fromRealtime;

  factory TimelineEntry.fromJson(Map<String, dynamic> json) => TimelineEntry(
      eventId: json['eventId'] as String,
      version: (json['orderVersion'] as num).toInt(),
      fromStatus: json['fromStatus'] as String?,
      toStatus: json['toStatus'] as String,
      label: json['toStatusLabel'] as String? ??
          labelOfStatus(json['toStatus'] as String),
      action: json['action'] as String?,
      occurredAt: _time(json['occurredAt']));

  factory TimelineEntry.fromEvent(OrderRealtimeEvent e) => TimelineEntry(
      eventId: e.eventId,
      version: e.version,
      fromStatus: e.fromStatus,
      toStatus: e.status,
      label: labelOfStatus(e.status),
      action: e.event,
      occurredAt: e.occurredAt,
      fromRealtime: true);
}

class StationPoint {
  const StationPoint(this.name);
  final String name;
  static StationPoint? from(Object? json) => json is Map<String, dynamic>
      ? StationPoint(json['name'] as String? ?? '')
      : null;
}

/// Subset of `GET /v1/orders/{id}/tracking` the mobile timeline needs.
class TrackingSnapshot {
  const TrackingSnapshot(
      {required this.orderId,
      required this.version,
      required this.status,
      required this.statusLabel,
      required this.terminal,
      required this.timeline,
      this.pickup,
      this.dropoff});
  final String orderId, status, statusLabel;
  final int version;
  final bool terminal;
  final List<TimelineEntry> timeline;
  final StationPoint? pickup, dropoff;

  factory TrackingSnapshot.fromJson(Map<String, dynamic> json) =>
      TrackingSnapshot(
          orderId: json['orderId'] as String,
          version: (json['version'] as num).toInt(),
          status: json['status'] as String,
          statusLabel: json['statusLabel'] as String? ??
              labelOfStatus(json['status'] as String),
          terminal: json['terminal'] as bool? ?? false,
          pickup: StationPoint.from(json['pickup']),
          dropoff: StationPoint.from(json['dropoff']),
          timeline: [
            for (final e in (json['timeline'] as List<dynamic>? ?? const []))
              TimelineEntry.fromJson(e as Map<String, dynamic>)
          ]);
}

class OrderListItem {
  const OrderListItem(
      {required this.id,
      required this.status,
      required this.statusLabel,
      required this.recipientName,
      this.createdAt});
  final String id, status, statusLabel, recipientName;
  final DateTime? createdAt;

  String get shortCode => id.length >= 8 ? id.substring(0, 8).toUpperCase() : id;

  factory OrderListItem.fromJson(Map<String, dynamic> json) => OrderListItem(
      id: json['id'] as String,
      status: json['status'] as String,
      statusLabel:
          json['statusLabel'] as String? ?? labelOfStatus(json['status'] as String),
      recipientName: json['recipientName'] as String? ?? '',
      createdAt: _time(json['createdAt']));
}

/// One element of `GET /v1/notifications` / `NOTIFICATION_CREATED.notification`.
class AppNotification {
  const AppNotification(
      {required this.id,
      required this.eventId,
      required this.orderId,
      required this.title,
      required this.body,
      required this.createdAt,
      this.readAt});
  final String id, eventId, orderId, title, body;
  final DateTime? createdAt, readAt;
  bool get unread => readAt == null;

  AppNotification markRead() => AppNotification(
      id: id,
      eventId: eventId,
      orderId: orderId,
      title: title,
      body: body,
      createdAt: createdAt,
      readAt: readAt ?? DateTime.now());

  factory AppNotification.fromJson(Map<String, dynamic> json) =>
      AppNotification(
          id: json['id'] as String,
          eventId: json['eventId'] as String,
          orderId: json['orderId'] as String,
          title: json['title'] as String? ?? '',
          body: json['body'] as String? ?? '',
          createdAt: _time(json['createdAt']),
          readAt: _time(json['readAt']));

  /// Envelope `{schemaVersion,type:"NOTIFICATION_CREATED",notification}`.
  static AppNotification? tryParseEnvelope(String body) {
    try {
      final json = jsonDecode(body);
      if (json is! Map<String, dynamic> ||
          json['type'] != 'NOTIFICATION_CREATED') {
        return null;
      }
      final n = json['notification'];
      return n is Map<String, dynamic> ? AppNotification.fromJson(n) : null;
    } on FormatException {
      return null;
    } on TypeError {
      return null;
    }
  }
}
