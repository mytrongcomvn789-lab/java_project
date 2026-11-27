import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../../core/realtime/realtime_provider.dart';
import '../orders/customer_order_models.dart';
import '../orders/customer_orders_api.dart';

const _addressPageSize = 50;
const _maxAddressPages = 20; // chặn vòng lặp nếu server trả total bất thường

class AddressListState {
  const AddressListState(
      {this.items = const [], this.loading = true, this.error, this.actionError});
  final List<Address> items;
  final bool loading;

  /// Lỗi tải danh sách khi chưa có gì để hiện.
  final ApiFailure? error;

  /// Lỗi của thao tác xóa (đã có danh sách để hiện).
  final ApiFailure? actionError;
}

/// Sổ địa chỉ của tôi. Gắn với user đang đăng nhập nên đổi tài khoản không bao
/// giờ thấy địa chỉ của người trước. Server là nguồn quyết định (địa chỉ phải thuộc user).
class AddressController extends AutoDisposeNotifier<AddressListState> {
  int _generation = 0;

  @override
  AddressListState build() {
    ref.watch(realtimeUserProvider);
    ref.onDispose(() => _generation++);
    Future.microtask(reload);
    return const AddressListState();
  }

  CustomerOrdersApi get _api => ref.read(customerOrdersApiProvider);

  Future<void> reload() async {
    final gen = ++_generation;
    final hadItems = state.items.isNotEmpty;
    state = AddressListState(items: state.items, loading: !hadItems);
    try {
      final all = <Address>[];
      for (var page = 1; page <= _maxAddressPages; page++) {
        final res =
            await _api.listAddresses(page: page, pageSize: _addressPageSize);
        if (gen != _generation) return;
        all.addAll(res.items);
        if (!res.hasMore || res.items.isEmpty) break;
      }
      state = AddressListState(items: all, loading: false);
    } catch (e) {
      if (gen != _generation) return;
      final failure = toApiFailure(e);
      state = AddressListState(
          items: state.items,
          loading: false,
          error: hadItems ? null : failure,
          actionError: hadItems ? failure : null);
    }
  }

  /// Thêm địa chỉ; ném [ApiFailure] để form hiện lỗi và giữ nguyên dữ liệu đã nhập.
  Future<Address> add(AddressInput input) async {
    final created = await _api.createAddress(input);
    state = AddressListState(items: [created, ...state.items], loading: false);
    return created;
  }

  Future<Address> update(String id, AddressInput input) async {
    final updated = await _api.updateAddress(id, input);
    state = AddressListState(
        items: [for (final a in state.items) a.id == id ? updated : a],
        loading: false);
    return updated;
  }

  /// Xóa khỏi sổ. Trả true nếu server xác nhận (404 coi như đã không còn).
  Future<bool> remove(String id) async {
    try {
      await _api.deleteAddress(id);
    } catch (e) {
      final failure = toApiFailure(e);
      if (failure.kind != FailureKind.notFound) {
        state = AddressListState(
            items: state.items, loading: false, actionError: failure);
        return false;
      }
    }
    state = AddressListState(
        items: [for (final a in state.items) if (a.id != id) a], loading: false);
    return true;
  }

  void dismissError() =>
      state = AddressListState(items: state.items, loading: false);
}

final addressesProvider =
    NotifierProvider.autoDispose<AddressController, AddressListState>(
        AddressController.new);

/// Kiểm tra nhanh ở client cho form (chỉ để báo sớm; server kiểm tra lại tất cả).
class AddressValidation {
  static final _phone = RegExp(r'^\+?[0-9]{9,15}$');

  static String? required(String? v, {int max = 500, String name = 'Trường này'}) {
    final t = v?.trim() ?? '';
    if (t.isEmpty) return '$name không được để trống.';
    if (t.length > max) return '$name tối đa $max ký tự.';
    return null;
  }

  static String? phone(String? v) {
    final t = v?.trim() ?? '';
    if (t.isEmpty) return 'Số điện thoại không được để trống.';
    if (!_phone.hasMatch(t)) return 'Số điện thoại gồm 9–15 chữ số, có thể bắt đầu bằng +.';
    return null;
  }
}
