import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/realtime/realtime_client.dart';
import '../../core/realtime/realtime_provider.dart';
import '../orders/order_models.dart';
import '../orders/orders_api.dart';

class NotificationsState {
  const NotificationsState(
      {this.items = const [],
      this.incoming,
      this.incomingSeq = 0,
      this.loaded = false,
      this.error = false});
  final List<AppNotification> items;

  /// Last notification that arrived live (not via REST catch-up).
  final AppNotification? incoming;

  /// Bumps on every live arrival so listeners can react to repeats.
  final int incomingSeq;
  final bool loaded, error;
  int get unread => items.where((n) => n.unread).length;

  NotificationsState copyWith(
          {List<AppNotification>? items,
          AppNotification? incoming,
          int? incomingSeq,
          bool? loaded,
          bool? error}) =>
      NotificationsState(
          items: items ?? this.items,
          incoming: incoming ?? this.incoming,
          incomingSeq: incomingSeq ?? this.incomingSeq,
          loaded: loaded ?? this.loaded,
          error: error ?? this.error);
}

String notificationTopic(String userId) => '/topic/notifications/$userId';

/// Foreground notifications for the signed-in customer (contract 4.9):
/// socket only announces; REST is reloaded after every (re)subscribe and
/// events are de-duplicated by `eventId`. Rebuilt (old state dropped) when the
/// signed-in user or the socket changes, so accounts never share items.
final notificationsProvider =
    NotifierProvider<NotificationsController, NotificationsState>(
        NotificationsController.new);

class NotificationsController extends Notifier<NotificationsState> {
  RealtimeSubscription? _sub;
  bool _disposed = false;
  int _loadSeq = 0;

  @override
  NotificationsState build() {
    _disposed = false;
    ref.onDispose(() {
      _disposed = true;
      _loadSeq++; // late REST answers of the previous owner are discarded
      _sub?.cancel();
      _sub = null;
    });
    final userId = ref.watch(realtimeUserProvider);
    final client = ref.watch(realtimeClientProvider);
    if (userId != null && client != null) {
      _sub = client.subscribe(notificationTopic(userId),
          onMessage: _onMessage, onReady: reload);
    }
    return const NotificationsState();
  }

  void _onMessage(String body) {
    if (_disposed) return;
    final n = AppNotification.tryParseEnvelope(body);
    if (n == null) return;
    if (state.items.any((e) => e.eventId == n.eventId)) return; // duplicate
    state = state.copyWith(
        items: [n, ...state.items],
        incoming: n,
        incomingSeq: state.incomingSeq + 1);
  }

  Future<void> reload() async {
    if (_disposed) return;
    final seq = ++_loadSeq;
    try {
      final fetched = await ref.read(ordersApiProvider).notifications();
      if (_disposed || seq != _loadSeq) return;
      final known = {for (final n in fetched) n.eventId};
      // Keep live items REST has not caught up with yet.
      final extra = state.items.where((n) => !known.contains(n.eventId));
      final merged = [...extra, ...fetched]
        ..sort((a, b) => (b.createdAt ?? DateTime(0))
            .compareTo(a.createdAt ?? DateTime(0)));
      state = state.copyWith(items: merged, loaded: true, error: false);
    } catch (_) {
      if (_disposed || seq != _loadSeq) return;
      state = state.copyWith(error: true);
    }
  }

  Future<void> markRead(AppNotification n) async {
    if (!n.unread) return;
    state = state.copyWith(
        items: [for (final e in state.items) e.id == n.id ? e.markRead() : e]);
    try {
      await ref.read(ordersApiProvider).markRead(n.id);
    } catch (_) {
      // Shown as read locally; the next REST reload restores the truth.
    }
  }
}
