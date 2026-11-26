import 'package:smartdrone_mobile/core/offline/pending_action.dart';
import 'package:smartdrone_mobile/features/operator/operator_sync.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Kho hàng đợi trong bộ nhớ cho test (bản thật dùng kho mã hóa của hệ điều hành).
class MemoryPendingStore implements PendingStore {
  final data = <String, List<PendingAction>>{};
  bool failWrites = false;
  int writes = 0;

  @override
  Future<List<PendingAction>> read(String userId) async =>
      List.of(data[userId] ?? const []);

  @override
  Future<void> write(String userId, List<PendingAction> actions) async {
    if (failWrites) throw StateError('disk full');
    writes++;
    data[userId] = List.of(actions);
  }
}

/// Overrides dùng chung: kho trong bộ nhớ và tắt tự thử lại theo timer (test tự gọi syncNow).
List<Override> pendingOverrides(MemoryPendingStore store) => [
      pendingStoreProvider.overrideWithValue(store),
      syncAutoRetryProvider.overrideWithValue(false),
    ];
