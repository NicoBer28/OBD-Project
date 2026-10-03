import 'package:obd_app/data/api/models/json.dart';

/// `DeviceTokenDTO.Minted` / `DeviceTokenDTO.Read` — the credential the phone's
/// background service uploads with, scoped to **one car**.
///
/// It is sent as `Authorization: Device <token>` and may call only four
/// endpoints: `POST /telemetry`, `POST /trips`, `POST /trips/{id}/finish` and
/// `GET /cars/{carId}/trips/active`. It acts as the person who minted it.
class DeviceToken {
  const DeviceToken({
    required this.id,
    required this.carId,
    required this.label,
    this.token,
    this.createdAt,
    this.lastUsedAt,
    this.idleExpiresAt,
  });

  factory DeviceToken.fromJson(Map<String, dynamic> json) => DeviceToken(
    id: json.str('id') ?? '',
    carId: json.str('carId') ?? '',
    label: json.str('label') ?? '',
    token: json.str('token'),
    createdAt: json.instant('createdAt'),
    lastUsedAt: json.instant('lastUsedAt'),
    idleExpiresAt: json.instant('idleExpiresAt'),
  );

  final String id;
  final String carId;

  /// The phone's name, so the user can tell which one they are revoking.
  final String label;

  /// The secret itself (`obdd_…`). Only present in the response to `create`:
  /// the server keeps nothing but its hash, so it can never be read again.
  final String? token;

  final DateTime? createdAt;

  /// Server clock, updated at most once a minute. Null until first used.
  final DateTime? lastUsedAt;

  /// When the token dies **if it is not used again**: 90 days after its last
  /// use (or its creation). Every upload pushes it forward, so it is not a
  /// fixed expiry date.
  final DateTime? idleExpiresAt;

  @override
  String toString() => 'DeviceToken($id, car $carId, "$label")';
}
