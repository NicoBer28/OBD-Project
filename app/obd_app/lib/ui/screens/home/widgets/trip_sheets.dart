import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/api_messages.dart';
import 'package:obd_app/core/utils/trip_format.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/groups/scan_qr_screen.dart';
import 'package:obd_app/ui/screens/home/widgets/add_person_section.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

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

/// Alguien elegido para sumar al viaje **antes** de que exista: todavía no
/// hay `tripId` al que pegarle un `POST /trips/{id}/participants*`, así que
/// se junta acá y recién se manda, uno por uno, cuando `POST /trips` vuelve
/// con el viaje creado (ver `_StartTripSheetState._addPendingTo`).
sealed class _Pending {
  const _Pending();

  String get name;
  String get subtitle;
}

class _PendingMember extends _Pending {
  final GroupMember member;
  const _PendingMember(this.member);

  @override
  String get name => member.name;
  @override
  String get subtitle => 'Del grupo';
}

class _PendingGuest extends _Pending {
  @override
  final String name;
  const _PendingGuest(this.name);

  @override
  String get subtitle => 'Invitado sin cuenta';
}

class _PendingInvite extends _Pending {
  final String email;
  @override
  final String name;
  const _PendingInvite(this.email, this.name);

  @override
  String get subtitle => 'Por correo o QR';
}

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

  // Acompañantes elegidos antes de que el viaje exista — ver _Pending.
  final List<_Pending> _pending = [];
  AddPersonMode _addMode = AddPersonMode.none;
  final _guestName = TextEditingController();
  final _inviteEmail = TextEditingController();
  final _inviteName = TextEditingController();

  @override
  void dispose() {
    _fuel.dispose();
    _guestName.dispose();
    _inviteEmail.dispose();
    _inviteName.dispose();
    super.dispose();
  }

  /// Miembros del grupo que todavía no están en [_pending] — ni yo, que voy
  /// a manejar y no soy mi propio acompañante.
  List<GroupMember> get _selectableMembers {
    final me = widget.controller.me?.id;
    final staged = _pending.whereType<_PendingMember>().map((p) => p.member.userId).toSet();
    return widget.controller.members.where((m) => m.userId != me && !staged.contains(m.userId)).toList(growable: false);
  }

  void _addFromGroup(GroupMember member) {
    setState(() {
      _pending.add(_PendingMember(member));
      _addMode = AddPersonMode.none;
    });
  }

  void _addGuest() {
    final typed = _guestName.text.trim();
    final name = typed.isEmpty ? 'Acompañante ${_pending.length + 1}' : typed;
    setState(() {
      _pending.add(_PendingGuest(name));
      _guestName.clear();
      _addMode = AddPersonMode.none;
    });
  }

  Future<void> _scanForInvite() async {
    final result = await ScanQrScreen.show(context);
    if (result == null || !mounted) return;
    setState(() {
      _inviteEmail.text = result.email;
      if (result.name != null) _inviteName.text = result.name!;
    });
  }

  void _warn(String text) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(text), backgroundColor: Colors.orange));
  }

  void _invite() {
    final email = _inviteEmail.text.trim();
    final name = _inviteName.text.trim();
    if (!email.contains('@')) {
      _warn('Ingresá un correo válido');
      return;
    }
    if (name.isEmpty) {
      _warn('Ingresá un nombre');
      return;
    }
    setState(() {
      _pending.add(_PendingInvite(email, name));
      _inviteEmail.clear();
      _inviteName.clear();
      _addMode = AddPersonMode.none;
    });
  }

  void _removePending(_Pending p) => setState(() => _pending.remove(p));

  Future<void> _iniciar() async {
    if (_enviando) return;
    final navigator = Navigator.of(context);
    final controller = widget.controller;
    setState(() => _enviando = true);
    try {
      final text = _fuel.text.trim();
      final trip = await controller.startTrip(
        initialFuel: text.isEmpty ? null : int.tryParse(text),
      );

      // El viaje ya arrancó — de acá para adelante son altas de acompañante,
      // no algo que pueda tirar abajo el viaje si una falla.
      final failed = await _addPendingTo(controller, trip.id);
      if (mounted && failed > 0) {
        _warn(
          failed == 1
              ? 'El viaje arrancó, pero no se pudo sumar a un acompañante. Reintentá desde el viaje.'
              : 'El viaje arrancó, pero no se pudo sumar a $failed acompañantes. Reintentá desde el viaje.',
        );
      }
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

  /// Manda cada acompañante en espera una vez que hay `tripId`. Una falla
  /// individual no revierte nada — el viaje ya está en curso — solo se
  /// cuenta para avisar después.
  Future<int> _addPendingTo(HomeController controller, String tripId) async {
    var failed = 0;
    for (final p in _pending) {
      try {
        switch (p) {
          case _PendingMember m:
            await controller.addParticipantFromGroup(tripId, m.member.userId);
          case _PendingGuest g:
            await controller.addGuestParticipant(tripId, g.name);
          case _PendingInvite i:
            await controller.inviteParticipant(tripId, email: i.email, name: i.name);
        }
      } on ObdApiException {
        failed++;
      }
    }
    return failed;
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final car = widget.controller.car;

    return Padding(
      padding: _sheetPadding(context),
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * .85),
        child: SingleChildScrollView(
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
              const SizedBox(height: 22),
              SectionLabel('Acompañantes${_pending.isEmpty ? ' (opcional)' : ' (${_pending.length})'}'),
              const SizedBox(height: 10),
              if (_pending.isNotEmpty) ...[
                SectionCard(
                  child: Column(
                    children: [
                      for (var i = 0; i < _pending.length; i++) ...[
                        if (i > 0) const Divider(height: 18),
                        Row(
                          children: [
                            Expanded(
                              child: TextStack(title: _pending[i].name, subtitle: _pending[i].subtitle),
                            ),
                            IconButton(
                              tooltip: 'Quitar',
                              icon: Icon(AppIcons.removePerson, size: 19, color: t.muted),
                              onPressed: _enviando ? null : () => _removePending(_pending[i]),
                            ),
                          ],
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(height: 12),
              ],
              AddPersonSection(
                mode: _addMode,
                onModeChanged: (m) => setState(() => _addMode = m == _addMode ? AddPersonMode.none : m),
                busy: _enviando,
                selectableMembers: _selectableMembers,
                onAddFromGroup: _addFromGroup,
                guestNameController: _guestName,
                onAddGuest: _addGuest,
                inviteEmailController: _inviteEmail,
                inviteNameController: _inviteName,
                onScan: _scanForInvite,
                onInvite: _invite,
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
        ),
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
              if (trip?.startedAt != null) 'Saliste a las ${TripFormat.clock(trip!.startedAt!)}',
              if (elapsed != null) TripFormat.duration(elapsed),
              if (trip?.initialFuel != null) 'nafta inicial ${trip!.initialFuel} %',
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
