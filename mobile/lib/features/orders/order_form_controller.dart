import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../../core/realtime/realtime_provider.dart';
import 'customer_order_models.dart';
import 'customer_orders_api.dart';
import 'order_list_controller.dart';
import 'photo_picker.dart';

/// Số ảnh tối đa chỉ để gợi ý giao diện; server (max-photos) mới là nơi quyết định.
const maxPackagePhotos = 3;

enum PhotoStatus { uploading, done, failed }

class PhotoAttachment {
  const PhotoAttachment(
      {required this.id,
      required this.name,
      required this.bytes,
      required this.contentType,
      this.status = PhotoStatus.uploading,
      this.fileId,
      this.failure});
  final int id;
  final String name, contentType;
  final Uint8List bytes;
  final PhotoStatus status;
  final String? fileId;
  final ApiFailure? failure;

  PhotoAttachment copyWith(
          {PhotoStatus? status, String? fileId, ApiFailure? failure}) =>
      PhotoAttachment(
          id: id,
          name: name,
          bytes: bytes,
          contentType: contentType,
          status: status ?? this.status,
          fileId: fileId ?? this.fileId,
          failure: status == PhotoStatus.failed ? failure : null);
}

/// Giá trị người dùng nhập ở các ô văn bản (đã parse).
class OrderFormValues {
  const OrderFormValues(
      {required this.description,
      required this.weightGrams,
      required this.lengthCm,
      required this.widthCm,
      required this.heightCm});
  final String description;
  final int weightGrams;
  final double lengthCm, widthCm, heightCm;
}

class OrderFormState {
  const OrderFormState(
      {this.pickupId,
      this.dropoffId,
      this.address,
      this.photos = const [],
      this.submitting = false,
      this.error,
      this.photoError,
      this.created,
      this.editing,
      this.keptPhotoIds = const []});
  final String? pickupId, dropoffId;

  /// Khác null khi đang SỬA một đơn chờ duyệt (không phải tạo mới).
  final OrderDetail? editing;

  /// Ảnh đã có trên đơn đang sửa và được giữ lại (không tải lại).
  final List<String> keptPhotoIds;
  final Address? address;
  final List<PhotoAttachment> photos;
  final bool submitting;

  /// Lỗi của lần gửi gần nhất (từ server hoặc kiểm tra cục bộ).
  final ApiFailure? error;
  final String? photoError;

  /// Chỉ khác null khi server đã xác nhận (2xx) tạo hoặc phát lại đơn.
  final CreatedOrder? created;

  bool get uploading => photos.any((p) => p.status == PhotoStatus.uploading);
  bool get hasFailedPhoto => photos.any((p) => p.status == PhotoStatus.failed);

  OrderFormState copyWith(
          {String? pickupId,
          String? dropoffId,
          Address? address,
          List<PhotoAttachment>? photos,
          bool? submitting,
          ApiFailure? error,
          String? photoError,
          CreatedOrder? created,
          OrderDetail? editing,
          List<String>? keptPhotoIds,
          bool clearError = false,
          bool clearPhotoError = false}) =>
      OrderFormState(
          pickupId: pickupId ?? this.pickupId,
          dropoffId: dropoffId ?? this.dropoffId,
          address: address ?? this.address,
          photos: photos ?? this.photos,
          submitting: submitting ?? this.submitting,
          error: clearError ? null : error ?? this.error,
          photoError: clearPhotoError ? null : photoError ?? this.photoError,
          created: created ?? this.created,
          editing: editing ?? this.editing,
          keptPhotoIds: keptPhotoIds ?? this.keptPhotoIds);
}

/// Biểu mẫu tạo đơn. Mọi ràng buộc nghiệp vụ do server quyết định; ở đây chỉ
/// giữ lựa chọn, tải ảnh và gửi đúng một lần (Idempotency-Key).
class OrderFormController extends AutoDisposeNotifier<OrderFormState> {
  int _epoch = 0;
  int _nextPhotoId = 0;

  /// Khóa idempotency và nội dung đã gắn với nó: gửi lại cùng nội dung dùng cùng
  /// khóa (an toàn khi lỗi mạng/timeout), nội dung đổi thì sinh khóa mới.
  String _key = '';
  String? _keyFingerprint;

  @override
  OrderFormState build() {
    ref.watch(realtimeUserProvider); // đổi tài khoản: bỏ hết dữ liệu đang nhập
    ref.onDispose(() => _epoch++);
    _key = newIdempotencyKey();
    _keyFingerprint = null;
    return const OrderFormState();
  }

  CustomerOrdersApi get _api => ref.read(customerOrdersApiProvider);

  /// Bắt đầu sửa [order]: điền sẵn trạm và giữ ảnh hiện có. Địa chỉ người nhận phải chọn lại vì đơn chỉ lưu
  /// bản chụp địa chỉ, không lưu mã địa chỉ.
  void startEdit(OrderDetail order) => state = OrderFormState(
      pickupId: order.pickupStationId,
      dropoffId: order.dropoffStationId,
      editing: order,
      keptPhotoIds: List.of(order.package.photoFileIds));

  void removeKeptPhoto(String fileId) => state = state.copyWith(
      keptPhotoIds: [for (final id in state.keptPhotoIds) if (id != fileId) id]);

  void setPickup(String? id) =>
      state = state.copyWith(pickupId: id, clearError: true);
  void setDropoff(String? id) =>
      state = state.copyWith(dropoffId: id, clearError: true);
  void setAddress(Address a) =>
      state = state.copyWith(address: a, clearError: true);

  /// Chọn ảnh rồi tải lên ngay để lỗi ảnh hiện sớm và gửi đơn nhanh hơn.
  Future<void> addPhoto(PhotoSource source) async {
    if (state.photos.length + state.keptPhotoIds.length >= maxPackagePhotos) {
      state = state.copyWith(
          photoError: 'Tối đa $maxPackagePhotos ảnh cho mỗi đơn.');
      return;
    }
    final epoch = _epoch;
    PickedPhoto? picked;
    try {
      picked = await ref.read(photoPickerProvider)(source);
    } catch (_) {
      if (epoch == _epoch) {
        state = state.copyWith(
            photoError: 'Không mở được bộ chọn ảnh. Kiểm tra quyền truy cập ảnh/camera.');
      }
      return;
    }
    if (picked == null || epoch != _epoch) return;
    final type = contentTypeOf(picked);
    if (type == null || !allowedPhotoTypes.contains(type)) {
      state = state.copyWith(photoError: 'Chỉ nhận ảnh JPEG, PNG hoặc WebP.');
      return;
    }
    final photo = PhotoAttachment(
        id: _nextPhotoId++, name: picked.name, bytes: picked.bytes, contentType: type);
    state = state.copyWith(
        photos: [...state.photos, photo], clearPhotoError: true, clearError: true);
    await _upload(photo);
  }

  Future<void> retryPhoto(int id) async {
    final photo = state.photos.where((p) => p.id == id).firstOrNull;
    if (photo == null || photo.status != PhotoStatus.failed) return;
    _replace(photo.copyWith(status: PhotoStatus.uploading));
    await _upload(photo);
  }

  void removePhoto(int id) => state = state.copyWith(
      photos: [for (final p in state.photos) if (p.id != id) p],
      clearPhotoError: true);

  Future<void> _upload(PhotoAttachment photo) async {
    final epoch = _epoch;
    try {
      final fileId = await _api.uploadPackagePhoto(
          bytes: photo.bytes, contentType: photo.contentType, filename: photo.name);
      if (epoch != _epoch) return;
      _replace(photo.copyWith(status: PhotoStatus.done, fileId: fileId));
    } catch (e) {
      if (epoch != _epoch) return;
      _replace(photo.copyWith(status: PhotoStatus.failed, failure: toApiFailure(e)));
    }
  }

  void _replace(PhotoAttachment updated) {
    // Ảnh có thể đã bị xóa trong lúc đang tải: khi đó bỏ qua kết quả.
    if (!state.photos.any((p) => p.id == updated.id)) return;
    state = state.copyWith(
        photos: [for (final p in state.photos) p.id == updated.id ? updated : p]);
  }

  /// Gửi đơn. Trả true chỉ khi server xác nhận. Gọi lại khi đang gửi bị bỏ qua.
  Future<bool> submit(OrderFormValues v) async {
    if (state.submitting) return false;
    ApiFailure? local;
    if (state.pickupId == null) {
      local = const ApiFailure(FailureKind.validation, 'Cần chọn trạm gửi.');
    } else if (state.dropoffId == null) {
      local = const ApiFailure(FailureKind.validation, 'Cần chọn trạm nhận.');
    } else if (state.address == null) {
      local = const ApiFailure(FailureKind.validation, 'Cần chọn địa chỉ người nhận.');
    } else if (state.uploading) {
      local = const ApiFailure(FailureKind.validation, 'Ảnh đang được tải lên, vui lòng chờ.');
    } else if (state.hasFailedPhoto) {
      local = const ApiFailure(FailureKind.validation,
          'Có ảnh tải lên chưa được. Hãy thử lại hoặc xóa ảnh đó.');
    }
    if (local != null) {
      state = state.copyWith(error: local);
      return false;
    }
    final input = CreateOrderInput(
        pickupStationId: state.pickupId!,
        dropoffStationId: state.dropoffId!,
        recipientAddressId: state.address!.id,
        description: v.description.trim(),
        weightGrams: v.weightGrams,
        lengthCm: v.lengthCm,
        widthCm: v.widthCm,
        heightCm: v.heightCm,
        photoFileIds: [...state.keptPhotoIds, for (final p in state.photos) p.fileId!]);
    final editing = state.editing;
    if (editing != null) return _update(editing, input);
    final fingerprint = jsonEncode(input.toJson());
    if (fingerprint != _keyFingerprint) {
      _key = newIdempotencyKey();
      _keyFingerprint = fingerprint;
    }
    final epoch = _epoch;
    state = state.copyWith(submitting: true, clearError: true);
    try {
      final created = await _api.createOrder(input, _key);
      if (epoch != _epoch) return true;
      ref.invalidate(orderListProvider); // danh sách phải thấy đơn mới
      state = state.copyWith(submitting: false, created: created);
      return true;
    } catch (e) {
      final failure = toApiFailure(e);
      if (failure.kind == FailureKind.idempotencyConflict) {
        _keyFingerprint = null; // lần sau dùng khóa mới
      }
      if (epoch == _epoch) {
        state = state.copyWith(submitting: false, error: failure);
      }
      return false;
    }
  }

  /// Gửi bản sửa. Không dùng Idempotency-Key: `If-Match` (phiên bản đang xem) đã chặn ghi đè và gửi trùng.
  Future<bool> _update(OrderDetail editing, CreateOrderInput input) async {
    final epoch = _epoch;
    state = state.copyWith(submitting: true, clearError: true);
    try {
      final order = await ref
          .read(customerOrdersApiProvider)
          .updateOrder(editing.id, editing.version, input);
      if (epoch != _epoch) return true;
      ref.invalidate(orderListProvider);
      state = state.copyWith(
          submitting: false, created: CreatedOrder(order, replayed: false));
      return true;
    } catch (e) {
      if (epoch == _epoch) state = state.copyWith(submitting: false, error: toApiFailure(e));
      return false;
    }
  }
}

final orderFormProvider =
    NotifierProvider.autoDispose<OrderFormController, OrderFormState>(
        OrderFormController.new);
