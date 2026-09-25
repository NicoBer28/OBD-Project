import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/models/api_models.dart';
import 'package:obd_app/data/api/models/json.dart';

/// `/api/v1/models` — the car model catalog.
///
/// This is where the `modelId` that `POST /cars` requires comes from: the
/// create-car form should be a picker over [list], not two free-text fields.
class ModelsApi {
  const ModelsApi(this._client);

  final ApiClient _client;

  /// `GET /api/v1/models` — the whole catalog, ordered by brand then model.
  ///
  /// Unpaged on purpose: it is small and curated. Eight models are seeded by
  /// the migrations with fixed ids, so this is never empty.
  Future<List<CarModel>> list() async {
    final response = await _client.get('/models');
    return parseList(response.body, CarModel.fromJson);
  }

  /// `POST /api/v1/models` — adds a model. **Admin-only** (the account-level
  /// role, not a group role). `201`.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [modelBrand] | yes | ≤ 60 chars |
  /// | [modelName] | yes | ≤ 60 chars |
  /// | [modelProtocol] | yes | ≤ 40 chars, e.g. `ISO 15765-4 (CAN)` |
  ///
  /// `register` always creates plain users, and no endpoint promotes an
  /// account, so the first admin is made with SQL:
  /// `update users set role = 'ADMIN' where email = '…';` (effective on the
  /// next login, when the role is read into the token).
  ///
  /// Throws `ApiException` with `isForbidden` for a non-admin and
  /// `isConflict` when that brand/model pair already exists.
  Future<CarModel> create({
    required String modelBrand,
    required String modelName,
    required String modelProtocol,
  }) async {
    final response = await _client.post(
      '/models',
      body: {
        'modelBrand': modelBrand,
        'modelName': modelName,
        'modelProtocol': modelProtocol,
      },
    );
    return CarModel.fromJson(response.asMap);
  }
}
