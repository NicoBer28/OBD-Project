import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/models/api_models.dart';

/// `/api/v1/users/*`.
class UsersApi {
  const UsersApi(this._client);

  final ApiClient _client;

  /// `GET /api/v1/users/me` — the caller's own profile. No parameters: the
  /// user is the token holder, and there is deliberately no
  /// `GET /users/{id}` to look anyone else up with.
  ///
  /// Throws `ApiException` with `isNotFound` if the account behind a
  /// still-valid token was deleted.
  Future<UserProfile> me() async {
    final response = await _client.get('/users/me');
    return UserProfile.fromJson(response.asMap);
  }
}
