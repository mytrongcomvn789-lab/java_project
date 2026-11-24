import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';

class MemoryTokens implements TokenStore {
  Tokens? value;
  @override
  Future<Tokens?> read() async => value;
  @override
  Future<void> write(Tokens tokens) async {
    value = tokens;
  }

  @override
  Future<void> clear() async {
    value = null;
  }
}

class FakeAdapter implements HttpClientAdapter {
  FakeAdapter(this.reply);
  final FutureOr<ResponseBody> Function(RequestOptions) reply;
  final requests = <RequestOptions>[];
  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? stream,
      Future<void>? cancelFuture) async {
    requests.add(options);
    return await reply(options);
  }

  @override
  void close({bool force = false}) {}
}

ResponseBody jsonResponse(Object? data, [int status = 200]) =>
    ResponseBody.fromString(jsonEncode(data), status, headers: {
      Headers.contentTypeHeader: ['application/json']
    });
Map<String, dynamic> profile(
        {String id = 'a', String role = 'CUSTOMER', String name = 'Nhã'}) =>
    {
      'id': id,
      'email': '$id@test.com',
      'fullName': name,
      'phone': '0901234567',
      'role': role
    };
Map<String, dynamic> tokenResponse(
        {String access = 'access-a',
        String refresh = 'refresh-a',
        String role = 'CUSTOMER',
        String id = 'a'}) =>
    {
      'accessToken': access,
      'refreshToken': refresh,
      'user': profile(id: id, role: role)
    };

Session makeSession(
        {required MemoryTokens store,
        required FakeAdapter api,
        required FakeAdapter auth}) =>
    Session(
        api: Dio(BaseOptions(baseUrl: 'http://localhost/api'))
          ..httpClientAdapter = api,
        authApi: Dio(BaseOptions(baseUrl: 'http://localhost/api'))
          ..httpClientAdapter = auth,
        store: store);
