import 'package:flutter/material.dart';
import 'package:obd_app/data/api/obd_api.dart';

/// Traduce los errores del cliente de la API a copy en español.
///
/// La capa de datos no sabe qué idioma habla la UI: `ApiException.message`
/// trae el `detail` del servidor tal cual (en inglés). Acá se decide qué ve
/// la persona. Cada pantalla puede afinar el texto de los códigos que le
/// importan con [notFound] / [conflict] / [forbidden]; el resto usa un
/// mensaje genérico razonable.
abstract final class ApiMessages {
  static String of(
    Object error, {
    String? notFound,
    String? conflict,
    String? forbidden,
    String fallback = 'Algo salió mal. Probá de nuevo.',
  }) {
    if (error is NetworkException) {
      return 'No se pudo conectar con el servidor. Revisá tu conexión.';
    }
    if (error is ApiException) {
      if (error.isUnauthorized) return 'Tu sesión venció. Volvé a ingresar.';
      if (error.isForbidden) {
        return forbidden ?? 'No tenés permiso para hacer eso.';
      }
      if (error.isNotFound) return notFound ?? 'No encontramos lo que pediste.';
      if (error.isConflict) {
        return conflict ?? 'Eso choca con el estado actual. Actualizá y probá.';
      }
      if (error.isValidation) {
        if (error.fieldErrors.isNotEmpty) {
          final first = error.fieldErrors.entries.first;
          return '${_label(first.key)}: ${first.value}';
        }
        return 'Revisá los datos ingresados.';
      }
      if (error.isServerError) {
        return 'El servidor tuvo un problema. Probá de nuevo en un rato.';
      }
      return 'Error del servidor (${error.statusCode}).';
    }
    return fallback;
  }

  /// Muestra el error en un SnackBar, con el color según la gravedad.
  static void show(
    BuildContext context,
    Object error, {
    String? notFound,
    String? conflict,
    String? forbidden,
  }) {
    final text = of(
      error,
      notFound: notFound,
      conflict: conflict,
      forbidden: forbidden,
    );
    final isHard =
        error is NetworkException ||
        (error is ApiException &&
            (error.isServerError || error.isUnauthorized));

    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(text),
          backgroundColor: isHard ? Colors.red : Colors.orange,
        ),
      );
  }

  static String _label(String field) => switch (field) {
    'name' => 'Nombre',
    'modelId' => 'Modelo',
    'licensePlate' => 'Patente',
    'mileage' => 'Kilometraje',
    'email' => 'Correo',
    'serial' => 'Serial',
    'initialFuel' => 'Nafta inicial',
    'tripFinalFuel' => 'Nafta final',
    'tripDistance' => 'Distancia',
    'userName' => 'Nombre',
    'userLastName' => 'Apellido',
    'userEmail' => 'Correo',
    'userPassword' => 'Contraseña',
    'userPhone' => 'Teléfono',
    _ => field,
  };
}
