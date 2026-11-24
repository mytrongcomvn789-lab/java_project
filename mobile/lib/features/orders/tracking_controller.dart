import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../../core/realtime/realtime_client.dart';
import '../../core/realtime/realtime_provider.dart';
import 'order_models.dart';
import 'orders_api.dart';
import 'tracking_state.dart';

/// Backend channel name for one order (`OrderChannel.destination`).
String orderTopic(String orderId) => '/topic/orders/$orderId';

/// Live tracking of exactly one order.
///
/// * Subscribes only to `/topic/orders/{orderId}`; leaving the screen disposes
///   the provider, which cancels that subscription (no leak across orders).
/// * Every (re)subscribe is confirmed by a RECEIPT; then the timeline is
///   reloaded from REST, so nothing sent during an outage is missed.
/// * Events are applied only when they are exactly the next version; repeats,
///   older ones and other orders are dropped, a hole triggers a REST reload.
final trackingProvider = NotifierProvider.autoDispose
    .family<TrackingController, TrackingState, String>(TrackingController.new);

class TrackingController
    extends AutoDisposeFamilyNotifier<TrackingState, String> {
  RealtimeSubscription? _sub;
  bool _disposed = false;
  int _loadSeq = 0;
  Timer? _reconcile;

  @override
  TrackingState build(String arg) {
    _disposed = false;
    ref.onDispose(() {
      _disposed = true;
      _loadSeq++;
      _reconcile?.cancel();
      _sub?.cancel();
    });
    final client = ref.watch(realtimeClientProvider);
    if (client != null) {
      _sub = client.subscribe(orderTopic(arg),
          onMessage: _onMessage, onReady: _onReady, onDenied: _onDenied);
    }
    Future.microtask(reload);
    return TrackingState(orderId: arg);
  }

  void _set(TrackingState next) {
    if (!_disposed) state = next;
  }

  void _onReady() {
    if (_disposed) return;
    _set(state.copyWith(live: true, catchUps: state.catchUps + 1));
    reload(); // catch-up after (re)subscribe
  }

  void _onDenied() {
    if (_disposed) return;
    _set(state.copyWith(live: false, denied: true));
  }

  void _onMessage(String body) {
    if (_disposed) return;
    final event = OrderRealtimeEvent.tryParse(body);
    if (event == null) return;
    final result = applyRealtimeEvent(state, event);
    _set(result.state);
    switch (result.outcome) {
      case EventOutcome.applied:
        // Confirm and enrich the optimistic entry from REST shortly after.
        _reconcile?.cancel();
        _reconcile = Timer(const Duration(milliseconds: 400), reload);
      case EventOutcome.gap:
        reload();
      case EventOutcome.duplicate ||
            EventOutcome.stale ||
            EventOutcome.foreign:
        break;
    }
  }

  /// REST is the source of truth. A response that returns after a newer
  /// reload started is discarded.
  Future<void> reload() async {
    if (_disposed) return;
    final seq = ++_loadSeq;
    _set(state.copyWith(refreshing: true));
    try {
      final snapshot = await ref.read(ordersApiProvider).tracking(arg);
      if (_disposed || seq != _loadSeq) return;
      _set(mergeSnapshot(state, snapshot));
    } catch (error) {
      if (_disposed || seq != _loadSeq) return;
      final notFound = error is DioException && error.response?.statusCode == 404;
      _set(state.copyWith(
          refreshing: false,
          error: notFound ? 'Không tìm thấy đơn hàng.' : apiError(error)));
    }
  }
}
