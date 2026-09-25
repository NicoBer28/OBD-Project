import 'package:flutter/foundation.dart';
import 'package:obd_app/data/api/models/api_models.dart';
import 'package:obd_app/data/api/session_store.dart';

/// Who is signed in, and the two tokens that prove it.
///
/// A [ChangeNotifier] so a widget can rebuild when the session starts or ends
/// (`ListenableBuilder`), which is how an app-wide "logged out" redirect is
/// wired without a state-management package.
///
/// **Only the refresh token is persisted** (through a [SessionStore], the OS
/// keystore in the real app). The access token, its expiry and the user
/// identity stay in memory: after a restart [restore] brings the refresh token
/// back and `ApiClient.refreshSession` trades it for all of those again.
///
/// Persistence is optional. Without a store — as in the unit tests — the
/// session behaves exactly as it always did and closing the app signs out.
class ObdSession extends ChangeNotifier {
  ObdSession({SessionStore? store}) : _store = store;

  final SessionStore? _store;

  /// Tail of the queue of pending disk writes. Chained so that a token saved
  /// and then cleared right after can never land on disk in the wrong order.
  Future<void> _writes = Future<void>.value();

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

  /// Loads the refresh token saved by a previous run of the app.
  ///
  /// Call once, before the first screen, and only then look at
  /// [refreshToken]. This does **not** make the session [isAuthenticated]:
  /// there is still no access token, so the caller has to run
  /// `ApiClient.refreshSession` to get one.
  ///
  /// A store that cannot be read (keystore reset, data restored from a backup
  /// onto another device) is treated as "signed out", never as an error.
  Future<void> restore() async {
    final store = _store;
    if (store == null) return;
    try {
      final saved = await store.readRefreshToken();
      if (saved != null && saved.isNotEmpty) {
        _refreshToken = saved;
        notifyListeners();
      }
    } catch (_) {
      _persist(null);
    }
  }

  /// Completes when every disk write started so far has finished. Only tests
  /// need to wait for this; the app fires and forgets.
  @visibleForTesting
  Future<void> get pendingWrites => _writes;

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
    _persist(value);
    notifyListeners();
  }

  /// Forgets everything. Called on logout and whenever a refresh is refused.
  void clear() {
    if (_accessToken == null && _refreshToken == null && _userId == null && _email == null) {
      return;
    }
    _accessToken = null;
    _refreshToken = null;
    _expiresAt = null;
    _userId = null;
    _email = null;
    _persist(null);
    notifyListeners();
  }

  /// Mirrors the refresh token to the store, or deletes it when [token] is
  /// null. Best effort: if the write fails the app still works, the user just
  /// has to log in again after the next restart.
  void _persist(String? token) {
    final store = _store;
    if (store == null) return;
    _writes = _writes.then((_) async {
      try {
        if (token == null) {
          await store.deleteRefreshToken();
        } else {
          await store.writeRefreshToken(token);
        }
      } catch (_) {
        // Nothing useful to do; see above.
      }
    });
  }

  @override
  String toString() => 'ObdSession(${isAuthenticated ? _email : 'signed out'})';
}
