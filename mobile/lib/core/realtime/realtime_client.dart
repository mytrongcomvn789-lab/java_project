import 'dart:async';
import 'dart:math';
import 'stomp_frame.dart';
import 'stomp_socket.dart';

enum RealtimeStatus { idle, connecting, connected, reconnecting, paused }

/// Returns a bearer access token, or null when nobody is signed in.
/// [forceRefresh] must rotate the token even if it still looks valid.
typedef AccessTokenProvider = Future<String?> Function({bool forceRefresh});

/// Handle for one SUBSCRIBE. Cancel it (idempotent) to stop delivery.
class RealtimeSubscription {
  RealtimeSubscription._(this.destination, this._id, this._onMessage,
      this._onReady, this._onDenied, this._owner);
  final String destination;
  final String _id;
  final void Function(String body) _onMessage;
  final void Function() _onReady;
  final void Function() _onDenied;
  final RealtimeClient _owner;
  String? _receipt;
  bool _cancelled = false;
  bool get isActive => !_cancelled;
  void cancel() => _owner._cancel(this);
}

/// STOMP-over-WebSocket client for the Spring backend (protocol 4.3).
///
/// * Subscriptions survive reconnects; `onReady` fires after the server's
///   RECEIPT, every time, so callers can REST-reconcile with no gap.
/// * Network loss is detected by transport close/error and by a heart-beat
///   watchdog; reconnects use capped exponential backoff with jitter.
/// * An expired/rejected token forces a refresh before the next connect.
/// * [pause]/[resume] close and reopen the socket for app lifecycle.
class RealtimeClient {
  RealtimeClient({
    required this.url,
    required this.tokens,
    required this.connectSocket,
    this.heartbeat = const Duration(seconds: 10),
    this.silenceLimit = const Duration(seconds: 30),
    this.connectTimeout = const Duration(seconds: 10),
    this.baseBackoff = const Duration(seconds: 1),
    this.maxBackoff = const Duration(seconds: 30),
    Random? random,
  }) : _random = random ?? Random();

  final Uri url;
  final AccessTokenProvider tokens;
  final SocketFactory connectSocket;
  final Duration heartbeat, silenceLimit, connectTimeout, baseBackoff, maxBackoff;
  final Random _random;

  final _status = StreamController<RealtimeStatus>.broadcast();
  final _subs = <String, RealtimeSubscription>{};
  RealtimeStatus _current = RealtimeStatus.idle;
  StompSocket? _socket;
  StreamSubscription<String>? _socketSub;
  Timer? _retry, _beat;
  DateTime _lastRx = DateTime.now();
  int _attempt = 0, _subSeq = 0, _generation = 0;
  bool _enabled = false, _foreground = true, _disposed = false, _forceRefresh = false;
  bool get _wantConnected => _enabled && _foreground;

  RealtimeStatus get status => _current;
  Stream<RealtimeStatus> get statusStream => _status.stream;
  List<String> get activeDestinations =>
      _subs.values.map((s) => s.destination).toList(growable: false);
  int get reconnectAttempts => _attempt;

  void _set(RealtimeStatus s) {
    if (_current == s || _disposed) return;
    _current = s;
    _status.add(s);
  }

  /// Begin (or keep wanting) a connection.
  void start() {
    if (_disposed) return;
    _enabled = true;
    if (_foreground && _socket == null && _retry == null) _connect();
  }

  /// App went to background: drop the socket, keep subscriptions.
  void pause() {
    if (_disposed) return;
    _foreground = false;
    _teardown(sendDisconnect: true);
    if (_enabled) _set(RealtimeStatus.paused);
  }

  /// App is foreground again: reconnect now (no backoff) and re-subscribe.
  void resume() {
    if (_disposed) return;
    _foreground = true;
    if (!_enabled) return;
    _attempt = 0;
    _teardown(sendDisconnect: false);
    _connect();
  }

  /// The device regained network: skip the remaining backoff wait.
  void retryNow() {
    if (_disposed || !_wantConnected || _socket != null) return;
    _retry?.cancel();
    _retry = null;
    _attempt = 0;
    _connect();
  }

  RealtimeSubscription subscribe(String destination,
      {required void Function(String body) onMessage,
      required void Function() onReady,
      void Function()? onDenied}) {
    final sub = RealtimeSubscription._(destination, 'sub-${_subSeq++}',
        onMessage, onReady, onDenied ?? () {}, this);
    _subs[sub._id] = sub;
    if (_current == RealtimeStatus.connected) _sendSubscribe(sub);
    return sub;
  }

  void _cancel(RealtimeSubscription sub) {
    if (sub._cancelled) return;
    sub._cancelled = true;
    _subs.remove(sub._id);
    if (_current == RealtimeStatus.connected && _socket != null) {
      _socket!.send(StompFrame('UNSUBSCRIBE', {'id': sub._id}).encode());
    }
  }

  void _sendSubscribe(RealtimeSubscription sub) {
    sub._receipt = 'rcpt-${sub._id}-$_generation';
    _socket?.send(StompFrame('SUBSCRIBE', {
      'id': sub._id,
      'destination': sub.destination,
      'receipt': sub._receipt!,
    }).encode());
  }

  Future<void> _connect() async {
    if (_disposed || !_wantConnected) return;
    final generation = ++_generation;
    _set(_attempt == 0 && _current == RealtimeStatus.idle
        ? RealtimeStatus.connecting
        : RealtimeStatus.reconnecting);
    try {
      final token = await tokens(forceRefresh: _forceRefresh);
      if (_stale(generation)) return;
      if (token == null) {
        // Signed out: nothing to connect with.
        _enabled = false;
        _set(RealtimeStatus.idle);
        return;
      }
      _forceRefresh = false;
      final socket = await connectSocket(url).timeout(connectTimeout);
      if (_stale(generation)) {
        unawaited(socket.close());
        return;
      }
      _socket = socket;
      _lastRx = DateTime.now();
      final connected = Completer<void>();
      _socketSub = socket.messages.listen(
          (data) => _onData(generation, data, connected),
          onError: (Object _) => _lost(generation),
          onDone: () => _lost(generation),
          cancelOnError: true);
      socket.send(StompFrame('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': '${heartbeat.inMilliseconds},${heartbeat.inMilliseconds}',
        'Authorization': 'Bearer $token',
      }).encode());
      await connected.future.timeout(connectTimeout);
      if (_stale(generation)) return;
      _set(RealtimeStatus.connected);
      _startWatchdog(generation);
      for (final sub in _subs.values.toList()) {
        _sendSubscribe(sub);
      }
    } on _AuthRejected {
      if (_stale(generation)) return;
      _forceRefresh = true;
      _teardown(sendDisconnect: false);
      _scheduleRetry(immediate: true);
    } catch (_) {
      if (_stale(generation)) return;
      _teardown(sendDisconnect: false);
      _scheduleRetry();
    }
  }

  bool _stale(int generation) =>
      _disposed || generation != _generation || !_wantConnected;

  void _onData(int generation, String data, Completer<void> connected) {
    if (generation != _generation) return;
    _lastRx = DateTime.now();
    for (final frame in StompFrame.parse(data)) {
      if (frame.isHeartbeat) continue;
      switch (frame.command) {
        case 'CONNECTED':
          if (!connected.isCompleted) {
            _attempt = 0;
            connected.complete();
          }
        case 'MESSAGE':
          _subs[frame.headers['subscription']]?._onMessage(frame.body);
        case 'RECEIPT':
          final id = frame.headers['receipt-id'];
          for (final sub in _subs.values.toList()) {
            if (sub._receipt == id) sub._onReady();
          }
        case 'ERROR':
          _onError(generation, frame, connected);
      }
    }
  }

  void _onError(int generation, StompFrame frame, Completer<void> connected) {
    final code = (frame.headers['message'] ?? '').trim();
    final authProblem = code == 'TOKEN_EXPIRED' || code == 'AUTH_REQUIRED';
    if (!connected.isCompleted) {
      connected.completeError(
          authProblem ? const _AuthRejected() : StateError(code));
      return;
    }
    if (authProblem) {
      _forceRefresh = true;
      _lost(generation, immediate: true);
      return;
    }
    if (code == 'FORBIDDEN') {
      // The server refuses this channel for this user: drop just that
      // subscription instead of looping reconnect -> subscribe -> refuse.
      final id = frame.headers['receipt-id'];
      final denied = _subs.values.where((s) => s._receipt == id).toList();
      for (final sub in denied) {
        _subs.remove(sub._id);
        sub._cancelled = true;
        sub._onDenied();
      }
    }
    _lost(generation);
  }

  void _startWatchdog(int generation) {
    _beat?.cancel();
    _beat = Timer.periodic(heartbeat, (_) {
      if (generation != _generation) return;
      if (DateTime.now().difference(_lastRx) > silenceLimit) {
        _lost(generation); // half-open connection: no data and no heart-beat
        return;
      }
      _socket?.send('\n');
    });
  }

  void _lost(int generation, {bool immediate = false}) {
    if (generation != _generation || _disposed) return;
    _teardown(sendDisconnect: false);
    if (_wantConnected) _scheduleRetry(immediate: immediate);
  }

  void _scheduleRetry({bool immediate = false}) {
    if (_disposed || !_wantConnected) return;
    _set(RealtimeStatus.reconnecting);
    _retry?.cancel();
    _attempt++;
    final exp =
        baseBackoff.inMilliseconds * pow(2, min(_attempt - 1, 6)).toInt();
    final capped = min(exp, maxBackoff.inMilliseconds);
    final delay = immediate
        ? Duration.zero
        : Duration(
            milliseconds: capped ~/ 2 + _random.nextInt(capped ~/ 2 + 1));
    _retry = Timer(delay, () {
      _retry = null;
      _connect();
    });
  }

  void _teardown({required bool sendDisconnect}) {
    _generation++; // invalidates in-flight connects and old socket callbacks
    _beat?.cancel();
    _beat = null;
    _retry?.cancel();
    _retry = null;
    final socket = _socket;
    _socket = null;
    unawaited(_socketSub?.cancel());
    _socketSub = null;
    if (socket != null) {
      if (sendDisconnect) {
        try {
          socket.send(const StompFrame('DISCONNECT').encode());
        } catch (_) {}
      }
      unawaited(socket.close().catchError((Object _) {}));
    }
  }

  /// Logout / dispose: close for good and drop every listener.
  void dispose() {
    if (_disposed) return;
    _enabled = false;
    _teardown(sendDisconnect: true);
    for (final sub in _subs.values) {
      sub._cancelled = true;
    }
    _subs.clear();
    _disposed = true;
    _current = RealtimeStatus.idle;
    unawaited(_status.close());
  }
}

class _AuthRejected implements Exception {
  const _AuthRejected();
}
