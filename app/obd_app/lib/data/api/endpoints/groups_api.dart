import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/models/api_models.dart';
import 'package:obd_app/data/api/models/json.dart';

/// `/api/v1/groups/*` — "families".
///
/// A group is what makes a car shared: `CarsApi.share()` points a car at one,
/// and from then on every member of it can drive the car, read its history and
/// upload for it.
class GroupsApi {
  const GroupsApi(this._client);

  final ApiClient _client;

  /// `POST /api/v1/groups` — creates a group and enrols the caller as its
  /// first member with the `ADMIN` role, in one transaction. `201`.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [name] | yes | non-blank, ≤ 60 chars. **Not unique** — it is a label, not an identifier |
  Future<Group> create({required String name}) async {
    final response = await _client.post('/groups', body: {'name': name});
    return Group.fromJson(response.asMap);
  }

  /// `GET /api/v1/groups` — every group the caller belongs to, by name.
  ///
  /// `callerRole` is *this* caller's role in *that* group, so a screen can
  /// decide whether to show "invite" without a second request. Empty for a
  /// user in no groups; never an error.
  Future<List<Group>> list() async {
    final response = await _client.get('/groups');
    return parseList(response.body, Group.fromJson);
  }

  /// `GET /api/v1/groups/{groupId}/members` — who is in a group, admins first
  /// then by name. Any member may read it, whatever their role.
  ///
  /// Each row carries the member's name and email, so a members screen can be
  /// drawn from this list alone — there is no `GET /users/{id}` to resolve ids
  /// with.
  ///
  /// Throws `ApiException` with `isNotFound` for a non-member **and** for a
  /// group that does not exist: the same answer, so an id is never confirmed
  /// to an outsider.
  Future<List<GroupMember>> members(String groupId) async {
    final response = await _client.get('/groups/$groupId/members');
    return parseList(response.body, GroupMember.fromJson);
  }
}
