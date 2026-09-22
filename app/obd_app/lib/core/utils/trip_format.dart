/// Formateadores en español para autos y viajes (sin `intl`, como
/// `ReservationFormat`).
abstract final class TripFormat {
  static const _weekdays = ['Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb', 'Dom'];
  static const _months = [
    'ene',
    'feb',
    'mar',
    'abr',
    'may',
    'jun',
    'jul',
    'ago',
    'sep',
    'oct',
    'nov',
    'dic',
  ];
  static const _monthsLong = [
    'enero',
    'febrero',
    'marzo',
    'abril',
    'mayo',
    'junio',
    'julio',
    'agosto',
    'septiembre',
    'octubre',
    'noviembre',
    'diciembre',
  ];

  static int _daysBetween(DateTime a, DateTime b) => DateTime.utc(
    b.year,
    b.month,
    b.day,
  ).difference(DateTime.utc(a.year, a.month, a.day)).inDays;

  /// 'Hoy', 'Ayer', 'Dom, 31 ago' — para fechas pasadas.
  static String dayLabel(DateTime when, [DateTime? now]) {
    final d = when.toLocal();
    final ago = _daysBetween(d, (now ?? DateTime.now()).toLocal());
    return switch (ago) {
      0 => 'Hoy',
      1 => 'Ayer',
      _ => '${_weekdays[d.weekday - 1]}, ${d.day} ${_months[d.month - 1]}',
    };
  }

  /// 'sáb 14'
  static String shortDay(DateTime when) {
    final d = when.toLocal();
    return '${_weekdays[d.weekday - 1].toLowerCase()} ${d.day}';
  }

  /// '14 sep'
  static String dayMonth(DateTime when) {
    final d = when.toLocal();
    return '${d.day} ${_months[d.month - 1]}';
  }

  /// 'septiembre'
  static String monthName(DateTime when) =>
      _monthsLong[when.toLocal().month - 1];

  /// '18:30'
  static String clock(DateTime t) {
    final l = t.toLocal();
    return '${_two(l.hour)}:${_two(l.minute)}';
  }

  /// '18:30 → 19:10', o '18:30 → en curso' si no terminó.
  static String timeRange(DateTime start, DateTime? end) =>
      '${clock(start)} → ${end == null ? 'en curso' : clock(end)}';

  /// '2 h', '1 h 30 min', '45 min', '1 d 2 h'.
  static String duration(Duration d) {
    final parts = <String>[
      if (d.inDays > 0) '${d.inDays} d',
      if (d.inHours % 24 > 0) '${d.inHours % 24} h',
      if (d.inMinutes % 60 > 0) '${d.inMinutes % 60} min',
    ];
    return parts.isEmpty ? '0 min' : parts.join(' ');
  }

  /// 'hace 5 min', 'hace 3 h', 'hace 2 d', 'recién'.
  static String ago(DateTime when, [DateTime? now]) {
    final d = (now ?? DateTime.now()).toUtc().difference(when.toUtc());
    if (d.inSeconds < 60) return 'recién';
    if (d.inMinutes < 60) return 'hace ${d.inMinutes} min';
    if (d.inHours < 24) return 'hace ${d.inHours} h';
    if (d.inDays < 30) return 'hace ${d.inDays} d';
    return 'el ${dayMonth(when)}';
  }

  /// '12.400' — separador de miles con punto, como se escribe acá.
  static String thousands(num n) {
    final s = n.round().abs().toString();
    final buffer = StringBuffer();
    for (var i = 0; i < s.length; i++) {
      if (i > 0 && (s.length - i) % 3 == 0) buffer.write('.');
      buffer.write(s[i]);
    }
    return n < 0 ? '-$buffer' : buffer.toString();
  }

  /// '12.400 km'
  static String km(num n) => '${thousands(n)} km';

  /// '-34,6037, -58,3816'
  static String coordinates(double lat, double lng) =>
      '${lat.toStringAsFixed(4).replaceAll('.', ',')}, '
      '${lng.toStringAsFixed(4).replaceAll('.', ',')}';

  static String _two(int n) => n.toString().padLeft(2, '0');
}
