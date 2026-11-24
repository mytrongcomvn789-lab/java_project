import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/features/operator/confirmation_photo_controller.dart';
import 'package:smartdrone_mobile/features/operator/confirmation_photo_picker.dart';
import 'package:smartdrone_mobile/features/operator/operator_api.dart';
import 'package:smartdrone_mobile/features/operator/operator_controllers.dart';
import 'package:smartdrone_mobile/features/orders/photo_picker.dart';

const orderId = '11111111-1111-1111-1111-111111111111';

// PNG 1x1 hợp lệ để Image.memory giải mã được.
final png = Uint8List.fromList(const [
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
  0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0xFF, 0xFF, 0x3F,
  0x00, 0x05, 0xFE, 0x02, 0xFE, 0xA7, 0x35, 0x81, 0x84, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
  0x44, 0xAE, 0x42, 0x60, 0x82
]);

PickedPhoto pic([String name = 'a.png', String? type = 'image/png']) =>
    PickedPhoto(name: name, bytes: png, contentType: type);

class FakeUploadApi extends OperatorApi {
  FakeUploadApi() : super(Dio());
  final uploads = <String>[];
  Object? uploadError;
  Completer<void>? gate;
  int counter = 0;

  @override
  Future<String> uploadConfirmationPhoto(
      {required Uint8List bytes,
      required String contentType,
      required String filename}) async {
    uploads.add('$filename|$contentType|${bytes.length}');
    final g = gate;
    if (g != null) await g.future;
    final e = uploadError;
    if (e != null) {
      uploadError = null;
      throw e;
    }
    return 'file-${++counter}';
  }
}

DioException offline() =>
    DioException.connectionError(requestOptions: RequestOptions(), reason: 'off');

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 10));

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late FakeUploadApi api;
  late StateProvider<String?> user;
  late ProviderContainer container;
  Object? pickerResult; // PickedPhoto?, hoặc exception để ném

  setUp(() {
    api = FakeUploadApi();
    pickerResult = pic();
    user = StateProvider<String?>((ref) => 'op-1');
    container = ProviderContainer(overrides: [
      operatorApiProvider.overrideWithValue(api),
      operatorUserProvider.overrideWith((ref) => ref.watch(user)),
      photoPickerProvider.overrideWithValue((source) async {
        final r = pickerResult;
        if (r is Exception) throw r;
        return r as PickedPhoto?;
      }),
    ]);
    addTearDown(container.dispose);
    container.listen(confirmationPhotoProvider(orderId), (_, __) {});
  });

  ConfirmationPhotoState read() => container.read(confirmationPhotoProvider(orderId));
  ConfirmationPhotoController ctl() =>
      container.read(confirmationPhotoProvider(orderId).notifier);

  group('controller', () {
    test('chụp ảnh rồi tải lên: chỉ có fileId sau khi tải xong', () async {
      api.gate = Completer<void>();
      final f = ctl().pick(PhotoSource.camera);
      await settle();
      expect(read().uploading, isTrue);
      expect(read().fileId, isNull); // chưa complete thì chưa có fileId dùng được
      api.gate!.complete();
      await f;
      expect(read().photo?.status, ConfirmPhotoStatus.done);
      expect(read().fileId, 'file-1');
      expect(api.uploads.single, startsWith('a.png|image/png|'));
    });

    test('chọn từ thư viện cũng tải lên; suy ra loại ảnh từ đuôi file', () async {
      pickerResult = pic('b.jpg', null);
      await ctl().pick(PhotoSource.gallery);
      expect(read().fileId, 'file-1');
      expect(api.uploads.single, contains('image/jpeg'));
    });

    test('người dùng hủy chọn ảnh thì không có gì thay đổi', () async {
      pickerResult = null;
      await ctl().pick(PhotoSource.camera);
      expect(read().photo, isNull);
      expect(read().pickError, isNull);
      expect(api.uploads, isEmpty);
    });

    test('từ chối quyền camera: báo riêng, không có ảnh, vẫn chọn được từ thư viện', () async {
      pickerResult = const PhotoPermissionDenied(PhotoSource.camera);
      await ctl().pick(PhotoSource.camera);
      expect(read().permissionDenied, PhotoSource.camera);
      expect(read().photo, isNull);
      expect(read().pickError, isNull);
      pickerResult = pic();
      await ctl().pick(PhotoSource.gallery);
      expect(read().permissionDenied, isNull); // lần chọn mới xóa cảnh báo
      expect(read().fileId, 'file-1');
    });

    test('lỗi khác của bộ chọn ảnh: báo lỗi chung', () async {
      pickerResult = Exception('boom');
      await ctl().pick(PhotoSource.camera);
      expect(read().pickError, isNotNull);
      expect(read().permissionDenied, isNull);
    });

    test('sai loại hoặc ảnh trống bị từ chối trước khi tải lên', () async {
      pickerResult = pic('a.gif', 'image/gif');
      await ctl().pick(PhotoSource.gallery);
      expect(read().pickError, contains('JPEG'));
      pickerResult = PickedPhoto(name: 'a.png', bytes: Uint8List(0), contentType: 'image/png');
      await ctl().pick(PhotoSource.gallery);
      expect(read().pickError, contains('trống'));
      expect(api.uploads, isEmpty);
      expect(read().photo, isNull);
    });

    test('upload thất bại do mất mạng: không có fileId, thử lại thành công', () async {
      api.uploadError = offline();
      await ctl().pick(PhotoSource.camera);
      expect(read().failed, isTrue);
      expect(read().fileId, isNull);
      expect(read().photo?.failure?.kind, FailureKind.offline);
      expect(read().photo?.failure?.isRetryable, isTrue);
      await ctl().retry();
      expect(read().photo?.status, ConfirmPhotoStatus.done);
      expect(read().fileId, 'file-1');
      expect(api.uploads.length, 2); // cùng ảnh được gửi lại
    });

    test('ảnh quá giới hạn của server: lỗi không thử lại được, phải chụp lại', () async {
      api.uploadError =
          const ApiFailure(FailureKind.fileTooLarge, 'Ảnh vượt quá dung lượng cho phép.');
      await ctl().pick(PhotoSource.camera);
      expect(read().failed, isTrue);
      expect(read().photo?.failure?.kind, FailureKind.fileTooLarge);
      expect(read().photo?.failure?.isRetryable, isFalse);
      await ctl().pick(PhotoSource.camera); // chụp lại ảnh khác
      expect(read().fileId, 'file-1');
    });

    test('upload dở (MinIO từ chối) là lỗi tạm thời, thử lại được', () async {
      api.uploadError = const ApiFailure(
          FailureKind.storageUnavailable, 'Tải ảnh lên không thành công.');
      await ctl().pick(PhotoSource.camera);
      expect(read().photo?.failure?.isRetryable, isTrue);
      await ctl().retry();
      expect(read().fileId, isNotNull);
    });

    test('đang tải lên thì không cho chọn ảnh khác', () async {
      api.gate = Completer<void>();
      final first = ctl().pick(PhotoSource.camera);
      await settle();
      await ctl().pick(PhotoSource.gallery); // bị bỏ qua
      api.gate!.complete();
      await first;
      expect(api.uploads.length, 1);
    });

    test('xóa ảnh: kết quả tải lên đến muộn của ảnh đã xóa bị bỏ qua', () async {
      api.gate = Completer<void>();
      final f = ctl().pick(PhotoSource.camera);
      await settle();
      ctl().remove();
      api.gate!.complete();
      await f;
      expect(read().photo, isNull);
      expect(read().fileId, isNull);
    });

    test('retry khi chưa lỗi thì không làm gì', () async {
      await ctl().retry();
      expect(api.uploads, isEmpty);
    });

    test('đổi tài khoản bỏ ảnh đang chọn', () async {
      await ctl().pick(PhotoSource.camera);
      expect(read().fileId, isNotNull);
      container.read(user.notifier).state = 'op-2';
      await settle();
      expect(read().photo, isNull);
    });
  });

  group('ánh xạ lỗi quyền của image_picker', () {
    const channel = MethodChannel('plugins.flutter.io/image_picker');
    tearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null));

    // Bộ chọn mặc định (không override): gọi image_picker thật qua kênh nền tảng giả lập.
    PhotoPicker realPicker() =>
        ProviderContainer().read(photoPickerProvider);

    void mockError(String code) => TestDefaultBinaryMessengerBinding
        .instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(code: code, message: 'x');
        });

    test('camera_access_denied và photo_access_denied thành PhotoPermissionDenied', () async {
      mockError('camera_access_denied');
      await expectLater(realPicker()(PhotoSource.camera), throwsA(isA<PhotoPermissionDenied>()));
      mockError('photo_access_denied');
      await expectLater(realPicker()(PhotoSource.gallery), throwsA(isA<PhotoPermissionDenied>()));
    });

    test('lỗi nền tảng khác vẫn là lỗi bình thường (không giả làm từ chối quyền)', () async {
      mockError('something_else');
      await expectLater(realPicker()(PhotoSource.camera), throwsA(isA<PlatformException>()));
    });
  });

  group('widget', () {
    Future<void> mount(WidgetTester tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(
              home: Scaffold(body: SingleChildScrollView(child: ConfirmationPhotoPicker(orderId: orderId))))));
      await tester.pumpAndSettle();
    }

    testWidgets('chưa có ảnh: hai nút chụp và chọn', (tester) async {
      await mount(tester);
      expect(find.byKey(const Key('photo-empty')), findsOneWidget);
      expect(find.text('Chụp ảnh'), findsOneWidget);
      expect(find.byKey(const Key('photo-gallery')), findsOneWidget);
      expect(find.byKey(const Key('photo-remove')), findsNothing);
    });

    testWidgets('đang tải: khóa nút; xong: hiện "Đã tải lên", cho chụp lại hoặc xóa', (tester) async {
      await mount(tester);
      api.gate = Completer<void>();
      await tester.tap(find.byKey(const Key('photo-camera')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 20));
      expect(find.byKey(const Key('photo-uploading')), findsOneWidget);
      expect(tester.widget<OutlinedButton>(find.byKey(const Key('photo-camera'))).onPressed, isNull);
      api.gate!.complete();
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('photo-done')), findsOneWidget);
      expect(find.text('Chụp lại'), findsOneWidget);
      await tester.tap(find.byKey(const Key('photo-remove')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('photo-empty')), findsOneWidget);
    });

    testWidgets('từ chối quyền camera: hướng dẫn cấp quyền và gợi ý chọn từ thư viện', (tester) async {
      await mount(tester);
      pickerResult = const PhotoPermissionDenied(PhotoSource.camera);
      await tester.tap(find.byKey(const Key('photo-camera')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('photo-permission')), findsOneWidget);
      expect(find.textContaining('quyền camera'), findsOneWidget);
      expect(find.textContaining('chọn ảnh từ thư viện'), findsOneWidget);
      await tester.tap(find.byKey(const Key('photo-gallery'))); // vẫn dùng được
      pickerResult = pic();
      await tester.pumpAndSettle();
    });

    testWidgets('mất mạng: có nút Thử lại; ảnh quá lớn: không có Thử lại', (tester) async {
      await mount(tester);
      api.uploadError = offline();
      await tester.tap(find.byKey(const Key('photo-camera')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('photo-failure')), findsOneWidget);
      expect(find.byKey(const Key('photo-retry')), findsOneWidget);
      await tester.tap(find.byKey(const Key('photo-retry')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('photo-done')), findsOneWidget);

      api.uploadError =
          const ApiFailure(FailureKind.fileTooLarge, 'Ảnh vượt quá dung lượng cho phép.');
      await tester.tap(find.byKey(const Key('photo-camera')));
      await tester.pumpAndSettle();
      expect(find.text('Ảnh vượt quá dung lượng cho phép.'), findsOneWidget);
      expect(find.byKey(const Key('photo-retry')), findsNothing);
    });
  });
}
