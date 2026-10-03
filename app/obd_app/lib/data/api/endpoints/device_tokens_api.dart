import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/models/api_models.dart';
import 'package:obd_app/data/api/models/json.dart';

/// `/api/v1/cars/{carId}/device-tokens` and `/api/v1/device-tokens/{id}`.
///
/// ### Why this exists
///
/// The native service has to upload trips **with the app closed**, and the
/// person's access token cannot do that: it lasts 15 minutes and only Dart may
/// refresh it (the refresh token is single-use; two processes refreshing log
/// the user out). A device token is the narrow alternative: one car, no
/// rotation, revocable on its own, and good for only the four endpoints the
/// background upload needs.
///
/// All three calls here use the person's **bearer** token, so they only work
/// with the app open and signed in.
class DeviceTokensApi {
  const DeviceTokensApi(this._client);

  final ApiClient _client;

  /// `POST /api/v1/cars/{carId}/device-tokens` — mints a credential for this
  /// phone and this car. `201`.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [carId] | yes | a car the caller may use: their own, or one shared with a group they belong to |
  /// | [label] | yes | the phone's name, 1–60 chars. Trimmed here and cut to 60 so a long model name is never a `400` |
  ///
  /// The returned [DeviceToken.token] is the **only** copy of the secret:
  /// hand it straight to the native side and do not keep it in Dart.
  ///
  /// Throws `ApiException`: `isNotFound` if the car does not exist **or** the
  /// caller may no longer use it, `isForbidden` (`email_not_verified`) for an
  /// unconfirmed account.
  Future<DeviceToken> create(String carId, {required String label}) async {
    var trimmed = label.trim();
    if (trimmed.isEmpty) trimmed = 'Android';
    if (trimmed.length > 60) trimmed = trimmed.substring(0, 60);

    final response = await _client.post(
      '/cars/$carId/device-tokens',
      body: {'label': trimmed},
    );
    return DeviceToken.fromJson(response.asMap);
  }

  /// `DELETE /api/v1/device-tokens/{tokenId}` — revokes one credential. `204`.
  ///
  /// The background service notices on its next upload (`401`).
  ///
  /// Throws `ApiException`: `isNotFound` for an unknown token, someone else's,
  /// **or one already revoked** — callers that only want it gone should
  /// treat `404` as done.
  Future<void> revoke(String tokenId) => _client.delete('/device-tokens/$tokenId');

  /// `GET /api/v1/cars/{carId}/device-tokens` — the caller's own **live**
  /// tokens for that car, newest first. Revoked ones are not listed, and
  /// [DeviceToken.token] is always null here.
  ///
  /// Throws `ApiException`: `isNotFound` if the car does not exist or is not
  /// readable by the caller.
  Future<List<DeviceToken>> forCar(String carId) async {
    final response = await _client.get('/cars/$carId/device-tokens');
    return parseList(response.body, DeviceToken.fromJson);
  }
}
