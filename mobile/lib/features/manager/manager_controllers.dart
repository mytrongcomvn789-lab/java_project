import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../ai/ai_errors.dart';
import '../ai/ai_models.dart';
import '../auth/session.dart';
import 'manager_api.dart';

/// Id của quản lý hậu cần đang đăng nhập, hoặc null. Đăng xuất hoặc đổi tài khoản thì mọi số liệu của
/// người trước bị bỏ (các provider bên dưới đều theo dõi id này).
final managerUserProvider = Provider<String?>((ref) => ref.watch(sessionProvider.select((s) =>
    s.phase == SessionPhase.signedIn && s.user?.role == 'LOGISTICS_MANAGER' ? s.user!.id : null)));

/// Kỳ thống kê tính bằng ngày (7 hoặc 30), tính tới hôm nay theo giờ Việt Nam.
const statsPeriods = [7, 30];
final statsDaysProvider = StateProvider.autoDispose<int>((ref) => 30);

final statsSummaryProvider = FutureProvider.autoDispose.family<StatsSummary, int>((ref, days) {
  ref.watch(managerUserProvider);
  return ref.read(managerApiProvider).summary(days);
});

final statsDailyProvider = FutureProvider.autoDispose.family<List<DailyPoint>, int>((ref, days) {
  ref.watch(managerUserProvider);
  return ref.read(managerApiProvider).daily(days);
});

final stationLoadProvider = FutureProvider.autoDispose<List<StationLoad>>((ref) {
  ref.watch(managerUserProvider);
  return ref.read(managerApiProvider).stationLoad();
});

/// Nhật ký kiểm toán phân trang 20 dòng (mới nhất trước), có thể lọc theo một loại hành động
/// ([arg] rỗng = tất cả). Phản hồi muộn của phiên cũ bị bỏ qua.
class AuditState {
  const AuditState(
      {this.items = const [], this.total = 0, this.page = 0, this.loading = true, this.loadingMore = false, this.error, this.moreError});
  final List<AuditEvent> items;
  final int total, page;
  final bool loading, loadingMore;
  final ApiFailure? error, moreError;
  bool get hasMore => items.isNotEmpty && items.length < total && page > 0;
}

class AuditController extends AutoDisposeFamilyNotifier<AuditState, String> {
  int _generation = 0;

  @override
  AuditState build(String arg) {
    ref.watch(managerUserProvider);
    ref.onDispose(() => _generation++);
    Future.microtask(reload);
    return const AuditState();
  }

  ManagerApi get _api => ref.read(managerApiProvider);
  String? get _action => arg.isEmpty ? null : arg;

  Future<void> reload() async {
    final gen = ++_generation;
    final had = state.items.isNotEmpty;
    state = AuditState(items: state.items, total: state.total, page: state.page, loading: !had);
    try {
      final p = await _api.audit(page: 1, action: _action);
      if (gen != _generation) return;
      state = AuditState(items: p.items, total: p.total, page: 1, loading: false);
    } catch (e) {
      if (gen != _generation) return;
      final f = toApiFailure(e);
      state = AuditState(
          items: state.items,
          total: state.total,
          page: state.page,
          loading: false,
          error: had ? null : f,
          moreError: had ? f : null);
    }
  }

  Future<void> loadMore() async {
    final s = state;
    if (s.loading || s.loadingMore || !s.hasMore) return;
    final gen = _generation;
    state = AuditState(items: s.items, total: s.total, page: s.page, loading: false, loadingMore: true);
    try {
      final p = await _api.audit(page: s.page + 1, action: _action);
      if (gen != _generation) return;
      final ids = {for (final e in s.items) e.id};
      state = AuditState(
          items: [...s.items, ...p.items.where((e) => !ids.contains(e.id))],
          total: p.total,
          page: s.page + 1,
          loading: false);
    } catch (e) {
      if (gen != _generation) return;
      state = AuditState(
          items: s.items, total: s.total, page: s.page, loading: false, moreError: toApiFailure(e));
    }
  }
}

final auditProvider =
    NotifierProvider.autoDispose.family<AuditController, AuditState, String>(AuditController.new);

enum AnalyticsPhase { idle, loading, success, error }

class AnalyticsState {
  const AnalyticsState(this.phase, {this.envelope, this.error});
  final AnalyticsPhase phase;
  final AiEnvelope<AnalyticsResult>? envelope;
  final AiErrorInfo? error;
}

/// Phân tích vận hành của kỳ đang chọn. Chỉ chạy khi bấm nút (có thể tốn hạn mức AI). Đổi kỳ hoặc đổi
/// tài khoản thì kết quả cũ bị bỏ để không hiện nhận định của kỳ khác.
class AnalyticsController extends AutoDisposeNotifier<AnalyticsState> {
  int _run = 0;

  @override
  AnalyticsState build() {
    ref.watch(managerUserProvider);
    ref.watch(statsDaysProvider);
    ref.onDispose(() => _run++);
    return const AnalyticsState(AnalyticsPhase.idle);
  }

  Future<void> start() async {
    if (state.phase == AnalyticsPhase.loading) return;
    final run = ++_run;
    state = const AnalyticsState(AnalyticsPhase.loading);
    try {
      final env = await ref.read(managerApiProvider).analyze(ref.read(statsDaysProvider));
      if (run != _run) return;
      state = AnalyticsState(AnalyticsPhase.success, envelope: env);
    } catch (e) {
      if (run != _run) return;
      state = AnalyticsState(AnalyticsPhase.error, error: describeAiFailure(e));
    }
  }
}

final analyticsProvider =
    NotifierProvider.autoDispose<AnalyticsController, AnalyticsState>(AnalyticsController.new);
