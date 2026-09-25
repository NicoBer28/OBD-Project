/// Where the API lives and how long we wait for it.
///
/// The host is read from a compile-time define so the same source can point at
/// a laptop on the LAN, an emulator or production without editing code:
///
/// ```bash
/// flutter run --dart-define=OBD_API_BASE_URL=http://10.0.2.2:8080
/// ```
///
/// Useful values:
///
/// | Running on            | Base URL                    |
/// |-----------------------|-----------------------------|
/// | Android emulator      | `http://10.0.2.2:8080`      |
/// | iOS simulator         | `http://localhost:8080`     |
/// | Physical phone (LAN)  | `http://<your-ip>:8080`     |
/// | Production            | `https://<host>`            |
class ApiConfig {
  const ApiConfig({
    required this.baseUrl,
    this.timeout = const Duration(seconds: 45),
  });

  /// Scheme + host + port, no trailing slash, no `/api/v1`.
  final String baseUrl;

  /// Applied per request. The phone is often on a flaky network, so a request
  /// that hangs must fail rather than block the UI for ever.
  ///
  /// 45 s and not 15: the dev API runs on Vercel, and a Spring Boot container
  /// waking from a cold start answers the first request in 20-25 s (measured
  /// 22.9 s for `register`). With a shorter timeout that request was reported
  /// as a network failure *after* the server had created the account.
  final Duration timeout;

  /// Everything the API exposes lives under this prefix.
  static const String prefix = '/api/v1';

  static const String _defaultBaseUrl = String.fromEnvironment(
    'OBD_API_BASE_URL',
    defaultValue: 'http://165.1.123.207:25565/',
  );

  /// What the app uses unless a test passes something else.
  static const ApiConfig defaults = ApiConfig(baseUrl: _defaultBaseUrl);

  /// Builds the absolute URL for [path] (given without the `/api/v1` prefix).
  ///
  /// Null entries in [query] are dropped, so an optional parameter can be
  /// passed straight through. [DateTime]s are sent as UTC ISO-8601, which is
  /// what the API's `Instant` fields expect.
  Uri resolve(String path, [Map<String, Object?> query = const {}]) {
    final host = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
    final route = path.startsWith('/') ? path : '/$path';
    final uri = Uri.parse('$host$prefix$route');

    final params = <String, String>{};
    query.forEach((key, value) {
      if (value == null) return;
      params[key] = value is DateTime ? value.toUtc().toIso8601String() : '$value';
    });

    return params.isEmpty ? uri : uri.replace(queryParameters: params);
  }

  ApiConfig copyWith({String? baseUrl, Duration? timeout}) => ApiConfig(
    baseUrl: baseUrl ?? this.baseUrl,
    timeout: timeout ?? this.timeout,
  );

  @override
  String toString() => 'ApiConfig($baseUrl$prefix)';
}
