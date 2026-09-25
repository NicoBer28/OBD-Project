import 'package:obd_app/models/models.dart';

/// Where reservations live. The rest of the app only knows this interface,
/// so swapping the in-memory demo for the real API means adding one class
/// (e.g. `ApiReservationRepository`) and changing one line in `MainScreen`.
abstract class ReservationRepository {
  /// The car's reservations sorted by start. Emits the current list on
  /// listen and again after every change.
  Stream<List<Reservation>> watch(String carId);

  /// Validates and inserts in ONE atomic step. Throws [ReservationRejected]
  /// when the slot is invalid or overlaps an existing reservation.
  ///
  /// A real backend must enforce this itself (transaction, or a Postgres
  /// exclusion constraint); the client-side check is only for fast feedback.
  Future<Reservation> create({
    required String carId,
    required String userId,
    required DateTime start,
    required DateTime end,
    String? note,
  });

  /// Only the owner may cancel; otherwise throws [ReservationRejected].
  Future<void> cancel(String reservationId, {required String requesterId});
}
