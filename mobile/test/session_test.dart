import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/router/app_router.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'support/fakes.dart';

void main() {
  test('empty storage opens login without calling API', () async {
    final api = FakeAdapter((_) => jsonResponse({}));
    final session = makeSession(store: MemoryTokens(), api: api, auth: api);
    addTearDown(session.dispose);
    await session.restore();
    expect(session.phase, SessionPhase.signedOut);
    expect(api.requests, isEmpty);
    expect(sessionRedirect(session, '/operator'), '/login');
  });

  test('login persists both tokens and routes customer; logout clears them',
      () async {
    final store = MemoryTokens();
    final auth = FakeAdapter(
        (r) => jsonResponse(r.path.endsWith('login') ? tokenResponse() : {}));
    final session = makeSession(
        store: store, api: FakeAdapter((_) => jsonResponse({})), auth: auth);
    addTearDown(session.dispose);
    await session.login(' a@test.com ', 'secret');
    expect(store.value!.refresh, 'refresh-a');
    expect(sessionRedirect(session, '/operator'), '/customer');
    expect(auth.requests.first.data['email'], 'a@test.com');
    await session.logout();
    expect(session.user, isNull);
    expect(store.value, isNull);
    expect(auth.requests.last.data, {'refreshToken': 'refresh-a'});
  });

  test('invalid password does not save a session', () async {
    final store = MemoryTokens();
    final session = makeSession(
        store: store,
        api: FakeAdapter((_) => jsonResponse({})),
        auth: FakeAdapter((_) => jsonResponse({}, 401)));
    addTearDown(session.dispose);
    await expectLater(
        session.login('a@test.com', 'bad'), throwsA(isA<DioException>()));
    expect(session.phase, SessionPhase.signedOut);
    expect(store.value, isNull);
  });

  test('restore loads profile from backend rather than trusting cached role',
      () async {
    final store = MemoryTokens()..value = const Tokens('access', 'refresh');
    final api = FakeAdapter((r) {
      expect(r.headers['Authorization'], 'Bearer access');
      return jsonResponse(profile(role: 'STATION_OPERATOR'));
    });
    final session = makeSession(
        store: store, api: api, auth: FakeAdapter((_) => jsonResponse({})));
    addTearDown(session.dispose);
    await session.restore();
    expect(session.user!.role, 'STATION_OPERATOR');
    expect(sessionRedirect(session, '/customer/orders/42'), '/operator');
    expect(sessionRedirect(session, '/profile'), isNull);
  });

  test('expired access refreshes once, stores rotated pair and retries',
      () async {
    final store = MemoryTokens()..value = const Tokens('old', 'refresh-old');
    final api = FakeAdapter((r) => r.headers['Authorization'] == 'Bearer old'
        ? jsonResponse({}, 401)
        : jsonResponse(profile()));
    final auth = FakeAdapter((_) =>
        jsonResponse(tokenResponse(access: 'new', refresh: 'refresh-new')));
    final session = makeSession(store: store, api: api, auth: auth);
    addTearDown(session.dispose);
    await session.restore();
    expect(session.phase, SessionPhase.signedIn);
    expect(auth.requests.length, 1);
    expect(store.value!.refresh, 'refresh-new');
    expect(api.requests.length, 2);
  });

  test('concurrent 401 requests share one refresh', () async {
    final store = MemoryTokens()..value = const Tokens('old', 'refresh-old');
    var expired = false;
    final api = FakeAdapter((r) =>
        expired && r.headers['Authorization'] == 'Bearer old'
            ? jsonResponse({}, 401)
            : jsonResponse(profile()));
    final gate = Completer<ResponseBody>();
    final started = Completer<void>();
    final auth = FakeAdapter((_) {
      if (!started.isCompleted) started.complete();
      return gate.future;
    });
    final session = makeSession(store: store, api: api, auth: auth);
    addTearDown(session.dispose);
    await session.restore();
    expired = true;
    final calls =
        Future.wait([session.api.get('/v1/me'), session.api.get('/v1/me')]);
    await started.future;
    await Future<void>.delayed(const Duration(milliseconds: 20));
    gate.complete(jsonResponse(tokenResponse(access: 'new')));
    await calls;
    expect(auth.requests.length, 1);
  });

  test('invalid refresh clears storage and returns to login', () async {
    final store = MemoryTokens()..value = const Tokens('old', 'bad-refresh');
    final session = makeSession(
        store: store,
        api: FakeAdapter((_) => jsonResponse({}, 401)),
        auth: FakeAdapter((_) => jsonResponse({}, 401)));
    addTearDown(session.dispose);
    await session.restore();
    expect(session.phase, SessionPhase.signedOut);
    expect(store.value, isNull);
  });

  test('retry 401 cannot loop and invalidates session', () async {
    final store = MemoryTokens()..value = const Tokens('old', 'refresh');
    final auth = FakeAdapter((_) => jsonResponse(tokenResponse()));
    final api = FakeAdapter((_) => jsonResponse({}, 401));
    final session = makeSession(store: store, api: api, auth: auth);
    addTearDown(session.dispose);
    await session.restore();
    expect(api.requests.length, 2);
    expect(auth.requests.length, 1);
    expect(session.phase, SessionPhase.signedOut);
  });

  test('403 does not refresh and preserves an authenticated session', () async {
    final store = MemoryTokens()..value = const Tokens('access', 'refresh');
    var forbidden = false;
    final auth = FakeAdapter((_) => jsonResponse({}));
    final session = makeSession(
        store: store,
        api: FakeAdapter(
            (_) => forbidden ? jsonResponse({}, 403) : jsonResponse(profile())),
        auth: auth);
    addTearDown(session.dispose);
    await session.restore();
    forbidden = true;
    await expectLater(
        session.api.get('/v1/forbidden'), throwsA(isA<DioException>()));
    expect(auth.requests, isEmpty);
    expect(session.phase, SessionPhase.signedIn);
  });

  test('offline restoration keeps tokens and can retry', () async {
    final store = MemoryTokens()..value = const Tokens('access', 'refresh');
    var offline = true;
    final session = makeSession(
        store: store,
        auth: FakeAdapter((_) => jsonResponse({})),
        api: FakeAdapter((r) {
          if (offline)
            throw DioException(
                requestOptions: r, type: DioExceptionType.connectionError);
          return jsonResponse(profile());
        }));
    addTearDown(session.dispose);
    await session.restore();
    expect(session.phase, SessionPhase.unavailable);
    expect(store.value, isNotNull);
    offline = false;
    await session.restore();
    expect(session.phase, SessionPhase.signedIn);
  });

  test('refresh network error preserves credentials instead of forcing logout',
      () async {
    final store = MemoryTokens()..value = const Tokens('old', 'refresh');
    final session = makeSession(
        store: store,
        api: FakeAdapter((_) => jsonResponse({}, 401)),
        auth: FakeAdapter((r) => throw DioException(
            requestOptions: r, type: DioExceptionType.connectionError)));
    addTearDown(session.dispose);
    await session.restore();
    expect(session.phase, SessionPhase.unavailable);
    expect(store.value!.refresh, 'refresh');
  });

  test('late refresh response after logout cannot resurrect session', () async {
    final store = MemoryTokens()..value = const Tokens('access', 'refresh');
    final gate = Completer<ResponseBody>();
    final started = Completer<void>();
    final session = makeSession(
        store: store,
        api: FakeAdapter((_) => jsonResponse(profile())),
        auth: FakeAdapter((r) {
          if (r.path.endsWith('/refresh')) {
            started.complete();
            return gate.future;
          }
          return jsonResponse({});
        }));
    addTearDown(session.dispose);
    await session.restore();
    final refreshing = session.refresh();
    await started.future;
    await session.logout();
    gate.complete(jsonResponse(tokenResponse(access: 'late')));
    await refreshing;
    expect(store.value, isNull);
    expect(session.user, isNull);
    expect(session.phase, SessionPhase.signedOut);
  });

  test('switch account clears previous profile and tokens', () async {
    final store = MemoryTokens();
    final session = makeSession(
        store: store,
        api: FakeAdapter((_) => jsonResponse({})),
        auth: FakeAdapter((r) => jsonResponse(
            tokenResponse(id: r.data['email'] == 'b@test.com' ? 'b' : 'a'))));
    addTearDown(session.dispose);
    await session.login('a@test.com', 'secret');
    await session.login('b@test.com', 'secret');
    expect(session.user!.id, 'b');
  });

  test('update profile sends only editable fields and uses backend response',
      () async {
    final store = MemoryTokens()..value = const Tokens('access', 'refresh');
    final api = FakeAdapter((r) =>
        jsonResponse(profile(name: r.method == 'PATCH' ? 'Updated' : 'Nhã')));
    final session = makeSession(
        store: store, api: api, auth: FakeAdapter((_) => jsonResponse({})));
    addTearDown(session.dispose);
    await session.restore();
    await session.updateProfile(' Updated ', '0901234567');
    expect(
        api.requests.last.data, {'fullName': 'Updated', 'phone': '0901234567'});
    expect(session.user!.name, 'Updated');
  });

  test('unsupported role is routed to explicit unsupported page', () async {
    final session = makeSession(
        store: MemoryTokens(),
        api: FakeAdapter((_) => jsonResponse({})),
        auth: FakeAdapter(
            (_) => jsonResponse(tokenResponse(role: 'SYSTEM_ADMIN'))));
    addTearDown(session.dispose);
    await session.login('a@test.com', 'secret');
    expect(sessionRedirect(session, '/customer'), '/unsupported');
    expect(sessionRedirect(session, '/profile'), '/unsupported');
  });

  test('failed logout revocation still clears local credentials and reports it',
      () async {
    final store = MemoryTokens()..value = const Tokens('access', 'refresh');
    final session = makeSession(
        store: store,
        api: FakeAdapter((_) => jsonResponse(profile())),
        auth: FakeAdapter((r) => throw DioException(
            requestOptions: r, type: DioExceptionType.connectionError)));
    addTearDown(session.dispose);
    await session.restore();
    expect(await session.logout(), contains('Chưa xác nhận'));
    expect(store.value, isNull);
    expect(session.phase, SessionPhase.signedOut);
  });
}
