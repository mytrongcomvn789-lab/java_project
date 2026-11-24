import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/dio_client.dart';
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

  @override
  Widget build(BuildContext context) => Scaffold(
      body: SafeArea(
          child: Center(
              child: SingleChildScrollView(
                  padding: const EdgeInsets.all(24),
                  child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 440),
                      child: AutofillGroup(
                          child: Form(
                              key: _form,
                              child: Column(
                                  crossAxisAlignment:
                                      CrossAxisAlignment.stretch,
                                  children: [
                                    Icon(Icons.flight_takeoff,
                                        size: 56,
                                        color: Theme.of(context)
                                            .colorScheme
                                            .primary),
                                    const SizedBox(height: 16),
                                    Text('SmartDroneDelivery',
                                        textAlign: TextAlign.center,
                                        style: Theme.of(context)
                                            .textTheme
                                            .headlineSmall),
                                    const SizedBox(height: 8),
                                    const Text('Đăng nhập để tiếp tục',
                                        textAlign: TextAlign.center),
                                    const SizedBox(height: 28),
                                    TextFormField(
                                        controller: _email,
                                        enabled: !_busy,
                                        decoration: const InputDecoration(
                                            labelText: 'Email'),
                                        keyboardType:
                                            TextInputType.emailAddress,
                                        autofillHints: const [
                                          AutofillHints.username
                                        ],
                                        textInputAction: TextInputAction.next,
                                        validator: (value) => value == null ||
                                                !RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$')
                                                    .hasMatch(value.trim())
                                            ? 'Nhập địa chỉ email hợp lệ.'
                                            : null),
                                    const SizedBox(height: 16),
                                    TextFormField(
                                        controller: _password,
                                        enabled: !_busy,
                                        obscureText: _obscure,
                                        autocorrect: false,
                                        enableSuggestions: false,
                                        autofillHints: const [
                                          AutofillHints.password
                                        ],
                                        onFieldSubmitted: (_) => _submit(),
                                        decoration: InputDecoration(
                                            labelText: 'Mật khẩu',
                                            suffixIcon: IconButton(
                                                tooltip: _obscure
                                                    ? 'Hiện mật khẩu'
                                                    : 'Ẩn mật khẩu',
                                                onPressed: () => setState(
                                                    () => _obscure = !_obscure),
                                                icon: Icon(_obscure
                                                    ? Icons.visibility
                                                    : Icons.visibility_off))),
                                        validator: (value) =>
                                            value == null || value.isEmpty
                                                ? 'Nhập mật khẩu.'
                                                : null),
                                    if (_error != null)
                                      Padding(
                                          padding:
                                              const EdgeInsets.only(top: 16),
                                          child: Text(_error!,
                                              style: TextStyle(
                                                  color: Theme.of(context)
                                                      .colorScheme
                                                      .error),
                                              semanticsLabel:
                                                  'Lỗi đăng nhập: $_error')),
                                    const SizedBox(height: 24),
                                    FilledButton(
                                        onPressed: _busy ? null : _submit,
                                        child: Padding(
                                            padding: const EdgeInsets.all(12),
                                            child: Text(_busy
                                                ? 'Đang đăng nhập…'
                                                : 'Đăng nhập'))),
                                    const SizedBox(height: 16),
                                    const Text(
                                        'Dành cho khách hàng và người vận hành trạm.',
                                        textAlign: TextAlign.center),
                                  ]))))))));
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
