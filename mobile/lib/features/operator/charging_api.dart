import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../auth/session.dart';

/// Trạng thái hiển thị của thiết bị sạc (`ChargerState` của backend).
enum ChargerState { empty, charging, full, maintenance, unknown }

ChargerState _state(Object? v) => switch (v) {
      'EMPTY' => ChargerState.empty,
      'CHARGING' => ChargerState.charging,
      'FULL' => ChargerState.full,
      'MAINTENANCE' => ChargerState.maintenance,
      _ => ChargerState.unknown,
    };

DateTime? _time(Object? v) => v is String ? DateTime.tryParse(v)?.toLocal() : null;

class DroneRef {
  const DroneRef(this.id, this.code);
  final String id, code;
  static DroneRef? from(Object? j) => j is Map<String, dynamic>
      ? DroneRef(j['id'] as String, j['code'] as String? ?? '')
      : null;
}

/// Một thiết bị sạc và drone đang cắm (nếu có). Số liệu sạc là GIẢ LẬP theo công thức của backend
/// (`source = SIMULATED`), giao diện phải ghi rõ điều đó.
class Charger {
  const Charger(
      {required this.id,
      required this.code,
      required this.state,
      required this.online,
      this.lastHeartbeatAt,
      this.drone,
      this.batteryPercent,
      this.startPercent,
      this.targetPercent,
      this.chargedMinutes,
      this.remainingMinutes,
      this.readyAt,
      this.temperatureC,
      this.canRelease = false});
  final String id, code;
  final ChargerState state;
  final bool online, canRelease;
  final DateTime? lastHeartbeatAt, readyAt;
  final DroneRef? drone;
  final int? batteryPercent, startPercent, targetPercent;
  final int? chargedMinutes, remainingMinutes;
  final double? temperatureC;

  factory Charger.fromJson(Map<String, dynamic> j) => Charger(
      id: j['id'] as String,
      code: j['code'] as String? ?? '',
      state: _state(j['state']),
      online: j['online'] == true,
      lastHeartbeatAt: _time(j['lastHeartbeatAt']),
      drone: DroneRef.from(j['drone']),
      batteryPercent: (j['batteryPercent'] as num?)?.toInt(),
      startPercent: (j['startPercent'] as num?)?.toInt(),
      targetPercent: (j['targetPercent'] as num?)?.toInt(),
      chargedMinutes: (j['chargedMinutes'] as num?)?.toInt(),
      remainingMinutes: (j['remainingMinutes'] as num?)?.toInt(),
      readyAt: _time(j['readyAt']),
      temperatureC: (j['temperatureC'] as num?)?.toDouble(),
      canRelease: j['canRelease'] == true);
}

class ChargerSummary {
  const ChargerSummary(
      {this.total = 0, this.empty = 0, this.charging = 0, this.full = 0, this.maintenance = 0, this.online = 0});
  final int total, empty, charging, full, maintenance, online;

  factory ChargerSummary.fromJson(Map<String, dynamic> j) {
    int n(String k) => (j[k] as num?)?.toInt() ?? 0;
    return ChargerSummary(
        total: n('total'),
        empty: n('empty'),
        charging: n('charging'),
        full: n('full'),
        maintenance: n('maintenance'),
        online: n('online'));
  }
}

/// Drone đang đỗ tại trạm, chưa cắm sạc.
class IdleDrone {
  const IdleDrone(this.id, this.code, this.batteryPercent);
  final String id, code;
  final int batteryPercent;
}

class StationChargers {
  const StationChargers(
      {required this.stationId,
      required this.stationName,
      required this.stationStatus,
      required this.summary,
      required this.chargers,
      required this.idleDrones,
      this.nextSlotAt,
      this.source = ''});
  final String stationId, stationName, stationStatus, source;
  final ChargerSummary summary;
  final List<Charger> chargers;
  final List<IdleDrone> idleDrones;

  /// Lúc dự kiến có chỗ nhận drone tiếp theo; null nghĩa là đang còn thiết bị trống.
  final DateTime? nextSlotAt;

  bool get simulated => source == 'SIMULATED';

  factory StationChargers.fromJson(Map<String, dynamic> j) => StationChargers(
      stationId: j['stationId'] as String,
      stationName: j['stationName'] as String? ?? '',
      stationStatus: j['stationStatus'] as String? ?? '',
      summary: ChargerSummary.fromJson((j['summary'] as Map<String, dynamic>?) ?? const {}),
      chargers: [
        for (final e in (j['chargers'] as List<dynamic>? ?? const []))
          Charger.fromJson(e as Map<String, dynamic>)
      ],
      idleDrones: [
        for (final e in (j['idleDrones'] as List<dynamic>? ?? const []))
          IdleDrone((e as Map<String, dynamic>)['id'] as String, e['code'] as String? ?? '',
              (e['batteryPercent'] as num?)?.toInt() ?? 0)
      ],
      nextSlotAt: _time(j['nextSlotAt']),
      source: j['source'] as String? ?? '');
}

/// Kế hoạch điều phối drone của một đơn. Mọi mốc thời gian là ước tính theo công thức.
class DispatchPlan {
  const DispatchPlan(
      {required this.orderId,
      required this.stage,
      required this.assigned,
      this.waitMinutes,
      this.waitReason,
      this.message,
      this.plannedDepartureAt,
      this.plannedArrivalAt,
      this.droneCode,
      this.originStationName,
      this.reservedChargerCode});
  final String orderId, stage;
  final bool assigned;
  final int? waitMinutes;
  final String? waitReason, message, droneCode, originStationName, reservedChargerCode;
  final DateTime? plannedDepartureAt, plannedArrivalAt;

  factory DispatchPlan.fromJson(Map<String, dynamic> j) => DispatchPlan(
      orderId: j['orderId'] as String,
      stage: j['stage'] as String? ?? 'NONE',
      assigned: j['assigned'] == true,
      waitMinutes: (j['waitMinutes'] as num?)?.toInt(),
      waitReason: j['waitReason'] as String?,
      message: j['message'] as String?,
      plannedDepartureAt: _time(j['plannedDepartureAt']),
      plannedArrivalAt: _time(j['plannedArrivalAt']),
      droneCode: j['droneCode'] as String?,
      originStationName: j['originStationName'] as String?,
      reservedChargerCode: j['reservedChargerCode'] as String?);
}

class FleetDrone {
  const FleetDrone(this.id, this.code, this.status, this.batteryPercent, this.stationName);
  final String id, code, status, stationName;
  final int batteryPercent;
}

class DispatchRow {
  const DispatchRow(
      {required this.orderId,
      required this.orderStatus,
      required this.pickupStationName,
      required this.dropoffStationName,
      required this.plan});
  final String orderId, orderStatus, pickupStationName, dropoffStationName;
  final DispatchPlan plan;
  String get shortCode => orderId.length >= 8 ? orderId.substring(0, 8).toUpperCase() : orderId;
}

class DispatchBoard {
  const DispatchBoard({required this.fleet, required this.orders, this.source = ''});
  final List<FleetDrone> fleet;
  final List<DispatchRow> orders;
  final String source;

  factory DispatchBoard.fromJson(Map<String, dynamic> j) => DispatchBoard(
      fleet: [
        for (final e in (j['fleet'] as List<dynamic>? ?? const []))
          FleetDrone(
              (e as Map<String, dynamic>)['id'] as String,
              e['code'] as String? ?? '',
              e['status'] as String? ?? '',
              (e['batteryPercent'] as num?)?.toInt() ?? 0,
              e['stationName'] as String? ?? '')
      ],
      orders: [
        for (final e in (j['orders'] as List<dynamic>? ?? const []))
          DispatchRow(
              orderId: (e as Map<String, dynamic>)['orderId'] as String,
              orderStatus: e['orderStatus'] as String? ?? '',
              pickupStationName: e['pickupStationName'] as String? ?? '',
              dropoffStationName: e['dropoffStationName'] as String? ?? '',
              plan: DispatchPlan.fromJson((e['dispatch'] as Map<String, dynamic>?) ?? {'orderId': e['orderId']}))
      ],
      source: j['source'] as String? ?? '');
}

/// REST cho thiết bị sạc, drone và điều phối của nhân viên trạm. Quyền do backend quyết định (chỉ nhân
/// viên của trạm được phân công; trạm khác trả 404). Số liệu sạc là giả lập.
class ChargingApi {
  ChargingApi(this._dio);
  final Dio _dio;

  Future<StationChargers> stationChargers(String stationId) => guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/stations/$stationId/chargers');
        return StationChargers.fromJson(res.data!);
      });

  /// Cho drone đã sạc đầy rời thiết bị (chỉ khi thiết bị ở trạng thái đầy).
  Future<StationChargers> release(String stationId, String chargerId) => guardApi(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/stations/$stationId/chargers/$chargerId/release');
        return StationChargers.fromJson(res.data!);
      });

  /// Bật/tắt bảo trì thiết bị (không áp dụng khi còn drone cắm).
  Future<StationChargers> setMaintenance(String stationId, String chargerId, bool maintenance) =>
      guardApi(() async {
        final res = await _dio.patch<Map<String, dynamic>>(
            '/v1/stations/$stationId/chargers/$chargerId/maintenance',
            data: {'maintenance': maintenance});
        return StationChargers.fromJson(res.data!);
      });

  Future<DispatchBoard> board() => guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/dispatch/board');
        return DispatchBoard.fromJson(res.data!);
      });

  /// Phân công hoặc tính lại drone cho đơn đã nhận kiện tại trạm gửi.
  Future<DispatchPlan> assign(String orderId) => guardApi(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/orders/$orderId/dispatch-plan');
        return DispatchPlan.fromJson(res.data!);
      });
}

final chargingApiProvider = Provider<ChargingApi>((ref) => ChargingApi(ref.read(sessionProvider).api));
