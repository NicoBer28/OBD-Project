import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/models/api_models.dart';
import 'package:obd_app/data/api/models/json.dart';

/// `/api/v1/invitations/*` — how someone joins a group.
///
/// Invitations are keyed by **email, not user id**, because the common case in
/// a family app is inviting someone who has not installed the app yet: the row
/// waits, and when that address registers the invitation is simply there.
///
/// Inviting writes nothing to the membership table. Joining is consent — the
/// invitee accepts.
class InvitationsApi {
  const InvitationsApi(this._client);

  final ApiClient _client;

  /// `POST /api/v1/invitations/invite/{groupId}` — an **admin** of the group
  /// invites an email address.
  ///
  /// | Parameter | Required | Notes |
  /// |---|---|---|
  /// | [groupId] | yes | a group the caller administers |
  /// | [email] | yes | valid email; trimmed and lowercased server-side |
  ///
  /// The response is identical whether or not that email has an account, so
  /// this cannot be used as an "is this address registered?" oracle.
  /// Invitations expire after 7 days; re-inviting the same address reclaims a
  /// lapsed row.
  ///
  /// Throws `ApiException`: `isNotFound` if the caller is not a member (a
  /// `403` would confirm the group exists), `isForbidden` if they are a member
  /// but not an admin, `isConflict` if that email already belongs to a member.
  Future<Invitation> invite({
    required String groupId,
    required String email,
  }) async {
    final response = await _client.post(
      '/invitations/invite/$groupId',
      body: {'email': email},
    );
    return Invitation.fromJson(response.asMap);
  }

  /// `GET /api/v1/invitations/pending` — the caller's own pending
  /// invitations, newest first. No parameters: the email always comes from the
  /// token, which is what stops one person listing (and then accepting)
  /// another's.
  ///
  /// Accepted and expired rows are filtered out, so every id returned can be
  /// accepted right now. `[]` for most users most of the time.
  Future<List<PendingInvitation>> pending() async {
    final response = await _client.get('/invitations/pending');
    return parseList(response.body, PendingInvitation.fromJson);
  }

  /// `POST /api/v1/invitations/{invitationId}/accept` — accepts an invitation
  /// addressed to the caller's email **and joins the group as `MEMBER`**, in
  /// one atomic statement. No body.
  ///
  /// The caller shows up in `GroupsApi.list()` immediately afterwards.
  ///
  /// Throws `ApiException`: `isNotFound` for an unknown invitation **or** one
  /// addressed to someone else (deliberately indistinguishable),
  /// `isConflict` if it was already accepted or has expired.
  Future<Invitation> accept(String invitationId) async {
    final response = await _client.post('/invitations/$invitationId/accept');
    return Invitation.fromJson(response.asMap);
  }
}
