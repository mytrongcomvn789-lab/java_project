import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../orders/photo_picker.dart';
import 'operator_api.dart';
import 'operator_controllers.dart' show operatorUserProvider;

enum ConfirmPhotoStatus { uploading, done, failed }

/// Ảnh xác nhận giao hàng đang chọn. `fileId` chỉ có khi upload đã hoàn tất (complete) thành công.
class ConfirmationPhoto {
  const ConfirmationPhoto(
      {required this.id,
      required this.name,
      required this.bytes,
      required this.contentType,
      this.status = ConfirmPhotoStatus.uploading,
      this.fileId,
      this.failure});
  final int id;
  final String name, contentType;
  final Uint8List bytes;
  final ConfirmPhotoStatus status;
  final String? fileId;
  final ApiFailure? failure;

  ConfirmationPhoto copyWith(
          {ConfirmPhotoStatus? status, String? fileId, ApiFailure? failure}) =>
      ConfirmationPhoto(
          id: id,
          name: name,
          bytes: bytes,
          contentType: contentType,
          status: status ?? this.status,
          fileId: fileId ?? this.fileId,
          failure: status == ConfirmPhotoStatus.failed ? failure : null);
}

class ConfirmationPhotoState {
  const ConfirmationPhotoState(
      {this.photo, this.permissionDenied, this.pickError});
  final ConfirmationPhoto? photo;

  /// Nguồn bị từ chối quyền (camera hoặc thư viện) ở lần chọn gần nhất.
  final PhotoSource? permissionDenied;

  /// Lỗi khi chọn ảnh (không mở được bộ chọn, sai loại, tệp rỗng).
  final String? pickError;

  bool get uploading => photo?.status == ConfirmPhotoStatus.uploading;
  bool get failed => photo?.status == ConfirmPhotoStatus.failed;

  /// `fileId` dùng được cho xác nhận giao: chỉ khi upload complete thành công.
  String? get fileId =>
      photo?.status == ConfirmPhotoStatus.done ? photo!.fileId : null;
}

/// Ảnh xác nhận cho MỘT đơn (server bắt buộc đúng một `photoFileId`). Chọn bằng camera hoặc
/// thư viện, tải lên ngay, có thể thử lại hoặc thay. Gắn với tài khoản đang đăng nhập; rời màn
/// hình thì bỏ ảnh (ảnh đã tải mà chưa gắn vào đơn sẽ được server dọn sau 24 giờ).
class ConfirmationPhotoController
    extends AutoDisposeFamilyNotifier<ConfirmationPhotoState, String> {
  int _nextId = 0;
  int _epoch = 0;

  @override
  ConfirmationPhotoState build(String orderId) {
    ref.watch(operatorUserProvider);
    ref.onDispose(() => _epoch++);
    return const ConfirmationPhotoState();
  }

  OperatorApi get _api => ref.read(operatorApiProvider);

  /// Chọn ảnh rồi tải lên. Đang tải thì bỏ qua (không thay ảnh giữa chừng).
  Future<void> pick(PhotoSource source) async {
    if (state.uploading) return;
    final epoch = _epoch;
    PickedPhoto? picked;
    try {
      picked = await ref.read(photoPickerProvider)(source);
    } on PhotoPermissionDenied catch (e) {
      if (epoch == _epoch) {
        state = ConfirmationPhotoState(
            photo: state.photo, permissionDenied: e.source);
      }
      return;
    } catch (_) {
      if (epoch == _epoch) {
        state = ConfirmationPhotoState(
            photo: state.photo,
            pickError: 'Không mở được bộ chọn ảnh. Vui lòng thử lại.');
      }
      return;
    }
    if (picked == null || epoch != _epoch) return; // người dùng hủy
    final type = contentTypeOf(picked);
    if (picked.bytes.isEmpty) {
      state = ConfirmationPhotoState(
          photo: state.photo, pickError: 'Ảnh trống. Vui lòng chọn ảnh khác.');
      return;
    }
    if (type == null || !allowedPhotoTypes.contains(type)) {
      state = ConfirmationPhotoState(
          photo: state.photo, pickError: 'Chỉ nhận ảnh JPEG, PNG hoặc WebP.');
      return;
    }
    final photo = ConfirmationPhoto(
        id: _nextId++, name: picked.name, bytes: picked.bytes, contentType: type);
    state = ConfirmationPhotoState(photo: photo);
    await _upload(photo);
  }

  /// Thử lại tải lên ảnh đã chọn (lỗi tạm thời như mất mạng hoặc kho ảnh chưa sẵn sàng).
  Future<void> retry() async {
    final photo = state.photo;
    if (photo == null || photo.status != ConfirmPhotoStatus.failed) return;
    final uploading = photo.copyWith(status: ConfirmPhotoStatus.uploading);
    state = ConfirmationPhotoState(photo: uploading);
    await _upload(uploading);
  }

  /// Bỏ ảnh đã chọn. Kết quả tải lên đến muộn của ảnh đã bỏ bị bỏ qua.
  void remove() => state = const ConfirmationPhotoState();

  void dismissMessages() => state = ConfirmationPhotoState(photo: state.photo);

  Future<void> _upload(ConfirmationPhoto photo) async {
    final epoch = _epoch;
    try {
      final fileId = await _api.uploadConfirmationPhoto(
          bytes: photo.bytes, contentType: photo.contentType, filename: photo.name);
      if (epoch != _epoch || state.photo?.id != photo.id) return;
      state = ConfirmationPhotoState(
          photo: photo.copyWith(status: ConfirmPhotoStatus.done, fileId: fileId));
    } catch (e) {
      if (epoch != _epoch || state.photo?.id != photo.id) return;
      state = ConfirmationPhotoState(
          photo: photo.copyWith(
              status: ConfirmPhotoStatus.failed, failure: toApiFailure(e)));
    }
  }
}

final confirmationPhotoProvider = NotifierProvider.autoDispose
    .family<ConfirmationPhotoController, ConfirmationPhotoState, String>(
        ConfirmationPhotoController.new);
