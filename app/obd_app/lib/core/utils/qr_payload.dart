/// Qué codifica el QR de la app y cómo se lee de vuelta.
///
/// La API no tiene "unirse con un código": para entrar a un grupo hace falta
/// que un admin invite un **correo** y que esa persona acepte. Por eso el QR
/// va en la dirección que sí funciona con el backend: **quien quiere entrar
/// muestra su código**, el admin lo escanea y la app completa el correo en la
/// invitación. Nada se firma ni se valida contra el servidor — el correo es
/// público de todos modos y el que decide es el invitado, al aceptar.
///
/// Formato: `obdc://invite?email=<correo>&name=<nombre>`. Un scheme propio
/// evita que la cámara del teléfono lo abra como link web si alguien lo
/// escanea por fuera de la app.
abstract final class QrPayload {
  static const scheme = 'obdc';
  static const host = 'invite';

  static String invite({required String email, String? name}) {
    final trimmedName = name?.trim();
    return Uri(
      scheme: scheme,
      host: host,
      queryParameters: {
        'email': email.trim().toLowerCase(),
        if (trimmedName != null && trimmedName.isNotEmpty) 'name': trimmedName,
      },
    ).toString();
  }

  /// Devuelve `(email, name)` o null si el texto no es un QR de la app.
  static ({String email, String? name})? parseInvite(String raw) {
    final uri = Uri.tryParse(raw.trim());
    if (uri == null || uri.scheme != scheme || uri.host != host) return null;

    final email = uri.queryParameters['email']?.trim().toLowerCase();
    if (email == null || email.isEmpty || !email.contains('@')) return null;

    final name = uri.queryParameters['name']?.trim();
    return (email: email, name: name == null || name.isEmpty ? null : name);
  }
}
