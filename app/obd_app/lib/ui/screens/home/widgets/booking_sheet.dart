import 'package:flutter/material.dart';
import 'package:obd_app/controllers/reservations_controller.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/reservation_format.dart';
import 'package:obd_app/models/models.dart';

/// Bottom sheet to reserve the car. Pops with the created [Reservation],
/// or null if the user dismisses it.
///
/// It listens to the controller, so a booking made by someone else while the
/// sheet is open shows up as a conflict immediately.
class BookingSheet extends StatefulWidget {
  final ReservationsController controller;

  const BookingSheet({super.key, required this.controller});

  static Future<Reservation?> show(
    BuildContext context, {
    required ReservationsController controller,
  }) {
    return showModalBottomSheet<Reservation>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: context.tokens.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => BookingSheet(controller: controller),
    );
  }

  @override
  State<BookingSheet> createState() => _BookingSheetState();
}

class _BookingSheetState extends State<BookingSheet> {
  final _note = TextEditingController();

  // Local times. They become UTC inside Reservation.
  late DateTime _start;
  late DateTime _end;
  bool _saving = false;
  String? _serverError;

  @override
  void initState() {
    super.initState();
    _start = ReservationRules.roundUpToQuarter(DateTime.now());
    _end = _start.add(const Duration(hours: 2));
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  void _edit(VoidCallback change) {
    setState(() {
      _serverError = null;
      change();
    });
  }

  Future<void> _pickDate({required bool isStart}) async {
    final today = DateTime.now();
    final firstDate = DateTime(today.year, today.month, today.day);
    final current = isStart ? _start : _end;

    final picked = await showDatePicker(
      context: context,
      initialDate: current.isBefore(firstDate) ? firstDate : current,
      firstDate: firstDate,
      lastDate: firstDate.add(const Duration(days: 365)),
    );
    if (picked == null || !mounted) return;

    _edit(() {
      if (isStart) {
        final length = _end.difference(_start);
        _start = DateTime(
          picked.year,
          picked.month,
          picked.day,
          _start.hour,
          _start.minute,
        );
        _end = _start.add(length);
      } else {
        _end = DateTime(
          picked.year,
          picked.month,
          picked.day,
          _end.hour,
          _end.minute,
        );
      }
    });
  }

  Future<void> _pickTime({required bool isStart}) async {
    final current = isStart ? _start : _end;

    final picked = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(current),
    );
    if (picked == null || !mounted) return;

    _edit(() {
      if (isStart) {
        // Moving the start keeps the duration, like a normal calendar.
        final length = _end.difference(_start);
        _start = DateTime(
          _start.year,
          _start.month,
          _start.day,
          picked.hour,
          picked.minute,
        );
        _end = _start.add(length);
      } else {
        _end = DateTime(
          _end.year,
          _end.month,
          _end.day,
          picked.hour,
          picked.minute,
        );
      }
    });
  }

  /// Whole day, or from now until midnight when the day is today.
  void _setAllDay() {
    final now = DateTime.now();
    final midnight = DateTime(_start.year, _start.month, _start.day);
    final isToday = ReservationFormat.daysBetween(now, midnight) == 0;
    final nextMidnight = DateTime(
      midnight.year,
      midnight.month,
      midnight.day + 1,
    );

    _start = isToday ? ReservationRules.roundUpToQuarter(now) : midnight;
    _end = nextMidnight.isAfter(_start)
        ? nextMidnight
        : _start.add(const Duration(hours: 1));
  }

  Future<void> _submit() async {
    setState(() {
      _saving = true;
      _serverError = null;
    });

    final note = _note.text.trim();
    try {
      final created = await widget.controller.reserve(
        start: _start,
        end: _end,
        note: note.isEmpty ? null : note,
      );
      if (!mounted) return;
      Navigator.of(context).pop(created);
    } on ReservationRejected catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _serverError = _messageFor(e.check);
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _serverError = 'No pudimos guardar la reserva. Probá de nuevo.';
      });
    }
  }

  String _messageFor(ReservationCheck check) {
    final error = check.error;
    if (error == null) return '';

    return switch (error) {
      ReservationError.endBeforeStart =>
        'El fin tiene que ser posterior al inicio.',
      ReservationError.inThePast => 'El inicio ya pasó.',
      ReservationError.tooShort =>
        'La reserva mínima es de ${ReservationFormat.durationLabel(ReservationRules.minDuration)}.',
      ReservationError.tooLong =>
        'La reserva máxima es de ${ReservationFormat.durationLabel(ReservationRules.maxDuration)}.',
      ReservationError.overlaps => _overlapMessage(check.conflict),
      ReservationError.notOwner => 'Solo podés cancelar tus propias reservas.',
    };
  }

  String _overlapMessage(Reservation? other) {
    if (other == null) return 'Ese horario ya está reservado.';

    final c = widget.controller;
    final who = c.isMine(other)
        ? 'tu reserva'
        : 'la reserva de ${c.memberOf(other.userId).name}';
    final when =
        ReservationFormat.longDate(other.start, DateTime.now()).toLowerCase();
    final range = ReservationFormat.timeRange(other.start, other.end);

    return 'Se superpone con $who ($when, $range).';
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return ListenableBuilder(
      listenable: widget.controller,
      builder: (context, _) {
        final now = DateTime.now();
        final check = widget.controller.check(_start, _end, now: now);
        final failure =
            _serverError ?? (check.isValid ? null : _messageFor(check));
        final available = failure == null;
        final statusText = failure ??
            'Disponible · ${ReservationFormat.durationLabel(_end.difference(_start))}';

        return SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
            20,
            24,
            20,
            MediaQuery.of(context).viewInsets.bottom + 24,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Reservar el auto',
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -.5,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                'Elegí cuándo lo vas a usar.',
                style: TextStyle(color: t.muted),
              ),
              const SizedBox(height: 20),
              _whenRow('Desde', _start, isStart: true, now: now),
              const SizedBox(height: 10),
              _whenRow('Hasta', _end, isStart: false, now: now),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final hours in const [1, 2, 3])
                    ActionChip(
                      label: Text('$hours h'),
                      onPressed: _saving
                          ? null
                          : () => _edit(
                              () => _end = _start.add(Duration(hours: hours)),
                            ),
                    ),
                  ActionChip(
                    label: const Text('Todo el día'),
                    onPressed: _saving ? null : () => _edit(_setAllDay),
                  ),
                ],
              ),
              const SizedBox(height: 20),
              Text(
                'Ya reservado · ${ReservationFormat.longDate(_start, now).toLowerCase()}',
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: t.muted,
                ),
              ),
              const SizedBox(height: 8),
              _DayTimeline(
                day: _start,
                reservations: widget.controller.reservations,
                start: _start,
                end: _end,
                available: available,
                memberOf: widget.controller.memberOf,
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _note,
                maxLength: 40,
                textCapitalization: TextCapitalization.sentences,
                decoration: const InputDecoration(
                  labelText: 'Destino o nota (opcional)',
                  prefixIcon: Icon(Icons.edit_note_rounded),
                  counterText: '',
                ),
              ),
              const SizedBox(height: 14),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    available
                        ? Icons.check_circle_outline_rounded
                        : Icons.error_outline_rounded,
                    size: 18,
                    color: available ? t.success : t.danger,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      statusText,
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: available ? t.text : t.danger,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              SizedBox(
                width: double.infinity,
                height: 54,
                child: FilledButton.icon(
                  onPressed: (check.isValid && !_saving) ? _submit : null,
                  icon: _saving
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.event_available_rounded),
                  label: const Text('Confirmar reserva'),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _whenRow(
    String label,
    DateTime value, {
    required bool isStart,
    required DateTime now,
  }) {
    final t = context.tokens;
    const buttonPadding = EdgeInsets.symmetric(horizontal: 12);

    return Row(
      children: [
        SizedBox(
          width: 52,
          child: Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: t.muted,
            ),
          ),
        ),
        Expanded(
          flex: 3,
          child: OutlinedButton.icon(
            style: OutlinedButton.styleFrom(padding: buttonPadding),
            onPressed: _saving ? null : () => _pickDate(isStart: isStart),
            icon: const Icon(Icons.calendar_month_rounded, size: 18),
            label: Text(ReservationFormat.longDate(value, now)),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          flex: 2,
          child: OutlinedButton.icon(
            style: OutlinedButton.styleFrom(padding: buttonPadding),
            onPressed: _saving ? null : () => _pickTime(isStart: isStart),
            icon: const Icon(Icons.schedule_rounded, size: 18),
            label: Text(ReservationFormat.clock(value)),
          ),
        ),
      ],
    );
  }
}

/// 24 h bar for one day: existing bookings in each member's colour, and the
/// proposed slot outlined in the accent colour (or the danger colour when it
/// is not available).
class _DayTimeline extends StatelessWidget {
  final DateTime day;
  final List<Reservation> reservations;
  final DateTime start;
  final DateTime end;
  final bool available;
  final MemberData Function(String userId) memberOf;

  const _DayTimeline({
    required this.day,
    required this.reservations,
    required this.start,
    required this.end,
    required this.available,
    required this.memberOf,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final proposedColor = available ? t.accent : t.danger;

    final dayStart = DateTime(day.year, day.month, day.day);
    final dayEnd = DateTime(day.year, day.month, day.day + 1);
    final totalMinutes = dayEnd.difference(dayStart).inMinutes;

    double fraction(DateTime instant) =>
        (instant.difference(dayStart).inMinutes / totalMinutes)
            .clamp(0.0, 1.0)
            .toDouble();

    final booked = reservations.where((r) => r.overlapsRange(dayStart, dayEnd));
    final proposedFrom = fraction(start);
    final proposedTo = fraction(end);

    return Column(
      children: [
        SizedBox(
          height: 44,
          child: LayoutBuilder(
            builder: (context, box) {
              final width = box.maxWidth;

              return Stack(
                children: [
                  Positioned.fill(
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: t.surface2,
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                  ),
                  for (final r in booked)
                    Positioned(
                      left: fraction(r.start) * width,
                      width: (fraction(r.end) - fraction(r.start)) * width,
                      top: 5,
                      bottom: 5,
                      child: _BookedBlock(member: memberOf(r.userId)),
                    ),
                  if (proposedTo > proposedFrom)
                    Positioned(
                      left: proposedFrom * width,
                      width: (proposedTo - proposedFrom) * width,
                      top: 0,
                      bottom: 0,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: proposedColor.withValues(alpha: .18),
                          border: Border.all(color: proposedColor, width: 2),
                          borderRadius: BorderRadius.circular(8),
                        ),
                      ),
                    ),
                ],
              );
            },
          ),
        ),
        const SizedBox(height: 4),
        const _HourTicks(),
      ],
    );
  }
}

class _BookedBlock extends StatelessWidget {
  final MemberData member;

  const _BookedBlock({required this.member});

  @override
  Widget build(BuildContext context) {
    return Container(
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: member.color,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        member.initials,
        maxLines: 1,
        softWrap: false,
        overflow: TextOverflow.clip,
        style: const TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w800,
          color: Colors.white,
        ),
      ),
    );
  }
}

class _HourTicks extends StatelessWidget {
  const _HourTicks();

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(fontSize: 10, color: context.tokens.muted);

    return SizedBox(
      height: 14,
      child: Stack(
        children: [
          for (final hour in const [0, 6, 12, 18, 24])
            Align(
              alignment: Alignment(-1 + 2 * hour / 24, 0),
              child: Text(hour.toString().padLeft(2, '0'), style: style),
            ),
        ],
      ),
    );
  }
}
