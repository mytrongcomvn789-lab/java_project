import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class Tokens {
  const Tokens(this.access, this.refresh);
  final String access;
  final String refresh;
  factory Tokens.fromJson(Map<String, dynamic> json) =>
      Tokens(json['accessToken'] as String, json['refreshToken'] as String);
  Map<String, dynamic> toJson() =>
      {'accessToken': access, 'refreshToken': refresh};
}

abstract class TokenStore {
  Future<Tokens?> read();
  Future<void> write(Tokens tokens);
  Future<void> clear();
}

/// Write a rotated pair in one storage entry, never a half-updated pair.
class SecureTokenStore implements TokenStore {
  final FlutterSecureStorage _storage = const FlutterSecureStorage();
  static const _key = 'smartdrone.session.v1';
  @override
  Future<Tokens?> read() async {
    final raw = await _storage.read(key: _key);
    if (raw == null) return null;
    try {
      return Tokens.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } on FormatException {
      await clear();
      return null;
    } on TypeError {
      await clear();
      return null;
    }
  }

  @override
  Future<void> write(Tokens tokens) =>
      _storage.write(key: _key, value: jsonEncode(tokens.toJson()));
  @override
  Future<void> clear() async {
    await _storage.delete(key: _key);
    // Remove legacy keys from the original skeleton.
    await _storage.delete(key: 'access_token');
    await _storage.delete(key: 'refresh_token');
  }
}
