import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/models/api_models.dart';

/// `/api/v1/auth/*` — the only endpoints that do **not** need a token.
///
/// Each of the three that issue tokens stores them in the client's session, so
/// a caller never has to touch `ObdSession` by hand: after `login` every other
/// endpoint simply works.
class AuthApi {
  const AuthApi(this._client);

  final ApiClient _client;

  /// `POST /api/v1/auth/register` — creates the account **and logs it in**.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [userName] | yes | first name, non-blank |
  /// | [userLastName] | yes | non-blank |
  /// | [userEmail] | yes | valid email |
  /// | [userPassword] | yes | 8–72 characters |
  /// | [userPhone] | no | e.g. `+39 320 1234567`; omitted when blank |
  ///
  /// Throws `ApiException` with `isConflict` when the email is taken, or
  /// `isValidation` (with `fieldErrors`) when a field is rejected.
  Future<AuthSession> register({
    required String userName,
    required String userLastName,
    required String userEmail,
    required String userPassword,
    String? userPhone,
  }) async {
    final phone = userPhone?.trim();
    final response = await _client.post(
      '/auth/register',
      authenticated: false,
      body: {
        'userName': userName,
        'userLastName': userLastName,
        'userEmail': userEmail,
        'userPassword': userPassword,
        if (phone != null && phone.isNotEmpty) 'userPhone': phone,
      },
    );

    final auth = AuthSession.fromJson(response.asMap);
    _client.session.save(auth);
    return auth;
  }

  /// `POST /api/v1/auth/login`.
  ///
  /// Throws `ApiException` with `isUnauthorized` on bad credentials.
  Future<AuthSession> login({
    required String userEmail,
    required String userPassword,
  }) async {
    final response = await _client.post(
      '/auth/login',
      authenticated: false,
      body: {'userEmail': userEmail, 'userPassword': userPassword},
    );

    final auth = AuthSession.fromJson(response.asMap);
    _client.session.save(auth);
    return auth;
  }

  /// `POST /api/v1/auth/refresh` — new access token, rotated refresh token.
  ///
  /// Normally there is no reason to call this: the client does it by itself
  /// when a request comes back `401`. Returns false when the session is over.
  ///
  /// Refresh tokens are single-use. Presenting one that has already been
  /// rotated out revokes the whole family, so never call this concurrently by
  /// hand — go through the client, which serialises refreshes.
  Future<bool> refresh() => _client.refreshSession();

  /// `POST /api/v1/auth/logout` — revokes every refresh token for this user
  /// and clears the cookie. `204 No Content`.
  ///
  /// The local session is cleared either way: if the call fails, the tokens
  /// are still no use to this app.
  Future<void> logout() async {
    if (!_client.session.isAuthenticated) {
      _client.session.clear();
      return;
    }
    try {
      await _client.post('/auth/logout');
    } finally {
      _client.session.clear();
    }
  }
}
