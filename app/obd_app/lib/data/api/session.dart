import 'package:flutter/foundation.dart';
import 'package:obd_app/data/api/models/api_models.dart';

/// Who is signed in, and the two tokens that prove it.
///
/// A [ChangeNotifier] so a widget can rebuild when the session starts or ends
/// (`ListenableBuilder`), which is how an app-wide "logged out" redirect is
/// wired without a state-management package.
///
/// **Nothing is written to disk.** Closing the app signs the user out. Adding
/// persistence is one class: store [accessToken], [refreshToken] and
/// [expiresAt] in `flutter_secure_storage` on every change and restore them
/// before `runApp`. It was left out on purpose — a refresh token in plain
/// `SharedPreferences` is worse than asking for the password again.
class ObdSession extends ChangeNotifier {
  String? _accessToken;
  String? _refreshToken;
  DateTime? _expiresAt;
  String? _userId;
  String? _email;

  /// Short-lived JWT sent as `Authorization: Bearer <token>`.
  String? get accessToken => _accessToken;

  /// The value of the `refreshToken` cookie the server set. In a browser this
  /// is invisible to JavaScript; on a phone there is no cookie jar at all, so
  /// the client holds it and sends it back by hand on `POST /auth/refresh`.
  String? get refreshToken => _refreshToken;

  /// When [accessToken] stops being accepted (issued-at + `expireInSeconds`).
  DateTime? get expiresAt => _expiresAt;

  String? get userId => _userId;

  String? get email => _email;

  bool get isAuthenticated => _accessToken != null;

  /// True once the access token is past its advertised lifetime. The client
  /// does not pre-emptively refresh on this — it reacts to a real `401` — but
  /// it is useful for deciding whether to bother calling at all.
  bool get isExpired {
    final expiry = _expiresAt;
    return expiry != null && DateTime.now().isAfter(expiry);
  }

  /// Stores what `register` / `login` / `refresh` returned.
  void save(AuthSession auth) {
    _accessToken = auth.accessToken;
    _expiresAt = DateTime.now().add(Duration(seconds: auth.expireInSeconds));
    _userId = auth.userId;
    _email = auth.email;
    notifyListeners();
  }

  /// Called by the client for every `Set-Cookie: refreshToken=...` it sees.
  /// A cleared cookie (logout) arrives as null.
  void rememberRefreshToken(String? value) {
    if (_refreshToken == value) return;
    _refreshToken = value;
    notifyListeners();
  }

  /// Forgets everything. Called on logout and whenever a refresh is refused.
  void clear() {
    if (_accessToken == null &&
        _refreshToken == null &&
        _userId == null &&
        _email == null) {
      return;
    }
    _accessToken = null;
    _refreshToken = null;
    _expiresAt = null;
    _userId = null;
    _email = null;
    notifyListeners();
  }

  @override
  String toString() =>
      'ObdSession(${isAuthenticated ? _email : 'signed out'})';
}
