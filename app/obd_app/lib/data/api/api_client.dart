import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:obd_app/data/api/api_config.dart';
import 'package:obd_app/data/api/api_exception.dart';
import 'package:obd_app/data/api/models/api_models.dart';
import 'package:obd_app/data/api/session.dart';

/// A successful response, already decoded.
class ApiResponse {
  const ApiResponse({
    required this.statusCode,
    required this.headers,
    required this.body,
  });

  final int statusCode;
  final Map<String, String> headers;

  /// Decoded JSON: a `Map`, a `List`, or null for `204 No Content`.
  final Object? body;

  bool get isEmpty => body == null;

  Map<String, dynamic> get asMap =>
      body is Map<String, dynamic> ? body as Map<String, dynamic> : const {};

  /// `Location` header — `POST /cars` and `POST /groups` set it.
  String? get location => headers['location'];
}

/// The one place that knows how to talk HTTP to the OBD API.
///
/// It handles the four things every endpoint would otherwise repeat:
///
/// 1. **Bearer auth.** Every call except `auth/*` carries the access token.
/// 2. **The refresh cookie.** `package:http` has no cookie jar, so the
///    `Set-Cookie: refreshToken=...` that `login` returns is read off the
///    response and sent back by hand on `POST /auth/refresh`.
/// 3. **Silent re-auth.** A `401` on an authenticated call triggers one
///    refresh and one retry. Concurrent 401s share a single refresh, so five
///    parallel requests do not burn five single-use refresh tokens — reusing a
///    rotated-out token revokes the whole family and forces a re-login.
/// 4. **Errors.** A 4xx/5xx becomes an [ApiException] carrying the server's
///    `ProblemDetail`; an unreachable host becomes a [NetworkException]. No
///    endpoint method ever inspects a status code.
class ApiClient {
  ApiClient({ApiConfig? config, http.Client? httpClient, ObdSession? session})
    : config = config ?? ApiConfig.defaults,
      session = session ?? ObdSession(),
      _http = httpClient ?? http.Client(),
      _ownsHttpClient = httpClient == null;

  /// Name of the cookie the API sets (`RefreshCookie.NAME`).
  static const String refreshCookieName = 'refreshToken';

  final ApiConfig config;
  final ObdSession session;
  final http.Client _http;
  final bool _ownsHttpClient;

  Future<bool>? _refreshInFlight;

  static final RegExp _refreshCookiePattern = RegExp(
    '(?:^|[,;]\\s*)$refreshCookieName=([^;,]*)',
  );

  Future<ApiResponse> get(
    String path, {
    Map<String, Object?> query = const {},
    bool authenticated = true,
  }) => send('GET', path, query: query, authenticated: authenticated);

  Future<ApiResponse> post(
    String path, {
    Object? body,
    Map<String, Object?> query = const {},
    bool authenticated = true,
  }) => send(
    'POST',
    path,
    body: body,
    query: query,
    authenticated: authenticated,
  );

  Future<ApiResponse> put(
    String path, {
    Object? body,
    bool authenticated = true,
  }) => send('PUT', path, body: body, authenticated: authenticated);

  Future<ApiResponse> delete(String path, {bool authenticated = true}) =>
      send('DELETE', path, authenticated: authenticated);

  /// Performs one request. [path] is relative to `/api/v1`.
  ///
  /// Throws [ApiException] for any 4xx/5xx and [NetworkException] when the
  /// request never completed.
  Future<ApiResponse> send(
    String method,
    String path, {
    Object? body,
    Map<String, Object?> query = const {},
    bool authenticated = true,
    Map<String, String> headers = const {},
    bool retryOn401 = true,
  }) async {
    final uri = config.resolve(path, query);
    final requestHeaders = <String, String>{
      'Accept': 'application/json',
      ...headers,
    };

    if (authenticated) {
      final token = session.accessToken;
      if (token == null) {
        // Fail like the server would, so callers have one error type to
        // handle instead of two.
        throw ApiException(
          statusCode: 401,
          title: 'Unauthorized',
          detail: 'No access token — log in first',
          uri: uri,
        );
      }
      requestHeaders['Authorization'] = 'Bearer $token';
    }

    final response = await _dispatch(method, uri, requestHeaders, body);
    _rememberRefreshCookie(response.headers);

    if (response.statusCode == 401 &&
        authenticated &&
        retryOn401 &&
        session.refreshToken != null) {
      if (await refreshSession()) {
        return send(
          method,
          path,
          body: body,
          query: query,
          authenticated: authenticated,
          headers: headers,
          retryOn401: false,
        );
      }
    }

    if (response.statusCode >= 400) {
      throw ApiException.fromBody(response.statusCode, _text(response), uri: uri);
    }

    return ApiResponse(
      statusCode: response.statusCode,
      headers: response.headers,
      body: _decode(response),
    );
  }

  /// Exchanges the stored refresh cookie for a new access token.
  ///
  /// Returns false when the session is gone for good (the caller should send
  /// the user back to the login screen). Single-flight: callers that arrive
  /// while a refresh is running await the same one.
  Future<bool> refreshSession() {
    final running = _refreshInFlight;
    if (running != null) return running;

    final future = _refresh();
    _refreshInFlight = future;
    return future.whenComplete(() => _refreshInFlight = null);
  }

  /// Closes the underlying HTTP client, unless one was supplied by the caller
  /// (whoever owns it should close it).
  void close() {
    if (_ownsHttpClient) _http.close();
  }

  Future<bool> _refresh() async {
    final cookie = session.refreshToken;
    if (cookie == null) return false;

    final uri = config.resolve('/auth/refresh');
    try {
      final response = await _dispatch('POST', uri, {
        'Accept': 'application/json',
        'Cookie': '$refreshCookieName=$cookie',
      }, null);

      if (response.statusCode >= 400) {
        // Expired, already used, or revoked: this session is finished.
        session.clear();
        return false;
      }

      _rememberRefreshCookie(response.headers);
      final decoded = _decode(response);
      if (decoded is! Map<String, dynamic>) {
        session.clear();
        return false;
      }
      session.save(AuthSession.fromJson(decoded));
      return true;
    } on NetworkException {
      // Offline. The tokens may still be perfectly good, so keep them and let
      // the next call try again.
      return false;
    }
  }

  Future<http.Response> _dispatch(
    String method,
    Uri uri,
    Map<String, String> headers,
    Object? body,
  ) async {
    try {
      final request = http.Request(method, uri);
      request.headers.addAll(headers);
      if (body != null) {
        request.headers['Content-Type'] = 'application/json; charset=utf-8';
        request.body = jsonEncode(body);
      }
      final streamed = await _http.send(request).timeout(config.timeout);
      return await http.Response.fromStream(streamed);
    } catch (error) {
      throw NetworkException(uri: uri, cause: error);
    }
  }

  /// Decodes as UTF-8 explicitly: `http.Response.body` falls back to latin-1
  /// when the response carries no charset, which mangles accented text — and
  /// `application/problem+json` carries none.
  String _text(http.Response response) =>
      utf8.decode(response.bodyBytes, allowMalformed: true);

  Object? _decode(http.Response response) {
    if (response.statusCode == 204 || response.bodyBytes.isEmpty) return null;
    try {
      return jsonDecode(_text(response));
    } on FormatException {
      return null;
    }
  }

  void _rememberRefreshCookie(Map<String, String> headers) {
    final raw = headers['set-cookie'];
    if (raw == null) return;

    final match = _refreshCookiePattern.firstMatch(raw);
    if (match == null) return;

    final value = match.group(1) ?? '';
    // Logout clears the cookie by setting it empty.
    session.rememberRefreshToken(value.isEmpty ? null : value);
  }
}
