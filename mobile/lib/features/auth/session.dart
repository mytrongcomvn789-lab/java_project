import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/dio_client.dart';
import '../../core/storage/storage_service.dart';

class UserProfile {
  const UserProfile(
      {required this.id,
      required this.email,
      required this.name,
      required this.phone,
      required this.role});
  final String id, email, name, phone, role;
  factory UserProfile.fromJson(Map<String, dynamic> json) => UserProfile(
      id: json['id'] as String,
      email: json['email'] as String,
      name: json['fullName'] as String? ?? '',
      phone: json['phone'] as String? ?? '',
      role: json['role'] as String);
  bool get supported => role == 'CUSTOMER' || role == 'STATION_OPERATOR';
  String get home => role == 'CUSTOMER' ? '/customer' : '/operator';
}

enum SessionPhase { restoring, signedOut, signedIn, unavailable }

final sessionProvider = ChangeNotifierProvider<Session>((ref) {
  final session = Session(
      api: createDio(), authApi: createDio(), store: SecureTokenStore());
  unawaited(session.restore());
  return session;
});

/// Shared authenticated API for tasks 6.2–6.5. Session epoch prevents a late
/// refresh/profile response from reviving a logged-out or switched account.
class Session extends ChangeNotifier {
  Session({required this.api, required this.authApi, required this.store}) {
    api.interceptors.add(InterceptorsWrapper(
      onRequest: (request, handler) {
        request.extra['sessionEpoch'] = _epoch;
        if (_tokens != null) {
          request.headers['Authorization'] = 'Bearer ${_tokens!.access}';
        }
        handler.next(request);
      },
      onError: (error, handler) async {
        final request = error.requestOptions;
        final epoch = request.extra['sessionEpoch'];
        if (error.response?.statusCode != 401 ||
            request.extra['retried'] == true ||
            epoch != _epoch ||
            _tokens == null) {
          if (error.response?.statusCode == 401 &&
              epoch == _epoch &&
              request.extra['retried'] == true) {
            await _invalidate();
          }
          handler.next(error);
          return;
        }
        try {
          // Another request may already have rotated this stale access token.
          if (request.headers['Authorization'] == 'Bearer ${_tokens!.access}') {
            await refresh();
          }
          if (epoch != _epoch || _tokens == null) {
            handler.next(error);
            return;
          }
          request.extra['retried'] = true;
          request.headers['Authorization'] = 'Bearer ${_tokens!.access}';
          handler.resolve(await api.fetch<dynamic>(request));
        } on DioException catch (next) {
          handler.reject(next);
        } catch (_) {
          handler.next(error);
        }
      },
    ));
  }
  final Dio api, authApi;
  final TokenStore store;
  SessionPhase phase = SessionPhase.restoring;
  UserProfile? user;
  String? message;
  Tokens? _tokens;
  Future<void>? _refreshing;
  int _epoch = 0;
  bool _disposed = false;
  // Serialize storage writes so logout cannot race an older refresh write.
  Future<void> _storageWork = Future<void>.value();
  Future<void> _storage(Future<void> Function() action) {
    final next = _storageWork.then((_) => action());
    _storageWork = next.catchError((Object _) {});
    return next;
  }

  void _emit() {
    if (!_disposed) notifyListeners();
  }

  Future<void> _invalidate() async {
    _epoch++;
    _tokens = null;
    _refreshing = null;
    user = null;
    phase = SessionPhase.signedOut;
    _emit();
    await _storage(store.clear);
  }

  Future<void> restore() async {
    final epoch = ++_epoch;
    phase = SessionPhase.restoring;
    message = null;
    _emit();
    try {
      final tokens = await store.read();
      if (epoch != _epoch) return;
      _tokens = tokens;
      if (tokens == null) {
        phase = SessionPhase.signedOut;
      } else {
        final response = await api.get<Map<String, dynamic>>('/v1/me');
        if (epoch != _epoch) return;
        user = UserProfile.fromJson(response.data!);
        phase = SessionPhase.signedIn;
      }
    } catch (error) {
      if (epoch != _epoch) return;
      if (error is DioException && error.response?.statusCode == 401) {
        await _invalidate();
      } else {
        // Offline is not evidence the refresh token is invalid.
        phase = SessionPhase.unavailable;
        message = apiError(error);
      }
    }
    _emit();
  }

  Future<void> login(String email, String password) async {
    await _invalidate();
    final epoch = _epoch;
    final response = await authApi.post<Map<String, dynamic>>('/v1/auth/login',
        data: {'email': email.trim(), 'password': password});
    if (epoch != _epoch) return;
    final tokens = Tokens.fromJson(response.data!);
    final profile =
        UserProfile.fromJson(response.data!['user'] as Map<String, dynamic>);
    await _storage(() => store.write(tokens));
    if (epoch != _epoch) return;
    _tokens = tokens;
    user = profile;
    phase = SessionPhase.signedIn;
    message = null;
    _emit();
  }

  Future<void> refresh() {
    final running = _refreshing;
    if (running != null) return running;
    final future = _rotate();
    _refreshing = future;
    return future.whenComplete(() {
      if (identical(_refreshing, future)) _refreshing = null;
    });
  }

  Future<void> _rotate() async {
    final epoch = _epoch;
    final tokens = _tokens;
    if (tokens == null) return;
    try {
      final response = await authApi.post<Map<String, dynamic>>(
          '/v1/auth/refresh',
          data: {'refreshToken': tokens.refresh});
      if (epoch != _epoch) return;
      final rotated = Tokens.fromJson(response.data!);
      await _storage(() => store.write(rotated));
      if (epoch != _epoch) return;
      _tokens = rotated;
    } on DioException catch (error) {
      if (epoch == _epoch &&
          (error.response?.statusCode == 401 ||
              error.response?.statusCode == 400 ||
              error.response?.statusCode == 403)) {
        await _invalidate();
      }
      rethrow;
    }
  }

  Future<String?> logout() async {
    final refresh = _tokens?.refresh;
    await _invalidate();
    if (refresh == null) return null;
    try {
      await authApi
          .post<void>('/v1/auth/logout', data: {'refreshToken': refresh});
      return null;
    } catch (_) {
      return 'Đã xóa phiên trên máy. Chưa xác nhận thu hồi phiên trên máy chủ do lỗi kết nối.';
    }
  }

  Future<void> updateProfile(String name, String phone) async {
    final epoch = _epoch;
    final response = await api.patch<Map<String, dynamic>>('/v1/me',
        data: {'fullName': name.trim(), 'phone': phone.trim()});
    if (epoch != _epoch) return;
    user = UserProfile.fromJson(response.data!);
    _emit();
  }

  @override
  void dispose() {
    _disposed = true;
    _epoch++;
    api.close();
    authApi.close();
    super.dispose();
  }
}
