/// Tolerant readers for decoded JSON maps.
///
/// Two things make a plain `json['x'] as int?` unsafe here:
///
/// * JSON has one number type, so a latitude of exactly `-34` arrives as an
///   `int` and a cast to `double` throws.
/// * A field the API stopped sending (or has not started sending yet) becomes
///   `null`, and a hard cast turns that into a crash in the middle of a list.
///
/// These return `null` instead of throwing, so a single odd field degrades one
/// value rather than the whole screen.
extension JsonMap on Map<String, dynamic> {
  String? str(String key) {
    final value = this[key];
    return value == null ? null : '$value';
  }

  int? integer(String key) {
    final value = this[key];
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value);
    return null;
  }

  double? decimal(String key) {
    final value = this[key];
    if (value is num) return value.toDouble();
    if (value is String) return double.tryParse(value);
    return null;
  }

  bool flag(String key, {bool fallback = false}) {
    final value = this[key];
    if (value is bool) return value;
    if (value is String) return value.toLowerCase() == 'true';
    return fallback;
  }

  /// Parses an ISO-8601 `Instant` into a UTC [DateTime].
  DateTime? instant(String key) {
    final value = this[key];
    if (value is! String || value.isEmpty) return null;
    return DateTime.tryParse(value)?.toUtc();
  }

  Map<String, dynamic>? object(String key) {
    final value = this[key];
    return value is Map<String, dynamic> ? value : null;
  }
}

/// Maps a decoded JSON array onto [parse], skipping anything that is not an
/// object.
List<T> parseList<T>(
  Object? body,
  T Function(Map<String, dynamic> json) parse,
) {
  if (body is! List) return const [];
  return body.whereType<Map<String, dynamic>>().map(parse).toList(growable: false);
}
