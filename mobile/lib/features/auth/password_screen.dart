import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/dio_client.dart';
import '../../core/theme/app_theme.dart';
import 'screens.dart' show appMessengerKey;
import 'session.dart';

/// Kiểm tra mật khẩu mới theo đúng quy tắc của máy chủ (8–72 ký tự, có chữ và số). Máy chủ vẫn là nơi
/// quyết định; kiểm tra ở đây chỉ để báo lỗi sớm.
abstract final class PasswordRules {
  static String? current(String? v) =>
      (v == null || v.isEmpty) ? 'Nhập mật khẩu hiện tại.' : null;

  static String? next(String? v, {required String current}) {
    final t = v ?? '';
    if (t.isEmpty) return 'Nhập mật khẩu mới.';
    if (t.length < 8 || t.length > 72) return 'Mật khẩu dài từ 8 đến 72 ký tự.';
    if (!RegExp(r'[A-Za-z]').hasMatch(t) || !RegExp(r'\d').hasMatch(t)) {
      return 'Mật khẩu phải có ít nhất 1 chữ cái và 1 chữ số.';
    }
    if (t == current) return 'Mật khẩu mới phải khác mật khẩu hiện tại.';
    return null;
  }

  static String? confirm(String? v, {required String next}) =>
      v != next ? 'Mật khẩu nhập lại không khớp.' : null;
}

/// Đổi mật khẩu. Thành công thì máy chủ thu hồi mọi phiên nên app đăng xuất và về màn đăng nhập.
class ChangePasswordScreen extends ConsumerStatefulWidget {
  const ChangePasswordScreen({super.key});
  @override
  ConsumerState<ChangePasswordScreen> createState() => _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends ConsumerState<ChangePasswordScreen> {
  final _form = GlobalKey<FormState>();
  final _current = TextEditingController();
  final _next = TextEditingController();
  final _confirm = TextEditingController();
  bool _busy = false, _show = false;
  String? _error;

  @override
  void dispose() {
    _current.dispose();
    _next.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy || !_form.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(sessionProvider).changePassword(_current.text, _next.text);
      appMessengerKey.currentState?.showSnackBar(
          const SnackBar(content: Text('Đã đổi mật khẩu. Vui lòng đăng nhập lại.')));
    } catch (e) {
      if (mounted) setState(() => _error = apiError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  InputDecoration _deco(String label) => InputDecoration(
      labelText: label,
      suffixIcon: IconButton(
          tooltip: _show ? 'Ẩn mật khẩu' : 'Hiện mật khẩu',
          icon: Icon(_show ? Icons.visibility_off_outlined : Icons.visibility_outlined),
          onPressed: () => setState(() => _show = !_show)));

  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(
          title: const Text('Đổi mật khẩu'),
          leading: IconButton(
              tooltip: 'Quay lại',
              icon: const Icon(Icons.arrow_back),
              onPressed: () => context.go('/profile'))),
      body: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Form(
              key: _form,
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                        color: AppColors.surface,
                        borderRadius: BorderRadius.circular(AppRadii.card),
                        boxShadow: cardShadow),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                      if (_error != null)
                        Container(
                            key: const Key('password-error'),
                            margin: const EdgeInsets.only(bottom: 12),
                            padding: const EdgeInsets.all(12),
                            decoration: BoxDecoration(
                                color: StatusPalette.failed.background,
                                borderRadius: BorderRadius.circular(AppRadii.button)),
                            child: Text(_error!)),
                      TextFormField(
                          key: const Key('password-current'),
                          controller: _current,
                          enabled: !_busy,
                          obscureText: !_show,
                          autofillHints: const [AutofillHints.password],
                          decoration: _deco('Mật khẩu hiện tại'),
                          validator: PasswordRules.current),
                      const SizedBox(height: 12),
                      TextFormField(
                          key: const Key('password-new'),
                          controller: _next,
                          enabled: !_busy,
                          obscureText: !_show,
                          autofillHints: const [AutofillHints.newPassword],
                          decoration: _deco('Mật khẩu mới'),
                          validator: (v) => PasswordRules.next(v, current: _current.text)),
                      const SizedBox(height: 12),
                      TextFormField(
                          key: const Key('password-confirm'),
                          controller: _confirm,
                          enabled: !_busy,
                          obscureText: !_show,
                          decoration: _deco('Nhập lại mật khẩu mới'),
                          validator: (v) => PasswordRules.confirm(v, next: _next.text)),
                      const SizedBox(height: 8),
                      const Text('8 đến 72 ký tự, có ít nhất 1 chữ cái và 1 chữ số. Sau khi đổi, bạn cần đăng nhập lại trên mọi thiết bị.',
                          style: TextStyle(color: AppColors.textMuted, fontSize: 13, height: 1.3)),
                    ])),
                const SizedBox(height: 16),
                FilledButton(
                    key: const Key('password-submit'),
                    onPressed: _busy ? null : _submit,
                    child: _busy
                        ? const SizedBox(
                            width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('Đổi mật khẩu')),
              ]))));
}
