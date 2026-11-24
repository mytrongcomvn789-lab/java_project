import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

/// Vấn đề khiến không quét được: người dùng từ chối quyền camera, máy không hỗ trợ, hoặc lỗi khác.
enum ScanProblem { permissionDenied, unsupported, other }

/// Dựng khung quét: gọi [onCode] với chuỗi thô của mỗi mã đọc được, và dùng [errorView] để dựng
/// màn thay thế khi camera không dùng được. Tách ra để test thay bằng bộ quét giả (không cần camera).
typedef QrScannerBuilder = Widget Function(
  BuildContext context, {
  required void Function(String raw) onCode,
  required Widget Function(ScanProblem problem) errorView,
});

Widget defaultQrScanner(
  BuildContext context, {
  required void Function(String raw) onCode,
  required Widget Function(ScanProblem problem) errorView,
}) =>
    _CameraScanner(onCode: onCode, errorView: errorView);

final qrScannerBuilderProvider =
    Provider<QrScannerBuilder>((ref) => defaultQrScanner);

class _CameraScanner extends StatefulWidget {
  const _CameraScanner({required this.onCode, required this.errorView});
  final void Function(String raw) onCode;
  final Widget Function(ScanProblem problem) errorView;

  @override
  State<_CameraScanner> createState() => _CameraScannerState();
}

class _CameraScannerState extends State<_CameraScanner> {
  // Chỉ đọc mã QR; không lặp lại cùng một mã liên tục.
  final _controller = MobileScannerController(
      formats: const [BarcodeFormat.qrCode],
      detectionSpeed: DetectionSpeed.noDuplicates);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MobileScanner(
      controller: _controller,
      onDetect: (capture) {
        for (final b in capture.barcodes) {
          final raw = b.rawValue;
          if (raw != null && raw.isNotEmpty) {
            widget.onCode(raw);
            return;
          }
        }
      },
      errorBuilder: (context, error) => widget.errorView(switch (error.errorCode) {
            MobileScannerErrorCode.permissionDenied => ScanProblem.permissionDenied,
            MobileScannerErrorCode.unsupported => ScanProblem.unsupported,
            _ => ScanProblem.other,
          }));
}
