import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_client.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/core/realtime/stomp_frame.dart';
import 'package:smartdrone_mobile/features/notifications/notifications_controller.dart';
import 'package:smartdrone_mobile/features/orders/order_models.dart';
import 'package:smartdrone_mobile/features/orders/orders_api.dart';
import 'package:smartdrone_mobile/features/orders/tracking_controller.dart';
import 'package:smartdrone_mobile/features/orders/tracking_state.dart';

import 'realtime_test.dart' show FakeSocket, eventJson, orderA, orderB, settle;

class FakeOrdersApi extends OrdersApi {
  FakeOrdersApi() : super(Dio());
  final versions = <String, int>{orderA: 2, orderB: 1};
  int trackingCalls = 0;
  bool offline = false;
  List<AppNotification> notes = [];

  TrackingSnapshot snap(String id) {
    final v = versions[id]!;
    return TrackingSnapshot(
        orderId: id,
        version: v,
        status: 'S$v',
        statusLabel: 'Trạng thái $v',
        terminal: false,
        timeline: [
          for (var i = 1; i <= v; i++)
            TimelineEntry(eventId: 'e-$id-$i', version: i, toStatus: 'S$i', label: 'Trạng thái $i')
        ]);
  }

  @override
  Future<TrackingSnapshot> tracking(String orderId) async {
    trackingCalls++;
    if (offline) throw DioException.connectionError(requestOptions: RequestOptions(), reason: 'offline');
    return snap(orderId);
  }

  @override
  Future<List<AppNotification>> notifications() async => notes;
  @override
  Future<void> markRead(String notificationId) async {}
}

AppNotification note(String eventId, String orderId) => AppNotification(
    id: 'n-$eventId', eventId: eventId, orderId: orderId, title: 'T', body: 'B', createdAt: DateTime(2026));

void main() {
  late FakeOrdersApi api;
  late List<FakeSocket> sockets;
  late RealtimeClient client;
  late ProviderContainer container;

  Future<void> boot() async {
    api = FakeOrdersApi();
    sockets = [];
    client = RealtimeClient(
      url: Uri.parse('ws://localhost/api/ws'),
      tokens: ({bool forceRefresh = false}) async => 'tok',
      connectSocket: (_) async {
        final s = FakeSocket();
        sockets.add(s);
        return s;
      },
      baseBackoff: const Duration(milliseconds: 10),
      maxBackoff: const Duration(milliseconds: 20),
    );
    container = ProviderContainer(overrides: [
      ordersApiProvider.overrideWithValue(api),
      realtimeClientProvider.overrideWithValue(client),
      realtimeUserProvider.overrideWithValue('user-1'),
    ]);
    client.start();
    await settle();
    sockets.last.push(const StompFrame('CONNECTED'));
    await settle();
  }

  /// Acknowledge every pending SUBSCRIBE of the newest socket.
  Future<void> ackSubscribes() async {
    final s = sockets.last;
    for (final f in s.sentFrames.where((f) => f.command == 'SUBSCRIBE')) {
      s.push(StompFrame('RECEIPT', {'receipt-id': f.headers['receipt']!}));
    }
    await settle();
  }

  void pushEvent(String orderId, int version, {String? eventId}) {
    final sub = sockets.last.sentFrames.lastWhere((f) =>
        f.command == 'SUBSCRIBE' && f.headers['destination'] == orderTopic(orderId));
    sockets.last.push(StompFrame('MESSAGE', {
      'subscription': sub.headers['id']!,
      'destination': orderTopic(orderId)
    }, eventJson(orderId, version, 'S$version', eventId: eventId)));
  }

  tearDown(() {
    container.dispose();
    client.dispose();
  });

  test('subscribes to exactly one order channel and loads timeline over REST', () async {
    await boot();
    final sub = container.listen(trackingProvider(orderA), (_, __) {});
    await settle();
    expect(client.activeDestinations, [orderTopic(orderA)]);
    expect(sub.read().loaded, isTrue);
    expect(sub.read().version, 2);
  });

  test('live event appends instantly, duplicate and stale ones are dropped', () async {
    await boot();
    final sub = container.listen(trackingProvider(orderA), (_, __) {});
    await settle();
    await ackSubscribes();
    api.versions[orderA] = 3;
    pushEvent(orderA, 3);
    await settle();
    expect(sub.read().version, 3);
    pushEvent(orderA, 3); // same event again
    pushEvent(orderA, 2, eventId: 'older-copy'); // older, different id
    await settle();
    final s = sub.read();
    expect(s.version, 3);
    expect(s.timeline.map((e) => e.version), [1, 2, 3]);
    expect(s.duplicates, 1);
    expect(s.stale, 1);
  });

  test('out-of-order v4 before v3 ends with the right final state via REST', () async {
    await boot();
    final sub = container.listen(trackingProvider(orderA), (_, __) {});
    await settle();
    await ackSubscribes();
    api.versions[orderA] = 4;
    pushEvent(orderA, 4); // v3 missing -> gap -> REST reload
    await settle();
    pushEvent(orderA, 3); // late arrival, already known
    await settle();
    final s = sub.read();
    expect(s.version, 4);
    expect(s.timeline.map((e) => e.version), [1, 2, 3, 4]);
    expect(s.gaps, 1);
  });

  test('reconnect re-subscribes and catches up with REST', () async {
    await boot();
    final sub = container.listen(trackingProvider(orderA), (_, __) {});
    await settle();
    await ackSubscribes();
    final before = api.trackingCalls;
    api.versions[orderA] = 5; // changed while the socket was down
    sockets.last.drop();
    await Future<void>.delayed(const Duration(milliseconds: 120));
    sockets.last.push(const StompFrame('CONNECTED'));
    await settle();
    await ackSubscribes();
    expect(api.trackingCalls, greaterThan(before));
    expect(sub.read().version, 5);
  });

  test('REST failure keeps showing the last known timeline', () async {
    await boot();
    final sub = container.listen(trackingProvider(orderA), (_, __) {});
    await settle();
    api.offline = true;
    await container.read(trackingProvider(orderA).notifier).reload();
    await settle();
    expect(sub.read().loaded, isTrue);
    expect(sub.read().version, 2);
    expect(sub.read().error, isNotNull);
  });

  test('another order\'s event on the wire never touches this screen', () async {
    await boot();
    final sub = container.listen(trackingProvider(orderA), (_, __) {});
    await settle();
    await ackSubscribes();
    // Even if a frame for B were delivered on A's subscription id, it is ignored.
    final idA = sockets.last.sentFrames.lastWhere((f) => f.command == 'SUBSCRIBE').headers['id']!;
    sockets.last.push(StompFrame('MESSAGE', {'subscription': idA}, eventJson(orderB, 2, 'S2')));
    await settle();
    expect(sub.read().version, 2);
    expect(sub.read().timeline.every((e) => e.eventId.contains(orderA)), isTrue);
  });

  test('switching orders releases the previous subscription (no leak)', () async {
    await boot();
    final a = container.listen(trackingProvider(orderA), (_, __) {});
    await settle();
    expect(client.activeDestinations, [orderTopic(orderA)]);
    a.close(); // leave order A (autoDispose)
    await settle();
    expect(client.activeDestinations, isEmpty);
    expect(sockets.last.sentFrames.where((f) => f.command == 'UNSUBSCRIBE'), hasLength(1));
    final b = container.listen(trackingProvider(orderB), (_, __) {});
    await settle();
    expect(client.activeDestinations, [orderTopic(orderB)]);
    b.close();
    await settle();
    expect(client.activeDestinations, isEmpty);
  });

  test('notifications: de-duplicated by eventId and caught up over REST', () async {
    await boot();
    api.notes = [note('old', orderA)];
    final sub = container.listen(notificationsProvider, (_, __) {});
    await settle();
    await ackSubscribes();
    expect(client.activeDestinations, [notificationTopic('user-1')]);
    expect(sub.read().items.map((n) => n.eventId), ['old']);
    final id = sockets.last.sentFrames.lastWhere((f) => f.command == 'SUBSCRIBE').headers['id']!;
    const envelope =
        '{"schemaVersion":1,"type":"NOTIFICATION_CREATED","notification":{"id":"n1","eventId":"ev1","orderId":"$orderA","title":"T","body":"B","createdAt":"2026-11-24T08:00:00Z","readAt":null}}';
    sockets.last.push(StompFrame('MESSAGE', {'subscription': id}, envelope));
    sockets.last.push(StompFrame('MESSAGE', {'subscription': id}, envelope)); // duplicate
    await settle();
    expect(sub.read().items.where((n) => n.eventId == 'ev1'), hasLength(1));
    expect(sub.read().unread, 2);
    expect(sub.read().incomingSeq, 1);
  });

  test('disposing the container (logout) cancels listeners and the socket', () async {
    await boot();
    container.listen(trackingProvider(orderA), (_, __) {});
    container.listen(notificationsProvider, (_, __) {});
    await settle();
    expect(client.activeDestinations, hasLength(2));
    container.dispose();
    client.dispose();
    await settle();
    expect(client.activeDestinations, isEmpty);
    expect(client.status, RealtimeStatus.idle);
  });

  test('state classes expose counters starting at zero', () {
    const s = TrackingState(orderId: orderA);
    expect([s.duplicates, s.stale, s.gaps, s.catchUps], [0, 0, 0, 0]);
  });
}
