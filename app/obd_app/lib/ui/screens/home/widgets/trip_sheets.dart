import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/api_messages.dart';
import 'package:obd_app/core/utils/trip_format.dart';
import 'package:obd_app/data/api/obd_api.dart';

Future<T?> _sheet<T>(BuildContext context, Widget child) {
  return showModalBottomSheet<T>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: context.tokens.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => child,
  );
}

EdgeInsets _sheetPadding(BuildContext context) => EdgeInsets.fromLTRB(
  20,
  24,
  20,
  MediaQuery.of(context).viewInsets.bottom + 24,
);

InputDecoration _fuelDecoration(String label) => InputDecoration(
  labelText: label,
  hintText: '70',
  prefixIcon: const Icon(AppIcons.fuel),
  suffixText: '%',
);

/// ---------------------------------------------------------------------------
/// Iniciar viaje — `POST /trips`
/// ---------------------------------------------------------------------------

/// El servidor pone el conductor (del token) y la hora (su reloj); lo único
/// que se elige acá es la nafta inicial, precargada con lo último que el
/// dongle reportó.
class StartTripSheet extends StatefulWidget {
  final HomeController controller;

  /// La nafta que muestra el dashboard ahora (snapshot o BLE en vivo).
  final int? currentFuel;

  const StartTripSheet({super.key, required this.controller, this.currentFuel});

  static Future<Trip?> show(
    BuildContext context,
    HomeController controller, {
    int? currentFuel,
  }) => _sheet<Trip>(
    context,
    StartTripSheet(controller: controller, currentFuel: currentFuel),
  );

  @override
  State<StartTripSheet> createState() => _StartTripSheetState();
}

class _StartTripSheetState extends State<StartTripSheet> {
  late final _fuel = TextEditingController(
    text: widget.currentFuel?.toString() ?? '',
  );
  bool _enviando = false;

  @override
  void dispose() {
    _fuel.dispose();
    super.dispose();
  }

  Future<void> _iniciar() async {
    if (_enviando) return;
    final navigator = Navigator.of(context);
    setState(() => _enviando = true);
    try {
      final text = _fuel.text.trim();
      final trip = await widget.controller.startTrip(
        initialFuel: text.isEmpty ? null : int.tryParse(text),
      );
      if (mounted) navigator.pop(trip);
    } on ObdApiException catch (error) {
      if (!mounted) return;
      if (error is ApiException && error.isConflict) {
        // Alguien más arrancó: que el dashboard lo muestre.
        widget.controller.refreshActiveTrip();
      }
      ApiMessages.show(
        context,
        error,
        conflict: 'El auto ya está en viaje con otra persona.',
        notFound: 'Ya no tenés acceso a este auto.',
      );
    } finally {
      if (mounted) setState(() => _enviando = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final car = widget.controller.car;

    return Padding(
      padding: _sheetPadding(context),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Iniciar viaje',
            style: TextStyle(
              fontSize: 24,
              fontWeight: FontWeight.w800,
              letterSpacing: -.5,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            car == null
                ? 'Elegí un auto primero.'
                : 'Vas a manejar ${car.name}. El grupo va a ver que lo tenés vos.',
            style: TextStyle(color: t.muted),
          ),
          const SizedBox(height: 22),
          TextField(
            controller: _fuel,
            enabled: !_enviando,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            onSubmitted: (_) => _iniciar(),
            decoration: _fuelDecoration('Nafta al salir (opcional)'),
          ),
          const SizedBox(height: 8),
          Text(
            'Si lo dejás vacío se usa lo último que reportó el dongle.',
            style: TextStyle(fontSize: 11, color: t.muted),
          ),
          const SizedBox(height: 20),
          SizedBox(
            width: double.infinity,
            height: 54,
            child: FilledButton.icon(
              onPressed: _enviando || car == null ? null : _iniciar,
              icon: _enviando
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.play_arrow_rounded),
              label: const Text('Iniciar viaje'),
            ),
          ),
        ],
      ),
    );
  }
}

/// ---------------------------------------------------------------------------
/// Finalizar viaje — `POST /trips/{id}/finish` · `DELETE /trips/{id}`
/// ---------------------------------------------------------------------------

class FinishTripSheet extends StatefulWidget {
  final HomeController controller;
  final int? currentFuel;

  const FinishTripSheet({
    super.key,
    required this.controller,
    this.currentFuel,
  });

  static Future<Trip?> show(
    BuildContext context,
    HomeController controller, {
    int? currentFuel,
  }) => _sheet<Trip>(
    context,
    FinishTripSheet(controller: controller, currentFuel: currentFuel),
  );

  @override
  State<FinishTripSheet> createState() => _FinishTripSheetState();
}

class _FinishTripSheetState extends State<FinishTripSheet> {
  late final _fuel = TextEditingController(
    text: widget.currentFuel?.toString() ?? '',
  );
  final _km = TextEditingController();
  bool _enviando = false;

  @override
  void dispose() {
    _fuel.dispose();
    _km.dispose();
    super.dispose();
  }

  Future<void> _finalizar() async {
    if (_enviando) return;
    final navigator = Navigator.of(context);
    setState(() => _enviando = true);
    try {
      final fuel = _fuel.text.trim();
      final km = _km.text.trim();
      final trip = await widget.controller.finishTrip(
        finalFuel: fuel.isEmpty ? null : int.tryParse(fuel),
        distance: km.isEmpty ? null : int.tryParse(km),
      );
      if (mounted) navigator.pop(trip);
    } on ObdApiException catch (error) {
      if (!mounted) return;
      if (error is ApiException && (error.isConflict || error.isNotFound)) {
        widget.controller.refreshActiveTrip();
      }
      ApiMessages.show(
        context,
        error,
        conflict: 'Ese viaje ya estaba terminado.',
        notFound: 'Solo quien maneja puede terminar el viaje.',
      );
    } finally {
      if (mounted) setState(() => _enviando = false);
    }
  }

  Future<void> _cancelar() async {
    if (_enviando) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Descartar viaje'),
        content: const Text(
          'Se borra como si nunca hubiera empezado. '
          'Usalo solo si lo iniciaste por error.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Volver'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Descartar'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    final navigator = Navigator.of(context);
    setState(() => _enviando = true);
    try {
      await widget.controller.cancelTrip();
      if (mounted) navigator.pop();
    } on ObdApiException catch (error) {
      if (!mounted) return;
      ApiMessages.show(
        context,
        error,
        conflict: 'Ese viaje ya terminó; no se puede descartar.',
        notFound: 'Solo quien lo inició puede descartarlo.',
      );
    } finally {
      if (mounted) setState(() => _enviando = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final trip = widget.controller.activeTrip;
    final elapsed = trip?.elapsed;

    return Padding(
      padding: _sheetPadding(context),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Finalizar viaje',
            style: TextStyle(
              fontSize: 24,
              fontWeight: FontWeight.w800,
              letterSpacing: -.5,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            [
              if (trip?.startedAt != null)
                'Saliste a las ${TripFormat.clock(trip!.startedAt!)}',
              if (elapsed != null) TripFormat.duration(elapsed),
              if (trip?.initialFuel != null)
                'nafta inicial ${trip!.initialFuel} %',
            ].join(' · '),
            style: TextStyle(color: t.muted),
          ),
          const SizedBox(height: 22),
          TextField(
            controller: _fuel,
            enabled: !_enviando,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            decoration: _fuelDecoration('Nafta al llegar (opcional)'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _km,
            enabled: !_enviando,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            onSubmitted: (_) => _finalizar(),
            decoration: const InputDecoration(
              labelText: 'Distancia recorrida (opcional)',
              hintText: '12',
              prefixIcon: Icon(AppIcons.trip),
              suffixText: 'km',
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Con la nafta inicial y final se calcula cuánto gastaste.',
            style: TextStyle(fontSize: 11, color: t.muted),
          ),
          const SizedBox(height: 20),
          SizedBox(
            width: double.infinity,
            height: 54,
            child: FilledButton.icon(
              onPressed: _enviando ? null : _finalizar,
              icon: _enviando
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.stop_rounded),
              label: const Text('Terminar viaje'),
            ),
          ),
          const SizedBox(height: 4),
          Center(
            child: TextButton(
              onPressed: _enviando ? null : _cancelar,
              style: TextButton.styleFrom(foregroundColor: t.danger),
              child: const Text('Lo inicié por error, descartarlo'),
            ),
          ),
        ],
      ),
    );
  }
}
