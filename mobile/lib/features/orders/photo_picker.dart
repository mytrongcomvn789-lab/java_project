import 'dart:typed_data';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

enum PhotoSource { gallery, camera }

/// Ảnh người dùng vừa chọn, đã đọc vào bộ nhớ.
class PickedPhoto {
  const PickedPhoto({required this.name, required this.bytes, this.contentType});
  final String name;
  final Uint8List bytes;

  /// Có thể null nếu hệ điều hành không cho biết; khi đó suy ra từ đuôi file.
  final String? contentType;
}

typedef PhotoPicker = Future<PickedPhoto?> Function(PhotoSource source);

/// Loại ảnh server cho phép (application.yml: allowed-content-types).
const allowedPhotoTypes = {'image/jpeg', 'image/png', 'image/webp'};

/// Suy ra content-type từ tên file khi hệ điều hành không cung cấp.
String? contentTypeOf(PickedPhoto p) {
  final declared = p.contentType?.toLowerCase();
  if (declared != null && declared.isNotEmpty && declared != 'application/octet-stream') {
    return declared == 'image/jpg' ? 'image/jpeg' : declared;
  }
  final n = p.name.toLowerCase();
  if (n.endsWith('.jpg') || n.endsWith('.jpeg')) return 'image/jpeg';
  if (n.endsWith('.png')) return 'image/png';
  if (n.endsWith('.webp')) return 'image/webp';
  return null;
}

Future<PickedPhoto?> _pickWithImagePicker(PhotoSource source) async {
  final file = await ImagePicker().pickImage(
      source: source == PhotoSource.camera ? ImageSource.camera : ImageSource.gallery,
      maxWidth: 1600, // thu nhỏ để tải nhanh, không lộ ảnh gốc quá lớn
      imageQuality: 85);
  if (file == null) return null; // người dùng hủy
  return PickedPhoto(
      name: file.name, bytes: await file.readAsBytes(), contentType: file.mimeType);
}

/// Có thể thay trong test để không mở bộ chọn ảnh thật.
final photoPickerProvider = Provider<PhotoPicker>((ref) => _pickWithImagePicker);
