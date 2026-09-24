/// Small Spanish formatters for the reservation UI (no `intl` needed).
///
/// Every function accepts UTC or local values and formats in local time.
abstract final class ReservationFormat {
  static const _weekdays = ['LUN', 'MAR', 'MIÉ', 'JUE', 'VIE', 'SÁB', 'DOM'];
  static const _weekdaysLower = ['lun', 'mar', 'mié', 'jue', 'vie', 'sáb', 'dom'];
  static const _months = [
    'ene', 'feb', 'mar', 'abr', 'may', 'jun',
    'jul', 'ago', 'sep', 'oct', 'nov', 'dic',
  ];

  /// Calendar days from [a] to [b] (ignores the time of day).
  static int daysBetween(DateTime a, DateTime b) {
    return DateTime.utc(b.year, b.month, b.day)
        .difference(DateTime.utc(a.year, a.month, a.day))
        .inDays;
  }

  /// 'HOY', 'MAÑ' or 'SÁB 20' — matches the compact style of the schedule list.
  static String dayLabel(DateTime day, DateTime now) {
    final d = day.toLocal();
    return switch (daysBetween(now.toLocal(), d)) {
      0 => 'HOY',
      1 => 'MAÑ',
      _ => '${_weekdays[d.weekday - 1]} ${d.day}',
    };
  }

  /// 'Hoy', 'Mañana' or 'sáb 20 sep'.
  static String longDate(DateTime day, DateTime now) {
    final d = day.toLocal();
    return switch (daysBetween(now.toLocal(), d)) {
      0 => 'Hoy',
      1 => 'Mañana',
      _ => '${_weekdaysLower[d.weekday - 1]} ${d.day} ${_months[d.month - 1]}',
    };
  }

  /// '18:30'
  static String clock(DateTime t) {
    final l = t.toLocal();
    return '${_two(l.hour)}:${_two(l.minute)}';
  }

  /// '18–21', '09:30–11:00', 'todo', '18–24', '22–02 +1'.
  static String timeRange(DateTime start, DateTime end) {
    final s = start.toLocal();
    final e = end.toLocal();
    final days = daysBetween(s, e);
    final startsAtMidnight = s.hour == 0 && s.minute == 0;
    final endsAtMidnight = e.hour == 0 && e.minute == 0;

    if (startsAtMidnight && endsAtMidnight && days == 1) return 'todo';

    final withMinutes = s.minute != 0 || e.minute != 0;
    String hm(int hour, int minute) =>
        withMinutes ? '${_two(hour)}:${_two(minute)}' : _two(hour);

    // An end at exactly 00:00 reads better as 24:00 of the previous day.
    var endHour = e.hour;
    var span = days;
    if (endsAtMidnight && days >= 1) {
      endHour = 24;
      span = days - 1;
    }
    final tail = span == 0 ? '' : ' +$span';
    return '${hm(s.hour, s.minute)}–${hm(endHour, e.minute)}$tail';
  }

  /// '2 h', '1 h 30 min', '1 d 2 h'.
  static String durationLabel(Duration d) {
    final parts = <String>[
      if (d.inDays > 0) '${d.inDays} d',
      if (d.inHours % 24 > 0) '${d.inHours % 24} h',
      if (d.inMinutes % 60 > 0) '${d.inMinutes % 60} min',
    ];
    return parts.isEmpty ? '0 min' : parts.join(' ');
  }

  static String _two(int n) => n.toString().padLeft(2, '0');
}
