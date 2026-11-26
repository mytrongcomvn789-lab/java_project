import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../auth/session.dart';
import 'ai_errors.dart';
import 'ai_models.dart';

/// Thời gian chờ riêng cho AI (chậm hơn API thường). Vẫn có giới hạn.
const aiReceiveTimeout = Duration(seconds: 30);

/// REST cho `/v1/ai/**` (7.6) dùng chung Dio có JWT của phiên. Mọi lỗi được ném dưới dạng
/// [ApiFailure]; hủy chủ động ném [AiCanceled]. Chỉ gọi backend, không gọi nhà cung cấp AI.
class AiApi {
  AiApi(this._dio);
  final Dio _dio;

  Future<T> _call<T>(CancelToken? cancel, Future<T> Function(Options) run) async {
    try {
      return await run(Options(receiveTimeout: aiReceiveTimeout));
    } on DioException catch (e) {
      if (cancel?.isCancelled ?? false) throw const AiCanceled();
      throw toApiFailure(e);
    } on TypeError {
      throw const ApiFailure(
          FailureKind.unknown, 'Dữ liệu máy chủ trả về không đúng định dạng.');
    }
  }

  /// `GET /v1/ai/orders/{id}/eta` (công thức, không tốn token AI).
  Future<AiEnvelope<EtaResult>> eta(String orderId, {CancelToken? cancel}) =>
      _call(cancel, (o) async {
        final res = await _dio.get<Map<String, dynamic>>(
            '/v1/ai/orders/$orderId/eta',
            options: o,
            cancelToken: cancel);
        return AiEnvelope.fromJson(res.data!, EtaResult.fromJson);
      });

  /// `POST /v1/ai/orders/{id}/summary` (có thể tốn hạn mức AI: chỉ gọi khi người dùng bấm).
  Future<AiEnvelope<OrderSummaryResult>> summary(String orderId,
          {CancelToken? cancel}) =>
      _call(cancel, (o) async {
        final res = await _dio.post<Map<String, dynamic>>(
            '/v1/ai/orders/$orderId/summary',
            options: o,
            cancelToken: cancel);
        return AiEnvelope.fromJson(res.data!, OrderSummaryResult.fromJson);
      });

  /// `POST /v1/ai/chat` (chỉ CUSTOMER). [conversationId] do server tạo; bỏ trống ở lượt đầu.
  Future<AiEnvelope<ChatResult>> chat(String message,
          {String? conversationId, String? orderId, CancelToken? cancel}) =>
      _call(cancel, (o) async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/ai/chat',
            data: {
              'message': message,
              if (conversationId != null) 'conversationId': conversationId,
              if (orderId != null) 'orderId': orderId,
            },
            options: o,
            cancelToken: cancel);
        return AiEnvelope.fromJson(res.data!, ChatResult.fromJson);
      });
}

final aiApiProvider =
    Provider<AiApi>((ref) => AiApi(ref.read(sessionProvider).api));
