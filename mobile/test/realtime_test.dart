import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_client.dart';
import 'package:smartdrone_mobile/core/realtime/stomp_frame.dart';
import 'package:smartdrone_mobile/core/realtime/stomp_socket.dart';
import 'package:smartdrone_mobile/features/orders/order_models.dart';
import 'package:smartdrone_mobile/features/orders/tracking_state.dart';

const orderA = '11111111-1111-1111-1111-111111111111';
const orderB = '22222222-2222-2222-2222-222222222222';

String eventJson(String orderId, int version, String status,
        {String? eventId, String? from}) =>
    jsonEncode({
      'schemaVersion': 1,
      'eventId': eventId ?? 'e-$orderId-$version',
      'type': 'ORDER_STATUS_CHANGED',
      'event': 'X',
      'orderId': orderId,
      'fromStatus': from,
      'status': status,
      'version': version,
      'occurredAt': '2026-11-24T08:00:00Z',
    });

OrderRealtimeEvent ev(String orderId, int version, String status,
        {String? eventId}) =>
    OrderRealtimeEvent.tryParse(
        eventJson(orderId, version, status, eventId: eventId))!;

TrackingState loaded(String orderId, int version, String status) =>
    TrackingState(
        orderId: orderId,
        loaded: true,
        version: version,
        status: status,
        statusLabel: labelOfStatus(status),
        timeline: [
          for (var v = 1; v <= version; v++)
            TimelineEntry(
                eventId: 'e-$orderId-$v',
                version: v,
                toStatus: status,
                label: labelOfStatus(status))
        ]);

class FakeSocket implements StompSocket {
  final controller = StreamController<String>();
  final sent = <String>[];
  bool closed = false;
  @override
  Stream<String> get messages => controller.stream;
  @override
  void send(String data) => sent.add(data);
  @override
  Future<void> close() async {
    closed = true;
    if (!controller.isClosed) await controller.close();
  }

  List<StompFrame> get sentFrames =>
      [for (final d in sent) ...StompFrame.parse(d)].where((f) => !f.isHeartbeat).toList();
  void push(StompFrame f) => controller.add(f.encode());
  void drop() => controller.close(); // network loss: remote end closed
}

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 20));

void main() {
  group('StompFrame', () {
    test('round trip with headers and body', () {
      final f = const StompFrame('MESSAGE', {'destination': '/topic/x', 'subscription': 'sub-0'}, '{"a":1}');
      final back = StompFrame.parse(f.encode()).single;
      expect(back.command, 'MESSAGE');
      expect(back.headers['subscription'], 'sub-0');
      expect(back.body, '{"a":1}');
    });
    test('splits several frames and heart-beats in one message', () {
      const a = StompFrame('RECEIPT', {'receipt-id': 'r1'});
      const b = StompFrame('RECEIPT', {'receipt-id': 'r2'});
      final frames = StompFrame.parse('\n${a.encode()}\n${b.encode()}');
      expect(frames.where((f) => !f.isHeartbeat).map((f) => f.headers['receipt-id']), ['r1', 'r2']);
    });
    test('a lone newline is a heart-beat', () {
      expect(StompFrame.parse('\n').single.isHeartbeat, isTrue);
    });
  });

  group('applyRealtimeEvent', () {
    test('next version is applied and appended', () {
      final r = applyRealtimeEvent(loaded(orderA, 2, 'APPROVED'), ev(orderA, 3, 'SCHEDULED'));
      expect(r.outcome, EventOutcome.applied);
      expect(r.state.version, 3);
      expect(r.state.status, 'SCHEDULED');
      expect(r.state.timeline.map((e) => e.version), [1, 2, 3]);
    });
    test('same eventId is a duplicate and changes nothing', () {
      final s = loaded(orderA, 2, 'APPROVED');
      final r = applyRealtimeEvent(s, ev(orderA, 2, 'APPROVED'));
      expect(r.outcome, EventOutcome.duplicate);
      expect(r.state.timeline.length, 2);
      expect(r.state.duplicates, 1);
    });
    test('older version with a different eventId is stale', () {
      final r = applyRealtimeEvent(loaded(orderA, 5, 'IN_TRANSIT'), ev(orderA, 3, 'SCHEDULED', eventId: 'other'));
      expect(r.outcome, EventOutcome.stale);
      expect(r.state.version, 5);
      expect(r.state.status, 'IN_TRANSIT');
    });
    test('skipped version asks for a REST reload and does not apply', () {
      final r = applyRealtimeEvent(loaded(orderA, 2, 'APPROVED'), ev(orderA, 4, 'PREPARING'));
      expect(r.outcome, EventOutcome.gap);
      expect(r.state.version, 2);
    });
    test('another order is ignored', () {
      final r = applyRealtimeEvent(loaded(orderA, 2, 'APPROVED'), ev(orderB, 3, 'SCHEDULED'));
      expect(r.outcome, EventOutcome.foreign);
      expect(r.state.version, 2);
    });
    test('reordered v3 then v2 ends consistent after REST reload', () {
      var s = loaded(orderA, 1, 'PENDING');
      final first = applyRealtimeEvent(s, ev(orderA, 3, 'SCHEDULED'));
      expect(first.outcome, EventOutcome.gap);
      s = mergeSnapshot(
          first.state,
          TrackingSnapshot(orderId: orderA, version: 3, status: 'SCHEDULED', statusLabel: 'Đã lên lịch', terminal: false, timeline: [
            for (var v = 1; v <= 3; v++)
              TimelineEntry(eventId: 'e-$orderA-$v', version: v, toStatus: 'X', label: 'L$v')
          ]));
      final late = applyRealtimeEvent(s, ev(orderA, 2, 'APPROVED'));
      expect(late.outcome, EventOutcome.duplicate); // already in the REST timeline
      expect(late.state.version, 3);
      expect(late.state.timeline.map((e) => e.version), [1, 2, 3]);
    });
    test('malformed or unknown frames are ignored', () {
      expect(OrderRealtimeEvent.tryParse('not json'), isNull);
      expect(OrderRealtimeEvent.tryParse('{"type":"OTHER"}'), isNull);
      expect(OrderRealtimeEvent.tryParse('{"type":"ORDER_STATUS_CHANGED","version":"x"}'), isNull);
    });
  });

  group('mergeSnapshot', () {
    test('an older REST answer never moves the screen backwards', () {
      final s = loaded(orderA, 4, 'IN_TRANSIT');
      final merged = mergeSnapshot(
          s, TrackingSnapshot(orderId: orderA, version: 3, status: 'PREPARING', statusLabel: 'Đang chuẩn bị', terminal: false, timeline: const []));
      expect(merged.version, 4);
      expect(merged.status, 'IN_TRANSIT');
      expect(merged.timeline.length, 4);
    });
  });

  group('RealtimeClient', () {
    late List<FakeSocket> sockets;
    late List<bool> refreshFlags;
    late RealtimeClient client;

    RealtimeClient make({Future<String?> Function({bool forceRefresh})? tokens, Future<StompSocket> Function(Uri)? factory}) {
      sockets = [];
      refreshFlags = [];
      return RealtimeClient(
        url: Uri.parse('ws://localhost/api/ws'),
        tokens: tokens ??
            ({bool forceRefresh = false}) async {
              refreshFlags.add(forceRefresh);
              return 'tok-${refreshFlags.length}';
            },
        connectSocket: factory ??
            (url) async {
              final s = FakeSocket();
              sockets.add(s);
              return s;
            },
        heartbeat: const Duration(milliseconds: 40),
        silenceLimit: const Duration(milliseconds: 120),
        connectTimeout: const Duration(milliseconds: 300),
        baseBackoff: const Duration(milliseconds: 10),
        maxBackoff: const Duration(milliseconds: 40),
      );
    }

    Future<void> acceptConnect(FakeSocket s) async {
      await settle();
      expect(s.sentFrames.first.command, 'CONNECT');
      s.push(const StompFrame('CONNECTED', {'version': '1.2'}));
      await settle();
    }

    tearDown(() => client.dispose());

    test('sends the bearer token and subscribes only to requested destinations', () async {
      client = make();
      final ready = <String>[];
      client.subscribe('/topic/orders/$orderA', onMessage: (_) {}, onReady: () => ready.add('A'));
      client.start();
      await settle();
      await acceptConnect(sockets.single);
      final connect = sockets.single.sentFrames.first;
      expect(connect.headers['Authorization'], 'Bearer tok-1');
      final subs = sockets.single.sentFrames.where((f) => f.command == 'SUBSCRIBE').toList();
      expect(subs.map((f) => f.headers['destination']), ['/topic/orders/$orderA']);
      sockets.single.push(StompFrame('RECEIPT', {'receipt-id': subs.single.headers['receipt']!}));
      await settle();
      expect(ready, ['A']);
    });

    test('messages are routed to their own subscription only', () async {
      client = make();
      final a = <String>[], b = <String>[];
      client.subscribe('/topic/orders/$orderA', onMessage: a.add, onReady: () {});
      client.subscribe('/topic/orders/$orderB', onMessage: b.add, onReady: () {});
      client.start();
      await settle();
      await acceptConnect(sockets.single);
      final subs = sockets.single.sentFrames.where((f) => f.command == 'SUBSCRIBE').toList();
      final idA = subs.firstWhere((f) => f.headers['destination']!.endsWith(orderA)).headers['id']!;
      sockets.single.push(StompFrame('MESSAGE', {'subscription': idA, 'destination': '/topic/orders/$orderA'}, 'hello'));
      await settle();
      expect(a, ['hello']);
      expect(b, isEmpty);
    });

    test('cancel sends UNSUBSCRIBE and stops delivery (no leak)', () async {
      client = make();
      final got = <String>[];
      final sub = client.subscribe('/topic/orders/$orderA', onMessage: got.add, onReady: () {});
      client.start();
      await settle();
      await acceptConnect(sockets.single);
      final id = sockets.single.sentFrames.firstWhere((f) => f.command == 'SUBSCRIBE').headers['id']!;
      sub.cancel();
      sub.cancel(); // idempotent
      await settle();
      expect(client.activeDestinations, isEmpty);
      expect(sockets.single.sentFrames.where((f) => f.command == 'UNSUBSCRIBE').map((f) => f.headers['id']), [id]);
      sockets.single.push(StompFrame('MESSAGE', {'subscription': id}, 'late'));
      await settle();
      expect(got, isEmpty);
    });

    test('network loss reconnects, re-subscribes and signals ready again', () async {
      client = make();
      var ready = 0;
      client.subscribe('/topic/orders/$orderA', onMessage: (_) {}, onReady: () => ready++);
      client.start();
      await settle();
      await acceptConnect(sockets.first);
      sockets.first.drop();
      await Future<void>.delayed(const Duration(milliseconds: 120));
      expect(sockets.length, 2);
      await acceptConnect(sockets.last);
      final sub = sockets.last.sentFrames.firstWhere((f) => f.command == 'SUBSCRIBE');
      expect(sub.headers['destination'], '/topic/orders/$orderA');
      sockets.last.push(StompFrame('RECEIPT', {'receipt-id': sub.headers['receipt']!}));
      await settle();
      expect(ready, 1);
      expect(client.status, RealtimeStatus.connected);
    });

    test('silent half-open connection is detected by the watchdog', () async {
      client = make();
      client.start();
      await settle();
      await acceptConnect(sockets.first);
      await Future<void>.delayed(const Duration(milliseconds: 350));
      expect(sockets.length, greaterThanOrEqualTo(2));
      expect(sockets.first.closed, isTrue);
    });

    test('TOKEN_EXPIRED error forces a token refresh before reconnecting', () async {
      client = make();
      client.start();
      await settle();
      await acceptConnect(sockets.first);
      sockets.first.push(const StompFrame('ERROR', {'message': 'TOKEN_EXPIRED'}));
      await Future<void>.delayed(const Duration(milliseconds: 80));
      expect(refreshFlags.first, isFalse);
      expect(refreshFlags.last, isTrue);
      expect(sockets.length, 2);
      expect(sockets.last.sentFrames.first.headers['Authorization'], 'Bearer tok-2');
    });

    test('expired token rejected on CONNECT also refreshes and retries', () async {
      client = make();
      client.start();
      await settle();
      sockets.first.push(const StompFrame('ERROR', {'message': 'TOKEN_EXPIRED'}));
      await Future<void>.delayed(const Duration(milliseconds: 80));
      expect(refreshFlags.last, isTrue);
      expect(sockets.length, 2);
    });

    test('pause closes the socket, resume reconnects immediately', () async {
      client = make();
      client.subscribe('/topic/orders/$orderA', onMessage: (_) {}, onReady: () {});
      client.start();
      await settle();
      await acceptConnect(sockets.first);
      client.pause();
      await settle();
      expect(client.status, RealtimeStatus.paused);
      expect(sockets.first.closed, isTrue);
      expect(sockets.first.sentFrames.any((f) => f.command == 'DISCONNECT'), isTrue);
      await Future<void>.delayed(const Duration(milliseconds: 120));
      expect(sockets.length, 1); // no reconnect while paused
      client.resume();
      await settle();
      await acceptConnect(sockets.last);
      expect(sockets.length, 2);
      expect(sockets.last.sentFrames.any((f) => f.command == 'SUBSCRIBE'), isTrue);
    });

    test('FORBIDDEN drops that subscription instead of looping', () async {
      client = make();
      var denied = 0;
      client.subscribe('/topic/orders/$orderB', onMessage: (_) {}, onReady: () {}, onDenied: () => denied++);
      client.start();
      await settle();
      await acceptConnect(sockets.first);
      final rcpt = sockets.first.sentFrames.firstWhere((f) => f.command == 'SUBSCRIBE').headers['receipt']!;
      sockets.first.push(StompFrame('ERROR', {'message': 'FORBIDDEN', 'receipt-id': rcpt}));
      await Future<void>.delayed(const Duration(milliseconds: 100));
      expect(denied, 1);
      expect(client.activeDestinations, isEmpty);
      if (sockets.length > 1) {
        await acceptConnect(sockets.last);
        expect(sockets.last.sentFrames.where((f) => f.command == 'SUBSCRIBE'), isEmpty);
      }
    });

    test('signed out (no token) never opens a socket', () async {
      client = make(tokens: ({bool forceRefresh = false}) async => null);
      client.start();
      await settle();
      expect(sockets, isEmpty);
      expect(client.status, RealtimeStatus.idle);
    });

    test('dispose cancels everything and stops reconnecting', () async {
      client = make();
      client.subscribe('/topic/orders/$orderA', onMessage: (_) {}, onReady: () {});
      client.start();
      await settle();
      await acceptConnect(sockets.first);
      client.dispose();
      await Future<void>.delayed(const Duration(milliseconds: 120));
      expect(client.activeDestinations, isEmpty);
      expect(sockets.first.closed, isTrue);
      expect(sockets.length, 1);
    });
  });
}
