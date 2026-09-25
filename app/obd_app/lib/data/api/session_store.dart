import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Where the refresh token lives between launches of the app.
///
/// Only the refresh token is stored. The access token is a 15-minute JWT that
/// `POST /auth/refresh` hands out again, and the user id / email come back in
/// the same response, so there is nothing else worth putting on disk.
///
/// An interface so tests can use an in-memory fake and never touch a platform
/// channel.
abstract interface class SessionStore {
  Future<String?> readRefreshToken();

  Future<void> writeRefreshToken(String token);

  Future<void> deleteRefreshToken();
}

/// Keeps the refresh token in the OS keystore: Keychain on iOS / macOS, an
/// AES-GCM key held in the Android Keystore on Android.
///
/// A refresh token in plain `SharedPreferences` would be readable by anyone
/// with a backup or a rooted device, which is why this is not that.
class SecureSessionStore implements SessionStore {
  SecureSessionStore([FlutterSecureStorage? storage]) : _storage = storage ?? FlutterSecureStorage();

  static const String _refreshTokenKey = 'obd.refresh_token';

  final FlutterSecureStorage _storage;

  @override
  Future<String?> readRefreshToken() => _storage.read(key: _refreshTokenKey);

  @override
  Future<void> writeRefreshToken(String token) => _storage.write(key: _refreshTokenKey, value: token);

  @override
  Future<void> deleteRefreshToken() => _storage.delete(key: _refreshTokenKey);
}
