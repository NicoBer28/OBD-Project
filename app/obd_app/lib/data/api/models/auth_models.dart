import 'package:obd_app/data/api/models/json.dart';

/// `AuthResponseDTO` — what `register`, `login` and `refresh` return.
///
/// The refresh token is **not** here: it travels as an `httpOnly` cookie and
/// is picked up by `ApiClient`, not by this class.
class AuthSession {
  const AuthSession({
    required this.accessToken,
    required this.tokenType,
    required this.expireInSeconds,
    required this.userId,
    required this.email,
  });

  factory AuthSession.fromJson(Map<String, dynamic> json) => AuthSession(
    accessToken: json.str('accessToken') ?? '',
    tokenType: json.str('tokenType') ?? 'Bearer',
    expireInSeconds: json.integer('expireInSeconds') ?? 0,
    userId: json.str('userId') ?? '',
    email: json.str('email') ?? '',
  );

  /// Short-lived JWT. Sent as `Authorization: Bearer <accessToken>`.
  final String accessToken;

  /// Always `Bearer`.
  final String tokenType;

  /// Lifetime of [accessToken] — 900 (15 minutes) at the time of writing.
  final int expireInSeconds;

  /// UUID of the account that just signed in.
  final String userId;

  final String email;

  @override
  String toString() => 'AuthSession($email, expires in ${expireInSeconds}s)';
}

/// `UserDTO.Read` — the caller's own profile (`GET /users/me`).
class UserProfile {
  const UserProfile({
    required this.id,
    required this.userName,
    required this.userLastName,
    required this.userEmail,
    this.userPhone,
  });

  factory UserProfile.fromJson(Map<String, dynamic> json) => UserProfile(
    id: json.str('id') ?? '',
    // First name, not a nickname: the same field `register` takes.
    userName: json.str('userName') ?? '',
    userLastName: json.str('userLastName') ?? '',
    userEmail: json.str('userEmail') ?? '',
    userPhone: json.str('userPhone'),
  );

  final String id;
  final String userName;
  final String userLastName;
  final String userEmail;
  final String? userPhone;

  String get fullName => '$userName $userLastName'.trim();

  /// Two letters for the avatar, e.g. `AL` for Ada Lovelace.
  String get initials {
    final first = userName.isNotEmpty ? userName[0] : '';
    final last = userLastName.isNotEmpty ? userLastName[0] : '';
    final letters = '$first$last'.trim();
    if (letters.isNotEmpty) return letters.toUpperCase();
    return userEmail.isNotEmpty ? userEmail[0].toUpperCase() : '?';
  }

  @override
  String toString() => 'UserProfile($fullName, $userEmail)';
}
