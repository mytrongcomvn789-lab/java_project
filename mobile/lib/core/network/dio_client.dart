import 'package:dio/dio.dart';

const apiBaseUrl = String.fromEnvironment('API_BASE_URL',
    defaultValue: 'http://10.0.2.2:5000/api');

Dio createDio() => Dio(BaseOptions(
      baseUrl: apiBaseUrl,
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 20),
      sendTimeout: const Duration(seconds: 20),
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json'
      },
    ));

String apiError(Object error) {
  if (error is DioException) {
    final status = error.response?.statusCode;
    if (status == 401) {
      return 'Phiên đăng nhập hoặc thông tin đăng nhập không hợp lệ.';
    }
    if (status == 403) {
      return 'Tài khoản không có quyền thực hiện thao tác này.';
    }
    if (status == 429) return 'Quá nhiều yêu cầu. Vui lòng thử lại sau.';
    if (status != null && status >= 500) {
      return 'Máy chủ đang gặp lỗi. Vui lòng thử lại.';
    }
    final data = error.response?.data;
    if (data is Map && data['message'] is String) {
      return data['message'] as String;
    }
    if (error.type == DioExceptionType.connectionTimeout ||
        error.type == DioExceptionType.receiveTimeout ||
        error.type == DioExceptionType.sendTimeout ||
        error.type == DioExceptionType.connectionError) {
      return 'Không kết nối được máy chủ. Kiểm tra mạng và thử lại.';
    }
  }
  return 'Không thể hoàn tất thao tác. Vui lòng thử lại.';
}
