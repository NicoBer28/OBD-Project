import 'package:obd_app/data/api/models/json.dart';

/// A member's role **inside a group**. Unrelated to the account-level role
/// that guards `POST /models`.
enum GroupRole {
  admin,
  member;

  static GroupRole? parse(String? value) => switch (value?.toUpperCase()) {
    'ADMIN' => GroupRole.admin,
    'MEMBER' => GroupRole.member,
    _ => null,
  };

  /// The wire value the API uses.
  String get wire => name.toUpperCase();

  bool get isAdmin => this == GroupRole.admin;
}

/// `GroupDTO.Read` — a "family" the caller belongs to.
class Group {
  const Group({
    required this.id,
    required this.name,
    this.createdAt,
    this.memberCount = 0,
    this.callerRole,
  });

  factory Group.fromJson(Map<String, dynamic> json) => Group(
    id: json.str('id') ?? '',
    name: json.str('name') ?? '',
    createdAt: json.instant('createdAt'),
    memberCount: json.integer('memberCount') ?? 0,
    callerRole: GroupRole.parse(json.str('callerRole')),
  );

  final String id;

  /// Not unique — two unrelated families may both be "Los Lopez". The name is
  /// a label, not an identifier.
  final String name;

  final DateTime? createdAt;

  /// Counted from `group_members` on every read, never stored.
  final int memberCount;

  /// *This* caller's role in *this* group: the same person can be admin of one
  /// group and a plain member of another.
  final GroupRole? callerRole;

  /// May the caller invite people to this group?
  bool get callerIsAdmin => callerRole?.isAdmin ?? false;

  @override
  String toString() => 'Group($name, $memberCount members)';
}

/// `GroupDTO.Member` — one row of `GET /groups/{groupId}/members`.
///
/// Carries the name and email directly because there is no
/// `GET /users/{id}` to resolve ids against.
class GroupMember {
  const GroupMember({
    required this.userId,
    required this.name,
    required this.email,
    this.role,
  });

  factory GroupMember.fromJson(Map<String, dynamic> json) => GroupMember(
    userId: json.str('userId') ?? '',
    // First name, as in GET /users/me.
    name: json.str('name') ?? '',
    email: json.str('email') ?? '',
    role: GroupRole.parse(json.str('role')),
  );

  final String userId;
  final String name;
  final String email;
  final GroupRole? role;

  bool get isAdmin => role?.isAdmin ?? false;

  @override
  String toString() => 'GroupMember($name, ${role?.wire})';
}

/// Derived from the timestamps server-side, never stored.
enum InvitationStatus {
  pending,
  accepted,
  expired;

  static InvitationStatus? parse(String? value) =>
      switch (value?.toUpperCase()) {
        'PENDING' => InvitationStatus.pending,
        'ACCEPTED' => InvitationStatus.accepted,
        'EXPIRED' => InvitationStatus.expired,
        _ => null,
      };

  String get wire => name.toUpperCase();
}

/// `InvitationDTO.Read` — the **admin's** view, returned by
/// `POST /invitations/invite/{groupId}` and by accepting one.
class Invitation {
  const Invitation({
    required this.invitationId,
    required this.groupId,
    required this.invitationEmail,
    this.invitationBy,
    this.invitationCreatedAt,
    this.invitationExpiresAt,
    this.invitationStatus,
  });

  factory Invitation.fromJson(Map<String, dynamic> json) => Invitation(
    invitationId: json.str('invitationId') ?? '',
    groupId: json.str('groupId') ?? '',
    invitationEmail: json.str('invitationEmail') ?? '',
    invitationBy: json.str('invitationBy'),
    invitationCreatedAt: json.instant('invitationCreatedAt'),
    invitationExpiresAt: json.instant('invitationExpiresAt'),
    invitationStatus: InvitationStatus.parse(json.str('invitationStatus')),
  );

  final String invitationId;
  final String groupId;

  /// Trimmed and lowercased by the server.
  final String invitationEmail;

  /// User id of the admin who sent it.
  final String? invitationBy;

  final DateTime? invitationCreatedAt;

  /// Seven days after creation.
  final DateTime? invitationExpiresAt;

  final InvitationStatus? invitationStatus;

  bool get isPending => invitationStatus == InvitationStatus.pending;

  @override
  String toString() => 'Invitation($invitationEmail, ${invitationStatus?.wire})';
}

/// `InvitationDTO.Pending` — the **invitee's** view
/// (`GET /invitations/pending`).
///
/// A different shape on purpose: the invitee sees the group's *name*, not the
/// inviter's user id. Every id listed here can be accepted right now.
class PendingInvitation {
  const PendingInvitation({
    required this.id,
    required this.groupId,
    required this.groupName,
    this.invitationCreatedAt,
    this.invitationExpiresAt,
  });

  factory PendingInvitation.fromJson(Map<String, dynamic> json) =>
      PendingInvitation(
        id: json.str('id') ?? '',
        groupId: json.str('groupId') ?? '',
        groupName: json.str('groupName') ?? '',
        invitationCreatedAt: json.instant('invitationCreatedAt'),
        invitationExpiresAt: json.instant('invitationExpiresAt'),
      );

  final String id;
  final String groupId;
  final String groupName;
  final DateTime? invitationCreatedAt;
  final DateTime? invitationExpiresAt;

  @override
  String toString() => 'PendingInvitation($groupName)';
}
