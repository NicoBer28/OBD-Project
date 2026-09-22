import 'dart:convert';

/// Anything that can go wrong while talking to the API.
///
/// Sealed so a `switch` over it is exhaustive: either the server answered and
/// refused ([ApiException]), or it never answered at all ([NetworkException]).
sealed class ObdApiException implements Exception {
  const ObdApiException();

  /// Short, technical description. Screens translate this into user-facing
  /// copy — the data layer does not know what language the UI speaks.
  String get message;
}

/// The server answered with 4xx/5xx.
///
/// The API returns RFC 7807 `ProblemDetail` bodies:
///
/// ```json
/// { "type": "about:blank", "title": "Conflict", "status": 409,
///   "detail": "That email is already registered" }
/// ```
///
/// and, for a validation failure, an extra `errors` object keyed by field:
///
/// ```json
/// { "title": "Validation failed", "status": 400,
///   "errors": { "userPassword": "size must be between 8 and 72" } }
/// ```
class ApiException extends ObdApiException {
  const ApiException({
    required this.statusCode,
    this.title,
    this.detail,
    this.fieldErrors = const {},
    this.uri,
  });

  /// Parses a `ProblemDetail` body; falls back to the raw text when the body
  /// is not JSON (a proxy error page, for instance).
  factory ApiException.fromBody(int statusCode, String body, {Uri? uri}) {
    String? title;
    String? detail;
    final errors = <String, String>{};

    if (body.trim().isNotEmpty) {
      try {
        final decoded = jsonDecode(body);
        if (decoded is Map) {
          title = decoded['title'] as String?;
          detail = decoded['detail'] as String?;
          final raw = decoded['errors'];
          if (raw is Map) {
            raw.forEach((key, value) => errors['$key'] = '$value');
          }
        } else {
          detail = body;
        }
      } on FormatException {
        detail = body;
      }
    }

    return ApiException(
      statusCode: statusCode,
      title: title,
      detail: detail,
      fieldErrors: Map.unmodifiable(errors),
      uri: uri,
    );
  }

  final int statusCode;

  /// `title` from the problem detail, e.g. `Conflict`.
  final String? title;

  /// `detail` from the problem detail, e.g. `That car is already on a trip`.
  final String? detail;

  /// Field name to message, from a `400`. Telemetry batches key by index:
  /// `readings[2].positionComplete`.
  final Map<String, String> fieldErrors;

  /// The request that failed. Handy in logs; never shown to a user.
  final Uri? uri;

  /// Malformed body, bad UUID in the path, or a failed validation.
  bool get isValidation => statusCode == 400;

  /// Missing, expired or rejected access token.
  bool get isUnauthorized => statusCode == 401;

  /// Authenticated but not allowed — only raised where the caller already
  /// knows the thing exists (not a group admin, not an account admin).
  bool get isForbidden => statusCode == 403;

  /// Does not exist **or** is not yours. The API answers both the same way on
  /// purpose, so an id is never confirmed to a stranger.
  bool get isNotFound => statusCode == 404;

  /// State conflict: email taken, plate taken, car already on a trip,
  /// invitation already accepted, dongle paired elsewhere.
  bool get isConflict => statusCode == 409;

  bool get isServerError => statusCode >= 500;

  @override
  String get message {
    if (fieldErrors.isNotEmpty) {
      return fieldErrors.entries.map((e) => '${e.key}: ${e.value}').join('\n');
    }
    return detail ?? title ?? 'HTTP $statusCode';
  }

  @override
  String toString() => 'ApiException($statusCode: $message)';
}

/// The request never completed: no route to the host, DNS failure, TLS error,
/// or the configured timeout elapsed.
class NetworkException extends ObdApiException {
  const NetworkException({required this.uri, required this.cause});

  final Uri uri;
  final Object cause;

  @override
  String get message => 'Could not reach $uri ($cause)';

  @override
  String toString() => 'NetworkException($message)';
}
