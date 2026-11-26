import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../auth/session.dart';

/// "Ổ cắm" cho các API backend CHƯA có (ETA, chuyến bay/telemetry). Model đúng theo hợp đồng đề
/// xuất trong docs/05-api/mobile-ui-required-apis.md; khi backend ra thật chỉ cần đúng JSON, giao
/// diện không phải sửa. Khi tuyến trả 404/405/501 (chưa cài), dữ liệu mẫu được dùng và PHẢI hiện nhãn
/// "Demo" (xem [Sourced.isDemo]).

/// Kết quả kèm nguồn: dữ liệu thật hay dữ liệu mẫu.
class Sourced<T> {
  const Sourced(this.data, {required this.isDemo});
  final T data;
  final bool isDemo;
}

/// `GET /v1/orders/{id}/eta`.
class OrderEta {
  const OrderEta(
      {required this.etaAt,
      required this.remainingSeconds,
      this.remainingKm,
      this.source});
  final DateTime etaAt;
  final int remainingSeconds;
  final double? remainingKm;

  /// `AI` hoặc `RULE`.
  final String? source;

  int get remainingMinutes => (remainingSeconds / 60).ceil();

  factory OrderEta.fromJson(Map<String, dynamic> json) => OrderEta(
      etaAt: DateTime.parse(json['etaAt'] as String).toLocal(),
      remainingSeconds: (json['remainingSeconds'] as num).toInt(),
      remainingKm: (json['remainingKm'] as num?)?.toDouble(),
      source: json['source'] as String?);
}

class FlightTelemetry {
  const FlightTelemetry(
      {required this.speedKmh, required this.altitudeM, required this.batteryPercent});
  final double speedKmh, altitudeM;
  final int batteryPercent;

  factory FlightTelemetry.fromJson(Map<String, dynamic> json) => FlightTelemetry(
      speedKmh: (json['speedKmh'] as num).toDouble(),
      altitudeM: (json['altitudeM'] as num).toDouble(),
      batteryPercent: (json['batteryPercent'] as num).toInt());
}

/// `GET /v1/orders/{id}/flight`: tiến độ giữa hai trạm và số đo của drone.
class Flight {
  const Flight({required this.progress, required this.telemetry, this.droneId});

  /// 0.0 đến 1.0 (backend trả `progressPercent` 0 đến 100).
  final double progress;
  final FlightTelemetry telemetry;
  final String? droneId;

  factory Flight.fromJson(Map<String, dynamic> json) => Flight(
      progress: ((json['progressPercent'] as num).toDouble() / 100).clamp(0.0, 1.0),
      telemetry: FlightTelemetry.fromJson(json['telemetry'] as Map<String, dynamic>),
      droneId: json['droneId'] as String?);
}

/// Dữ liệu mẫu ổn định theo mã đơn (cùng một đơn luôn ra cùng số, để test và demo dễ lặp lại).
abstract final class DemoFlightData {
  static int _seed(String orderId) =>
      orderId.codeUnits.fold(7, (a, b) => (a * 31 + b) & 0x7fffffff);

  static OrderEta eta(String orderId, {DateTime? now}) {
    final minutes = 6 + _seed(orderId) % 15; // 6..20 phút
    final base = now ?? DateTime.now();
    return OrderEta(
        etaAt: base.add(Duration(minutes: minutes)),
        remainingSeconds: minutes * 60,
        remainingKm: minutes * 0.75,
        source: 'DEMO');
  }

  static Flight flight(String orderId) {
    final s = _seed(orderId);
    return Flight(
        progress: (30 + s % 50) / 100, // 30..79%
        telemetry: FlightTelemetry(
            speedKmh: (40 + s % 21).toDouble(), // 40..60
            altitudeM: (80 + s % 71).toDouble(), // 80..150
            batteryPercent: 60 + s % 36), // 60..95
        droneId: 'DEMO-${(s % 90) + 10}');
  }
}

/// Tuyến backend chưa cài: dùng dữ liệu mẫu thay vì báo lỗi.
///
/// 404/405/501 là tuyến chưa có controller. 403 cũng được tính vì backend mặc định TỪ CHỐI mọi
/// tuyến chưa khai báo trong `RbacPolicy` (đã gặp với `/flight`); khách xem telemetry của chính
/// đơn mình thì không thể là bị cấm thật. Dữ liệu mẫu luôn kèm nhãn "Demo" nên không bị hiểu nhầm.
bool _notImplemented(ApiFailure f) =>
    f.status == 403 || f.status == 404 || f.status == 405 || f.status == 501;

class FlightApi {
  FlightApi(this._dio, {this.demoFallback = true});
  final Dio _dio;

  /// Tắt trong test hoặc khi muốn thấy lỗi thật của backend.
  final bool demoFallback;

  Future<Sourced<OrderEta>> eta(String orderId) => _withFallback(
      () => guardApi(() async {
            final res = await _dio.get<Map<String, dynamic>>('/v1/orders/$orderId/eta');
            return OrderEta.fromJson(res.data!);
          }),
      () => DemoFlightData.eta(orderId));

  Future<Sourced<Flight>> flight(String orderId) => _withFallback(
      () => guardApi(() async {
            final res =
                await _dio.get<Map<String, dynamic>>('/v1/orders/$orderId/flight');
            return Flight.fromJson(res.data!);
          }),
      () => DemoFlightData.flight(orderId));

  Future<Sourced<T>> _withFallback<T>(Future<T> Function() remote, T Function() demo) async {
    try {
      return Sourced(await remote(), isDemo: false);
    } on ApiFailure catch (f) {
      if (demoFallback && _notImplemented(f)) return Sourced(demo(), isDemo: true);
      rethrow;
    }
  }
}

final flightApiProvider =
    Provider<FlightApi>((ref) => FlightApi(ref.read(sessionProvider).api));

/// ETA của một đơn. Tự hủy khi không còn màn nào xem.
final etaProvider = FutureProvider.autoDispose.family<Sourced<OrderEta>, String>(
    (ref, orderId) => ref.read(flightApiProvider).eta(orderId));

final flightProvider = FutureProvider.autoDispose.family<Sourced<Flight>, String>(
    (ref, orderId) => ref.read(flightApiProvider).flight(orderId));
