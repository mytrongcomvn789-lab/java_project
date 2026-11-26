import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_failure.dart';
import '../../core/theme/app_theme.dart';
import '../../core/ui/app_widgets.dart';
import '../orders/customer_order_models.dart' show Station;
import '../orders/order_detail_controller.dart' show stationsProvider;
import '../orders/order_screens.dart' show StatusChip;
import 'confirmation_photo_controller.dart';
import 'confirmation_photo_picker.dart';
import 'operator_models.dart';
import 'pickup_confirm_controller.dart';
import 'qr_scanner.dart';
import 'sync_widgets.dart' show QueuedNotice;

void _leave(BuildContext context) {
  if (context.canPop()) {
    context.pop();
  } else {
    context.go('/operator');
  }
}

/// Quét mã QR nhận hàng của khách. Trả về [PickupCredential] qua `context.pop`. Chuỗi không đúng
/// hình dạng mã nhận hàng bị bỏ qua và quét tiếp; camera không dùng được thì hướng dẫn nhập OTP.
class QrScanScreen extends ConsumerStatefulWidget {
  const QrScanScreen({super.key});
  @override
  ConsumerState<QrScanScreen> createState() => _QrScanScreenState();
}

class _QrScanScreenState extends ConsumerState<QrScanScreen> {
  bool _done = false;
  bool _unrelated = false;

  void _onCode(String raw) {
    if (_done) return;
    final credential = PickupCredential.fromScan(raw);
    if (credential == null) {
      if (!_unrelated) setState(() => _unrelated = true);
      return;
    }
    _done = true; // chỉ trả về một lần
    if (context.canPop()) {
      context.pop<PickupCredential>(credential);
    }
  }

  Widget _problem(ScanProblem problem) => Center(
      child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.no_photography_outlined, size: 48, color: Colors.white),
            const SizedBox(height: 12),
            Text(
                switch (problem) {
                  ScanProblem.permissionDenied =>
                    'Ứng dụng chưa được cấp quyền camera. Vào Cài đặt > Ứng dụng > SmartDrone > Quyền để cho phép, rồi mở lại màn này. Hoặc quay lại và nhập mã OTP.',
                  ScanProblem.unsupported =>
                    'Thiết bị này không hỗ trợ quét mã. Quay lại và nhập mã OTP.',
                  ScanProblem.other =>
                    'Không mở được camera. Quay lại và nhập mã OTP, hoặc thử lại sau.',
                },
                key: const Key('scan-problem'),
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white, height: 1.4)),
            const SizedBox(height: 16),
            FilledButton(
                key: const Key('scan-back'),
                onPressed: () => _leave(context),
                child: const Text('Quay lại để nhập OTP')),
          ])));

  @override
  Widget build(BuildContext context) {
    final builder = ref.watch(qrScannerBuilderProvider);
    final torch = ref.watch(scanTorchProvider);
    return Scaffold(
        backgroundColor: AppColors.dark,
        body: Stack(fit: StackFit.expand, children: [
          builder(context, onCode: _onCode, errorView: _problem),
          // Khung ngắm và vạch quét chỉ để trang trí, không chặn thao tác chạm.
          const IgnorePointer(child: _ScanOverlay()),
          // Phải gắn vào mép trên bằng Positioned: Stack(expand) sẽ kéo giãn một con thường ra toàn
          // màn hình và đẩy thanh này xuống giữa.
          Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: SafeArea(
                  bottom: false,
                  child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Row(children: [
                    _RoundButton(
                        key: const Key('scan-close'),
                        icon: Icons.arrow_back,
                        tooltip: 'Quay lại',
                        onTap: () => _leave(context)),
                    const Expanded(
                        child: Text('Quét mã nhận hàng',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                                color: Colors.white,
                                fontSize: 16,
                                fontWeight: FontWeight.w700))),
                    _RoundButton(
                        key: const Key('scan-torch'),
                        icon: torch ? Icons.flash_on : Icons.flash_off,
                        tooltip: torch ? 'Tắt đèn flash' : 'Bật đèn flash',
                        onTap: () =>
                            ref.read(scanTorchProvider.notifier).state = !torch),
                  ])))),
          Positioned(
              left: 16,
              right: 16,
              bottom: 24 + MediaQuery.paddingOf(context).bottom,
              child: IgnorePointer(
                  child: Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                          color: _unrelated
                              ? StatusPalette.failed.background
                              : Colors.black54,
                          borderRadius: BorderRadius.circular(16)),
                      child: Text(
                          _unrelated
                              ? 'Mã QR này không phải mã nhận hàng. Hãy quét mã trong ứng dụng của khách.'
                              : 'Hướng camera vào mã QR nhận hàng trên điện thoại của khách.',
                          key: const Key('scan-hint'),
                          textAlign: TextAlign.center,
                          style: TextStyle(
                              color: _unrelated
                                  ? StatusPalette.failed.foreground
                                  : Colors.white))))),
        ]));
  }
}

/// Nút tròn nổi trên camera (quay lại, đèn flash). Vùng chạm 48dp.
class _RoundButton extends StatelessWidget {
  const _RoundButton(
      {super.key, required this.icon, required this.tooltip, required this.onTap});
  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
      color: Colors.black45,
      shape: const CircleBorder(),
      child: IconButton(
          tooltip: tooltip,
          onPressed: onTap,
          constraints: const BoxConstraints.tightFor(width: 48, height: 48),
          icon: Icon(icon, color: Colors.white)));
}

/// Làm tối vùng ngoài khung ngắm, vẽ 4 góc teal và vạch quét vàng.
class _ScanOverlay extends StatelessWidget {
  const _ScanOverlay();
  @override
  Widget build(BuildContext context) =>
      ExcludeSemantics(child: SizedBox.expand(child: CustomPaint(painter: _OverlayPainter())));
}

class _OverlayPainter extends CustomPainter {
  const _OverlayPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final side = (size.shortestSide * 0.62).clamp(180.0, 320.0);
    final frame = Rect.fromCenter(
        center: Offset(size.width / 2, size.height * 0.42), width: side, height: side);
    final hole = RRect.fromRectAndRadius(frame, const Radius.circular(24));
    canvas.drawPath(
        Path.combine(PathOperation.difference, Path()..addRect(Offset.zero & size),
            Path()..addRRect(hole)),
        Paint()..color = Colors.black.withValues(alpha: 0.45));

    final corner = Paint()
      ..color = AppColors.tealBright
      ..style = PaintingStyle.stroke
      ..strokeWidth = 5
      ..strokeCap = StrokeCap.round;
    const len = 34.0;
    for (final (cx, cy, dx, dy) in [
      (frame.left, frame.top, 1.0, 1.0),
      (frame.right, frame.top, -1.0, 1.0),
      (frame.left, frame.bottom, 1.0, -1.0),
      (frame.right, frame.bottom, -1.0, -1.0),
    ]) {
      canvas.drawPath(
          Path()
            ..moveTo(cx, cy + dy * len)
            ..lineTo(cx, cy)
            ..lineTo(cx + dx * len, cy),
          corner);
    }
    final line = Paint()
      ..color = AppColors.amber
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(Offset(frame.left + 14, frame.center.dy),
        Offset(frame.right - 14, frame.center.dy), line);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

/// Xác nhận giao hàng cho khách tại trạm nhận. Route `/operator/pickups/:id`.
class PickupConfirmScreen extends ConsumerStatefulWidget {
  const PickupConfirmScreen({super.key, required this.orderId});
  final String orderId;
  @override
  ConsumerState<PickupConfirmScreen> createState() => _PickupConfirmScreenState();
}

class _PickupConfirmScreenState extends ConsumerState<PickupConfirmScreen> {
  final _otp = TextEditingController();
  final _note = TextEditingController();
  CredentialKind _mode = CredentialKind.qr;
  String? _otpError;

  @override
  void dispose() {
    _otp.dispose();
    _note.dispose();
    super.dispose();
  }

  PickupConfirmController get _controller =>
      ref.read(pickupConfirmProvider(widget.orderId).notifier);

  void _setMode(CredentialKind m) {
    if (m == _mode) return;
    setState(() {
      _mode = m;
      _otpError = null;
    });
    _otp.clear();
    _controller.setCredential(null);
  }

  void _onOtpChanged(String v) {
    final err = v.trim().isEmpty ? null : PickupCredential.validateOtp(v);
    setState(() => _otpError = err);
    _controller.setCredential(
        v.trim().isNotEmpty && err == null ? PickupCredential.otp(v) : null);
  }

  Future<void> _scan() async {
    final credential = await context.push<PickupCredential>('/operator/scan');
    if (credential != null) _controller.setCredential(credential);
  }

  Future<void> _submit() async {
    final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
                title: const Text('Xác nhận đã giao cho khách?'),
                content: const Text(
                    'Hệ thống sẽ kiểm tra mã nhận hàng. Khi xác nhận thành công, đơn chuyển sang "Đã giao" và không hoàn tác được.'),
                actions: [
                  TextButton(
                      key: const Key('confirm-dismiss'),
                      onPressed: () => Navigator.of(ctx).pop(false),
                      child: const Text('Quay lại')),
                  FilledButton(
                      key: const Key('confirm-ok'),
                      onPressed: () => Navigator.of(ctx).pop(true),
                      child: const Text('Xác nhận')),
                ]));
    if (ok != true) return;
    final done = await _controller.confirm(_note.text);
    if (!done && mounted && ref.read(pickupConfirmProvider(widget.orderId)).queued) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Chưa có mạng: đã lưu, sẽ tự gửi khi có mạng.')));
    }
    if (done && mounted) {
      final replayed = ref.read(pickupConfirmProvider(widget.orderId)).result?.replayed ?? false;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(replayed
              ? 'Đơn này đã được xác nhận giao trước đó.'
              : 'Đã xác nhận giao hàng.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(pickupConfirmProvider(widget.orderId));
    final photo = ref.watch(confirmationPhotoProvider(widget.orderId));
    final stations = ref.watch(stationsProvider).valueOrNull;
    // Mã bị bỏ do sai/hết hạn/khóa thì xóa luôn ô OTP đang hiện.
    ref.listen(pickupConfirmProvider(widget.orderId).select((s) => s.credential),
        (prev, next) {
      if (next == null && prev != null && _otp.text.isNotEmpty) {
        _otp.clear();
        setState(() => _otpError = null);
      }
    });
    final order = state.order;
    return Scaffold(
        appBar: AppBar(
            title: Text(order == null ? 'Xác nhận giao' : 'Giao đơn ${order.shortCode}'),
            actions: [
              IconButton(
                  tooltip: 'Tải lại',
                  onPressed: () => _controller.reload(),
                  icon: const Icon(Icons.refresh))
            ]),
        body: Column(children: [
          if (state.notice != null)
            MessageBanner(
                key: const Key('confirm-notice'),
                text: state.notice!,
                color: StatusPalette.pending.background,
                onClose: _controller.dismissMessages),
          if (state.failure != null)
            _FailureBanner(state.failure!, _controller.dismissMessages),
          Expanded(child: _body(context, state, photo, stations)),
        ]),
        bottomNavigationBar: _bottomBar(state, photo));
  }

  Widget _body(BuildContext context, PickupConfirmState state,
      ConfirmationPhotoState photo, List<Station>? stations) {
    final order = state.order;
    if (order == null) {
      if (state.error != null) {
        return Center(
            child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Text(
                      state.error!.kind == FailureKind.notFound
                          ? 'Đơn không còn trong phạm vi của bạn hoặc không tồn tại.'
                          : state.error!.message,
                      key: const Key('confirm-error'),
                      textAlign: TextAlign.center),
                  const SizedBox(height: 12),
                  FilledButton(
                      onPressed: state.error!.kind == FailureKind.notFound
                          ? () => _leave(context)
                          : () => _controller.reload(),
                      child: Text(state.error!.kind == FailureKind.notFound
                          ? 'Quay lại danh sách'
                          : 'Thử lại')),
                ])));
      }
      return const Center(child: CircularProgressIndicator());
    }
    final theme = Theme.of(context);
    String name(String id) {
      for (final s in stations ?? const <Station>[]) {
        if (s.id == id) return s.name;
      }
      return 'Trạm ${id.length >= 8 ? id.substring(0, 8) : id}';
    }

    if (state.result != null) {
      return _Success(state.result!, onDone: () => _leave(context));
    }
    if (state.queued) {
      return ListView(padding: const EdgeInsets.all(16), children: [
        const QueuedNotice(
            key: Key('confirm-queued'),
            text:
                'Chưa có mạng nên yêu cầu xác nhận giao (kèm mã và ảnh) đã được lưu mã hóa trên máy. Đơn CHƯA được giao trên hệ thống; app sẽ tự gửi khi có mạng. Mã nhận hàng của khách chỉ có hiệu lực 30 phút nên hãy kết nối mạng sớm.'),
        FilledButton(
            key: const Key('confirm-queued-back'),
            onPressed: () => _leave(context),
            child: const Text('Về danh sách')),
      ]);
    }
    final ready = state.credential != null &&
        photo.fileId != null &&
        !state.submitting &&
        state.awaitingPickup;
    final retry = state.failure?.outcomeUnknown == true ||
        state.failure?.kind == FailureKind.offline ||
        state.failure?.kind == FailureKind.server;
    return ListView(padding: const EdgeInsets.all(16), children: [
      Row(children: [
        Expanded(
            child: Text(order.statusLabel,
                key: const Key('confirm-status'),
                style: theme.textTheme.headlineSmall)),
        StatusChip(order.status, order.statusLabel),
      ]),
      const SizedBox(height: 12),
      InfoCard('Đơn hàng', [
        InfoRow('Trạm gửi', name(order.pickupStationId)),
        InfoRow('Trạm nhận', name(order.dropoffStationId)),
        InfoRow('Người nhận', order.recipient.name),
        InfoRow('Điện thoại', order.recipient.phone),
        InfoRow('Kiện', '${order.package.description} · ${order.package.weightGrams} g'),
      ]),
      if (!state.awaitingPickup)
        Container(
            key: const Key('confirm-not-pending'),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(14)),
            child: Text(
                'Đơn không còn chờ khách nhận (trạng thái: ${order.statusLabel}).'))
      else ...[
        InfoCard('Mã nhận hàng của khách', [
          SegmentedButton<CredentialKind>(
              key: const Key('mode'),
              segments: const [
                ButtonSegment(
                    value: CredentialKind.qr,
                    icon: Icon(Icons.qr_code_scanner),
                    label: Text('Quét QR')),
                ButtonSegment(
                    value: CredentialKind.otp,
                    icon: Icon(Icons.pin_outlined),
                    label: Text('Nhập OTP')),
              ],
              selected: {_mode},
              onSelectionChanged:
                  state.submitting ? null : (s) => _setMode(s.first)),
          const SizedBox(height: 12),
          if (_mode == CredentialKind.qr)
            state.credential?.kind == CredentialKind.qr
                ? Row(children: [
                    Icon(Icons.check_circle, color: StatusPalette.done.foreground),
                    const SizedBox(width: 8),
                    const Expanded(
                        child: Text('Đã quét mã QR', key: Key('qr-scanned'))),
                    TextButton(
                        key: const Key('rescan'),
                        onPressed: state.submitting ? null : _scan,
                        child: const Text('Quét lại')),
                  ])
                : FilledButton.icon(
                    key: const Key('scan-button'),
                    onPressed: state.submitting ? null : _scan,
                    icon: const Icon(Icons.qr_code_scanner),
                    label: const Text('Quét mã QR'))
          else
            TextField(
                key: const Key('otp-field'),
                controller: _otp,
                enabled: !state.submitting,
                keyboardType: TextInputType.number,
                maxLength: 16,
                obscureText: false,
                enableSuggestions: false,
                autocorrect: false,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration:
                    InputDecoration(labelText: 'Mã OTP', errorText: _otpError),
                onChanged: _onOtpChanged),
        ]),
        InfoCard('Ảnh xác nhận (bắt buộc)',
            [ConfirmationPhotoPicker(orderId: widget.orderId)]),
        InfoCard('Ghi chú', [
          TextField(
              key: const Key('confirm-note'),
              controller: _note,
              maxLength: 500,
              maxLines: 2,
              decoration: const InputDecoration(labelText: 'Ghi chú (tùy chọn)')),
        ]),
      ],
    ]);
  }

  /// Nút chính cố định ở đáy màn hình, kèm dòng nhắc còn thiếu gì (mã, ảnh).
  Widget? _bottomBar(PickupConfirmState state, ConfirmationPhotoState photo) {
    if (state.order == null ||
        state.result != null ||
        state.queued ||
        !state.awaitingPickup) {
      return null;
    }
    final ready = state.credential != null &&
        photo.fileId != null &&
        !state.submitting &&
        state.awaitingPickup;
    final retry = state.failure?.outcomeUnknown == true ||
        state.failure?.kind == FailureKind.offline ||
        state.failure?.kind == FailureKind.server;
    return BottomActionBar(
        hint: !ready && !state.submitting
            ? Text(
                [
                  if (state.credential == null)
                    _mode == CredentialKind.qr ? 'Chưa quét mã QR.' : 'Chưa nhập mã OTP.',
                  if (photo.fileId == null)
                    photo.uploading ? 'Ảnh đang tải lên…' : 'Chưa có ảnh xác nhận đã tải lên.',
                ].join(' '),
                key: const Key('confirm-missing'),
                style: const TextStyle(color: AppColors.textMuted, fontSize: 12))
            : null,
        child: FilledButton(
            key: const Key('confirm-button'),
            // Vô hiệu hóa khi đang gửi hoặc thiếu mã/ảnh: chặn bấm đúp.
            onPressed: ready ? _submit : null,
            child: state.submitting
                ? const SizedBox(
                    width: 22,
                    height: 22,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                : Text(retry ? 'Gửi lại' : 'Xác nhận đã giao')));
  }
}

class _Success extends StatelessWidget {
  const _Success(this.result, {required this.onDone});
  final ConfirmResult result;
  final VoidCallback onDone;
  @override
  Widget build(BuildContext context) => Center(
      child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.check_circle, size: 64, color: Colors.green),
            const SizedBox(height: 12),
            Text(
                result.replayed
                    ? 'Đơn này đã được xác nhận giao trước đó.'
                    : 'Đã xác nhận giao hàng.',
                key: const Key('confirm-success'),
                style: Theme.of(context).textTheme.titleLarge,
                textAlign: TextAlign.center),
            const SizedBox(height: 4),
            Text(
                'Phương thức: ${result.confirmation.method == 'QR' ? 'Quét QR' : 'Nhập OTP'}',
                textAlign: TextAlign.center),
            const SizedBox(height: 16),
            FilledButton(
                key: const Key('confirm-done'),
                onPressed: onDone,
                child: const Text('Về danh sách')),
          ])));
}

class _FailureBanner extends StatelessWidget {
  const _FailureBanner(this.failure, this.onClose);
  final ApiFailure failure;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final hint = switch (failure.kind) {
      FailureKind.pickupCodeExpired || FailureKind.pickupCodeLocked =>
        'Nhờ khách mở lại mã nhận hàng, rồi quét hoặc nhập mã mới.',
      FailureKind.pickupCodeInvalid => 'Kiểm tra lại mã rồi nhập hoặc quét lại.',
      FailureKind.pickupCodeUsed => 'Mã này đã dùng. Kiểm tra lại trạng thái đơn.',
      FailureKind.offline => 'Đơn CHƯA được xác nhận giao. Kiểm tra mạng rồi bấm "Gửi lại".',
      FailureKind.timeout =>
        'Chưa rõ đơn đã được xác nhận chưa. "Gửi lại" là an toàn (không giao hai lần).',
      _ => null,
    };
    return MessageBanner(
        key: const Key('confirm-failure'),
        text: hint == null ? failure.message : '${failure.message}\n$hint',
        color: StatusPalette.failed.background,
        onClose: onClose);
  }
}
