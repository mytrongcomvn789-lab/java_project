import 'dart:typed_data';
import 'package:dio/dio.dart';
import '../../core/network/api_failure.dart';
import '../orders/customer_order_models.dart' show UploadTicket;

/// Mục đích của tệp khi xin upload ticket (`FilePurpose` của backend). Server chỉ cho
/// đúng vai trò tải đúng mục đích: khách tải ảnh gói hàng, nhân viên trạm tải ảnh xác nhận.
enum UploadPurpose {
  packagePhoto('PACKAGE_PHOTO'),
  confirmationPhoto('CONFIRMATION_PHOTO');

  const UploadPurpose(this.wire);
  final String wire;
}

/// Luồng tải ảnh 3 bước dùng chung (3.4): xin upload ticket, POST multipart tới MinIO
/// (field tệp đặt cuối), báo hoàn tất để server kiểm tra dung lượng và loại thật.
/// Chỉ trả `fileId` khi cả ba bước thành công; mọi lỗi ném [ApiFailure].
class PhotoUploader {
  PhotoUploader(this._dio, {Dio? uploadDio}) : _uploadDio = uploadDio ?? Dio();
  final Dio _dio;

  /// Dio riêng, không baseUrl, không Bearer token: URL tải lên là của MinIO.
  final Dio _uploadDio;

  Future<String> upload(
          {required UploadPurpose purpose,
          required Uint8List bytes,
          required String contentType,
          required String filename}) =>
      guardApi(() async {
        final ticketRes = await _dio.post<Map<String, dynamic>>(
            '/v1/files/upload-tickets',
            data: {
              'purpose': purpose.wire,
              'contentType': contentType,
              'sizeBytes': bytes.length,
            });
        final ticket = UploadTicket.fromJson(ticketRes.data!);
        if (ticket.maxSizeBytes > 0 && bytes.length > ticket.maxSizeBytes) {
          throw const ApiFailure(
              FailureKind.fileTooLarge, 'Ảnh vượt quá dung lượng cho phép.');
        }
        final form = FormData();
        for (final e in ticket.fields.entries) {
          form.fields.add(MapEntry(e.key, e.value));
        }
        form.files.add(MapEntry(
            ticket.fileFieldName,
            MultipartFile.fromBytes(bytes,
                filename: filename,
                contentType: DioMediaType.parse(contentType))));
        try {
          await _uploadDio.post<void>(ticket.url, data: form);
        } on DioException catch (e) {
          // Lỗi từ MinIO không theo envelope của backend: báo chung để người dùng thử lại.
          if (e.response != null) {
            throw const ApiFailure(FailureKind.storageUnavailable,
                'Tải ảnh lên không thành công. Vui lòng thử lại.');
          }
          rethrow;
        }
        await _dio.post<void>('/v1/files/${ticket.fileId}/complete');
        return ticket.fileId;
      });
}
