import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/reservation_format.dart';
import 'package:obd_app/data/reservation_repository.dart';
import 'package:obd_app/models/models.dart';

/// Everything the UI needs to know about the car's reservations.
///
/// It follows the repository stream, exposes the derived views the screens
/// use (this week's list, the current user's next turn), and forwards the
/// two actions (reserve / cancel). Widgets listen to it with
/// `ListenableBuilder`, so only the sections that show reservations rebuild.
class ReservationsController extends ChangeNotifier {
  ReservationsController({
    required ReservationRepository repository,
    required this.carId,
    required this.currentUserId,
    required List<MemberData> members,
  }) : _repository = repository,
       _members = members {
    _subscription = repository.watch(carId).listen(_onData, onError: _onError);
  }

  final ReservationRepository _repository;
  final List<MemberData> _members;
  final String carId;
  final String currentUserId;

  late final StreamSubscription<List<Reservation>> _subscription;
  List<Reservation> _reservations = const [];
  Object? _error;

  /// All of the car's reservations, sorted by start.
  List<Reservation> get reservations => _reservations;

  /// Last error from the repository stream, or null.
  Object? get error => _error;

  bool isMine(Reservation r) => r.userId == currentUserId;

  MemberData memberOf(String userId) {
    return _members.firstWhere(
      (m) => m.id == userId,
      orElse: () => MemberData(
        id: userId,
        initials: '?',
        name: 'Miembro',
        color: AppPalette.surface2,
        fuelShare: 0,
        fuelAmount: '',
      ),
    );
  }

  /// Reservations still running, or starting within the next [days] days.
  List<Reservation> upcoming({DateTime? now, int days = 7}) {
    final from = now ?? DateTime.now();
    final until = from.add(Duration(days: days));

    return _reservations.where((r) => r.end.isAfter(from) && r.start.isBefore(until)).toList();
  }

  /// The current user's next (or ongoing) reservation.
  Reservation? nextTurn({DateTime? now}) {
    final from = now ?? DateTime.now();

    for (final r in _reservations) {
      if (isMine(r) && r.end.isAfter(from)) return r;
    }
    return null;
  }

  /// Reservation -> the string-based row the schedule widgets draw.
  ScheduleSlot slotFor(Reservation r, {DateTime? now}) {
    final member = memberOf(r.userId);
    final who = isMine(r) ? 'Vos' : member.name;

    return ScheduleSlot(
      day: ReservationFormat.dayLabel(r.start, now ?? DateTime.now()),
      time: ReservationFormat.timeRange(r.start, r.end),
      person: r.note == null ? who : '$who · ${r.note}',
      color: member.color,
    );
  }

  /// Client-side validation against what we currently know. The repository
  /// (and the API) still have the last word.
  ReservationCheck check(DateTime start, DateTime end, {DateTime? now}) {
    return ReservationRules.validate(
      start: start,
      end: end,
      now: now ?? DateTime.now(),
      existing: _reservations,
    );
  }

  /// Throws [ReservationRejected] if the slot is taken or invalid.
  Future<Reservation> reserve({
    required DateTime start,
    required DateTime end,
    String? note,
  }) {
    return _repository.create(
      carId: carId,
      userId: currentUserId,
      start: start,
      end: end,
      note: note,
    );
  }

  /// Throws [ReservationRejected] if the reservation is not the user's.
  Future<void> cancel(Reservation reservation) {
    return _repository.cancel(reservation.id, requesterId: currentUserId);
  }

  void _onData(List<Reservation> list) {
    _reservations = list;
    _error = null;
    notifyListeners();
  }

  void _onError(Object error) {
    _error = error;
    notifyListeners();
  }

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}
