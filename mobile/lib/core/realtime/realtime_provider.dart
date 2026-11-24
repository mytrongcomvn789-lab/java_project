import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../features/auth/session.dart';
import '../network/dio_client.dart';
import 'realtime_client.dart';
import 'stomp_socket.dart';

/// Optional override (e.g. a debugging proxy): `--dart-define=WS_URL=ws://host:port/api/ws`.
const _wsOverride = String.fromEnvironment('WS_URL');

/// `http://host/api` -> `ws://host/api/ws` (same context path as REST).
Uri realtimeUrl([String api = apiBaseUrl]) {
  if (_wsOverride.isNotEmpty && api == apiBaseUrl) return Uri.parse(_wsOverride);
  final base = api.endsWith('/') ? api.substring(0, api.length - 1) : api;
  final ws = base.replaceFirst(RegExp(r'^http'), 'ws');
  return Uri.parse('$ws/ws');
}

/// Id of the signed-in customer, or null. Everything realtime hangs off this,
/// so logging out (or switching account) tears the whole graph down.
final realtimeUserProvider = Provider<String?>((ref) => ref.watch(sessionProvider
    .select((s) => s.phase == SessionPhase.signedIn && s.user?.role == 'CUSTOMER'
        ? s.user!.id
        : null)));

/// One socket per signed-in customer. Disposed on logout/account switch, which
/// cancels every subscription and listener bound to it.
final realtimeClientProvider = Provider<RealtimeClient?>((ref) {
  final userId = ref.watch(realtimeUserProvider);
  if (userId == null) return null;
  final session = ref.read(sessionProvider);
  final client = RealtimeClient(
    url: realtimeUrl(),
    tokens: ({bool forceRefresh = false}) =>
        session.accessTokenForSocket(forceRefresh: forceRefresh),
    connectSocket: ChannelStompSocket.connect,
  )..start();
  ref.onDispose(client.dispose);
  return client;
});

final realtimeStatusProvider = StreamProvider.autoDispose<RealtimeStatus>((ref) {
  final client = ref.watch(realtimeClientProvider);
  if (client == null) return const Stream.empty();
  return () async* {
    yield client.status;
    yield* client.statusStream;
  }();
});
