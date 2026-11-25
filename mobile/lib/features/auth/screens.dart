import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/dio_client.dart';
import '../../core/theme/app_theme.dart';
import 'drone_hero.dart';
import 'session.dart';

final appMessengerKey = GlobalKey<ScaffoldMessengerState>();

Future<void> signOut(WidgetRef ref) async {
  try {
    final message = await ref.read(sessionProvider).logout();
    if (message != null) {
      appMessengerKey.currentState
          ?.showSnackBar(SnackBar(content: Text(message)));
    }
  } catch (_) {
    appMessengerKey.currentState?.showSnackBar(const SnackBar(
        content: Text(
            'Không thể xóa thông tin phiên khỏi bộ nhớ an toàn. Vui lòng thử lại.')));
  }
}

class SessionScreen extends ConsumerWidget {
  const SessionScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider);
    return Scaffold(
        body: Center(
            child: Padding(
                padding: const EdgeInsets.all(24),
                child: session.phase == SessionPhase.restoring
                    ? const Column(mainAxisSize: MainAxisSize.min, children: [
                        CircularProgressIndicator(),
                        SizedBox(height: 16),
                        Text('Đang khôi phục phiên…')
                      ])
                    : Column(mainAxisSize: MainAxisSize.min, children: [
                        Text(session.message ?? 'Không thể khôi phục phiên.'),
                        const SizedBox(height: 16),
                        FilledButton(
                            onPressed: session.restore,
                            child: const Text('Thử lại')),
                        TextButton(
                            onPressed: () => signOut(ref),
                            child: const Text('Đăng nhập tài khoản khác')),
                      ]))));
  }
}

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});
  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _form = GlobalKey<FormState>();
  final _email = TextEditingController(), _password = TextEditingController();
  bool _busy = false, _obscure = true;
  String? _error;
  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy || !_form.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(sessionProvider).login(_email.text, _password.text);
    } catch (error) {
      if (mounted) setState(() => _error = apiError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
  /// Vai trò chỉ là gợi ý hiển thị: server quyết định vai trò thật theo tài khoản và app tự
  /// chuyển tới màn chính tương ứng sau khi đăng nhập.
  _LoginRole _role = _LoginRole.customer;

  @override
  Widget build(BuildContext context) => Scaffold(
      backgroundColor: AppColors.dark,
      body: LayoutBuilder(builder: (context, box) {
        final heroH = (box.maxHeight * 0.34).clamp(200.0, 320.0);
        return Stack(children: [
          Positioned(top: 0, left: 0, right: 0, height: heroH, child: const DroneHero()),
          const Positioned(
              top: 0,
              left: 0,
              child: SafeArea(
                  child: Padding(
                      padding: EdgeInsets.fromLTRB(24, 16, 0, 0), child: _Logo()))),
          SingleChildScrollView(
              padding: EdgeInsets.only(top: heroH - 30),
              child: ConstrainedBox(
                  constraints: BoxConstraints(minHeight: box.maxHeight - heroH + 30),
                  child: Container(
                      decoration: const BoxDecoration(
                          color: AppColors.surface,
                          borderRadius: BorderRadius.vertical(
                              top: Radius.circular(AppRadii.sheet))),
                      padding: const EdgeInsets.fromLTRB(24, 28, 24, 24),
                      child: Center(
                          child: ConstrainedBox(
                              constraints: const BoxConstraints(maxWidth: 440),
                              child: _formBody(context)))))),
        ]);
      }));

  Widget _formBody(BuildContext context) {
    final theme = Theme.of(context);
    return AutofillGroup(
        child: Form(
            key: _form,
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Text('Giao hàng bằng drone,\ntheo dõi từng phút',
                  style: theme.textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.w800, height: 1.25, color: AppColors.text)),
              const SizedBox(height: 8),
              const Text(
                  'Đăng nhập để tạo đơn, theo dõi hành trình và vận hành trạm.',
                  style: TextStyle(color: AppColors.textMuted, height: 1.4)),
              const SizedBox(height: 20),
              _RoleSwitch(
                  selected: _role,
                  onChanged: _busy ? null : (r) => setState(() => _role = r)),
              const SizedBox(height: 20),
              TextFormField(
                  controller: _email,
                  enabled: !_busy,
                  decoration: InputDecoration(
                      labelText: 'Email',
                      hintText: _role == _LoginRole.customer
                          ? 'ten@email.com'
                          : 'nhanvien@tram.vn',
                      prefixIcon: const Icon(Icons.mail_outline)),
                  keyboardType: TextInputType.emailAddress,
                  autofillHints: const [AutofillHints.username],
                  textInputAction: TextInputAction.next,
                  validator: (value) => value == null ||
                          !RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$').hasMatch(value.trim())
                      ? 'Nhập địa chỉ email hợp lệ.'
                      : null),
              const SizedBox(height: 16),
              TextFormField(
                  controller: _password,
                  enabled: !_busy,
                  obscureText: _obscure,
                  autocorrect: false,
                  enableSuggestions: false,
                  autofillHints: const [AutofillHints.password],
                  onFieldSubmitted: (_) => _submit(),
                  decoration: InputDecoration(
                      labelText: 'Mật khẩu',
                      prefixIcon: const Icon(Icons.lock_outline),
                      suffixIcon: IconButton(
                          tooltip: _obscure ? 'Hiện mật khẩu' : 'Ẩn mật khẩu',
                          onPressed: () => setState(() => _obscure = !_obscure),
                          icon: Icon(_obscure
                              ? Icons.visibility_outlined
                              : Icons.visibility_off_outlined))),
                  validator: (value) =>
                      value == null || value.isEmpty ? 'Nhập mật khẩu.' : null),
              if (_error != null)
                Container(
                    margin: const EdgeInsets.only(top: 16),
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                        color: StatusPalette.failed.background,
                        borderRadius: BorderRadius.circular(12)),
                    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      const Icon(Icons.error_outline, size: 18, color: AppColors.danger),
                      const SizedBox(width: 8),
                      Expanded(
                          child: Text(_error!,
                              style: const TextStyle(color: AppColors.danger),
                              semanticsLabel: 'Lỗi đăng nhập: $_error')),
                    ])),
              const SizedBox(height: 24),
              FilledButton(
                  onPressed: _busy ? null : _submit,
                  child: Text(_busy ? 'Đang đăng nhập…' : 'Đăng nhập')),
              const SizedBox(height: 16),
              Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                const Icon(Icons.lock_outline, size: 14, color: AppColors.textMuted),
                const SizedBox(width: 6),
                Flexible(
                    child: Text(
                        _role == _LoginRole.customer
                            ? 'Dành cho khách hàng. Phiên đăng nhập được bảo vệ bằng JWT.'
                            : 'Dành cho nhân viên vận hành trạm. Phiên được bảo vệ bằng JWT.',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                            color: AppColors.textMuted, fontSize: 12, height: 1.4))),
              ]),
            ])));
  }
}


enum _LoginRole { customer, operator }

class _Logo extends StatelessWidget {
  const _Logo();
  @override
  Widget build(BuildContext context) => Row(mainAxisSize: MainAxisSize.min, children: [
        Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(
                color: AppColors.amber, borderRadius: BorderRadius.circular(11)),
            child: const Icon(Icons.flight, color: AppColors.dark, size: 22)),
        const SizedBox(width: 10),
        const Text('SmartDrone',
            style: TextStyle(
                color: Colors.white, fontSize: 20, fontWeight: FontWeight.w800)),
      ]);
}

/// Chọn vai trò hiển thị trên màn đăng nhập (Khách hàng / Vận hành trạm). Vùng chạm cao 48.
class _RoleSwitch extends StatelessWidget {
  const _RoleSwitch({required this.selected, required this.onChanged});
  final _LoginRole selected;
  final ValueChanged<_LoginRole>? onChanged;

  @override
  Widget build(BuildContext context) => Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
          color: AppColors.background, borderRadius: BorderRadius.circular(14)),
      child: Row(children: [
        _seg('Khách hàng', Icons.person_outline, _LoginRole.customer),
        _seg('Vận hành trạm', Icons.warehouse_outlined, _LoginRole.operator),
      ]));

  Widget _seg(String label, IconData icon, _LoginRole role) {
    final on = selected == role;
    return Expanded(
        child: Semantics(
            button: true,
            selected: on,
            label: label,
            excludeSemantics: true,
            child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: onChanged == null ? null : () => onChanged!(role),
                child: AnimatedContainer(
                    duration: const Duration(milliseconds: 150),
                    height: 44,
                    decoration: BoxDecoration(
                        color: on ? AppColors.surface : Colors.transparent,
                        borderRadius: BorderRadius.circular(11),
                        boxShadow: on ? cardShadow : null),
                    child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                      Icon(icon,
                          size: 18,
                          color: on ? AppColors.primary : AppColors.textMuted),
                      const SizedBox(width: 6),
                      Flexible(
                          child: Text(label,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                  fontSize: 14,
                                  fontWeight: on ? FontWeight.w700 : FontWeight.w500,
                                  color: on ? AppColors.primary : AppColors.textMuted))),
                    ])))));
  }
}
class WorkspaceScreen extends ConsumerWidget {
  const WorkspaceScreen({super.key, required this.operator});
  final bool operator;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(sessionProvider).user!;
    return Scaffold(
        appBar: AppBar(
            title: Text(operator ? 'Vận hành trạm' : 'Khách hàng'),
            actions: [
              IconButton(
                  tooltip: 'Đăng xuất',
                  onPressed: () => signOut(ref),
                  icon: const Icon(Icons.logout))
            ]),
        body: ListView(padding: const EdgeInsets.all(24), children: [
          Text('Xin chào, ${user.name.isEmpty ? user.email : user.name}',
              style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 12),
          Text(operator
              ? 'Vai trò: Người vận hành trạm'
              : 'Vai trò: Khách hàng'),
          const SizedBox(height: 24),
          Card(
              child: ListTile(
                  leading: const Icon(Icons.account_circle),
                  title: const Text('Hồ sơ của tôi'),
                  subtitle: Text(user.email),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => context.go('/profile'))),
          const SizedBox(height: 16),
          Text(operator
              ? 'Nền tài khoản đã sẵn sàng. Nghiệp vụ vận hành được triển khai ở các task mobile tiếp theo.'
              : 'Nền tài khoản đã sẵn sàng. Tạo và quản lý đơn hàng sẽ được bổ sung ở mục 6.2.'),
        ]));
  }
}

class UnsupportedScreen extends ConsumerWidget {
  const UnsupportedScreen({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
      appBar: AppBar(title: const Text('Vai trò chưa được hỗ trợ')),
      body: Padding(
          padding: const EdgeInsets.all(24),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('Ứng dụng này dành cho CUSTOMER và STATION_OPERATOR. '
                'Vui lòng dùng ứng dụng web cho vai trò quản trị/điều phối.'),
            const SizedBox(height: 16),
            FilledButton(
                onPressed: () => signOut(ref), child: const Text('Đăng xuất')),
          ])));
}

class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});
  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  final _form = GlobalKey<FormState>();
  late final TextEditingController _name, _phone;
  bool _busy = false;
  String? _message;
  @override
  void initState() {
    super.initState();
    final user = ref.read(sessionProvider).user!;
    _name = TextEditingController(text: user.name);
    _phone = TextEditingController(text: user.phone);
  }

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_busy || !_form.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      await ref.read(sessionProvider).updateProfile(_name.text, _phone.text);
      if (mounted) setState(() => _message = 'Đã lưu hồ sơ.');
    } catch (error) {
      if (mounted) setState(() => _message = apiError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(sessionProvider).user;
    if (user == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return Scaffold(
        appBar: AppBar(
            title: const Text('Hồ sơ của tôi'),
            leading: IconButton(
                tooltip: 'Về trang chính',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => context.go(user.home))),
        body: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Form(
                key: _form,
                child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(user.email,
                          style: Theme.of(context).textTheme.titleMedium),
                      Text('Vai trò: ${user.role}'),
                      const SizedBox(height: 24),
                      TextFormField(
                          controller: _name,
                          enabled: !_busy,
                          maxLength: 150,
                          decoration:
                              const InputDecoration(labelText: 'Họ tên'),
                          validator: (v) => (v?.trim().length ?? 0) > 150
                              ? 'Tối đa 150 ký tự.'
                              : null),
                      const SizedBox(height: 16),
                      TextFormField(
                          controller: _phone,
                          enabled: !_busy,
                          keyboardType: TextInputType.phone,
                          decoration:
                              const InputDecoration(labelText: 'Số điện thoại'),
                          validator: (v) => v != null &&
                                  v.trim().isNotEmpty &&
                                  !RegExp(r'^\+?[0-9]{9,15}$')
                                      .hasMatch(v.trim())
                              ? 'Nhập 9–15 chữ số, có thể bắt đầu bằng +.'
                              : null),
                      const SizedBox(height: 20),
                      if (_message != null)
                        Padding(
                            padding: const EdgeInsets.only(bottom: 16),
                            child: Text(_message!)),
                      FilledButton(
                          onPressed: _busy ? null : _save,
                          child: Text(_busy ? 'Đang lưu…' : 'Lưu hồ sơ')),
                      TextButton(
                          onPressed: _busy ? null : () => signOut(ref),
                          child: const Text('Đăng xuất')),
                    ]))));
  }
}
