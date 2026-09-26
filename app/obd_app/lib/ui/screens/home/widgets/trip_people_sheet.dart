import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/api_messages.dart';
import 'package:obd_app/core/utils/trip_format.dart';
import 'package:obd_app/core/utils/trip_share_text.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/groups/scan_qr_screen.dart';
import 'package:obd_app/ui/screens/home/widgets/add_person_section.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Acompañantes y reparto del gasto de un viaje
///
/// `GET/POST/DELETE /trips/{id}/participants*` · `PUT /trips/{id}/cost` ·
/// `GET /trips/{id}/split` — **todavía no existen en la API**; esta pantalla
/// ya está armada contra esa forma (ver `TripsApi`), lista para conectarse en
/// cuanto el backend las tenga. Mientras tanto cada llamada falla con un 404
/// normal de "no existe el endpoint", que esta misma pantalla ya sabe mostrar
/// como un error de carga con botón de reintentar — no hace falta ningún modo
/// especial de "demo".
///
/// Tres formas de sumar a alguien, una por botón:
///  · **Del grupo** — elegís a alguien que ya comparte el auto. No le da
///    acceso a nada nuevo, solo lo suma al reparto de este viaje.
///  · **Por correo o QR** — para alguien que no es del grupo (un amigo, no
///    familia): se busca por correo, o escaneando su "Mi código QR". Si tiene
///    cuenta se lo suma con su nombre real; si no, con el nombre que se le
///    haya puesto, como invitado.
///  · **Invitado sin cuenta** — ni correo ni grupo, solo un nombre (o dejarlo
///    en blanco para simplemente sumar una cabeza más al reparto).
///
/// Con el costo cargado, el reparto se ve al toque; para quien no tiene la
/// app, "Cobrar" arma un QR con el monto en texto plano — lo puede leer la
/// cámara de cualquier teléfono, no hace falta tener OBD-C instalada.
/// ---------------------------------------------------------------------------

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

class TripPeopleSheet extends StatefulWidget {
  final HomeController controller;
  final Trip trip;

  const TripPeopleSheet({super.key, required this.controller, required this.trip});

  static Future<void> show(BuildContext context, HomeController controller, Trip trip) =>
      _sheet<void>(context, TripPeopleSheet(controller: controller, trip: trip));

  @override
  State<TripPeopleSheet> createState() => _TripPeopleSheetState();
}

class _TripPeopleSheetState extends State<TripPeopleSheet> {
  List<TripParticipant>? _participants;
  TripSplit? _split;
  Object? _loadError;
  bool _busy = false;

  late final TextEditingController _cost = TextEditingController(
    text: widget.trip.cost?.toString() ?? '',
  );
  bool _includeDriver = true;

  AddPersonMode _addMode = AddPersonMode.none;
  final _guestName = TextEditingController();
  final _inviteEmail = TextEditingController();
  final _inviteName = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _cost.dispose();
    _guestName.dispose();
    _inviteEmail.dispose();
    _inviteName.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() => _loadError = null);
    try {
      final participants = await widget.controller.tripParticipants(widget.trip.id);
      if (!mounted) return;
      setState(() => _participants = participants);
      unawaited(_loadSplit());
    } on ObdApiException catch (e) {
      if (mounted) setState(() => _loadError = e);
    }
  }

  Future<void> _loadSplit() async {
    // No `widget.trip.cost == null` shortcut here on purpose: `widget.trip`
    // is a snapshot from when the sheet opened and never changes, but the
    // cost can change during this session via `_saveCost`. Always asking the
    // server keeps this correct either way; the "no cost yet" case is just a
    // 409 the catch below already turns into "no split to show".
    try {
      final split = await widget.controller.tripSplit(widget.trip.id, includeDriver: _includeDriver);
      if (mounted) setState(() => _split = split);
    } on ObdApiException {
      if (mounted) setState(() => _split = null);
    }
  }

  void _warn(String text) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(text), backgroundColor: Colors.orange));
  }

  Future<void> _saveCost() async {
    if (_busy) return;
    final text = _cost.text.trim();
    final parsed = text.isEmpty ? null : int.tryParse(text);
    if (text.isNotEmpty && parsed == null) {
      _warn('Ingresá solo números');
      return;
    }
    setState(() => _busy = true);
    try {
      await widget.controller.setTripCost(widget.trip.id, parsed);
      FocusScope.of(context).unfocus();
      await _loadSplit();
    } on ObdApiException catch (e) {
      if (mounted) {
        ApiMessages.show(context, e, notFound: 'Solo quien manejó puede cambiar el costo del viaje.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _onAdded(TripParticipant added) {
    setState(() {
      _participants = [...?_participants, added];
      _addMode = AddPersonMode.none;
    });
    unawaited(_loadSplit());
  }

  Future<void> _addFromGroup(GroupMember member) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final added = await widget.controller.addParticipantFromGroup(widget.trip.id, member.userId);
      _onAdded(added);
    } on ObdApiException catch (e) {
      if (mounted) {
        ApiMessages.show(
          context,
          e,
          conflict: 'Ya está sumado a este viaje.',
          notFound: 'Solo quien manejó puede agregar acompañantes.',
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _scanForInvite() async {
    final result = await ScanQrScreen.show(context);
    if (result == null || !mounted) return;
    setState(() {
      _inviteEmail.text = result.email;
      if (result.name != null) _inviteName.text = result.name!;
    });
  }

  Future<void> _invite() async {
    if (_busy) return;
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
    setState(() => _busy = true);
    try {
      final added = await widget.controller.inviteParticipant(widget.trip.id, email: email, name: name);
      _inviteEmail.clear();
      _inviteName.clear();
      _onAdded(added);
    } on ObdApiException catch (e) {
      if (mounted) {
        ApiMessages.show(
          context,
          e,
          conflict: 'Esa persona ya está sumada a este viaje.',
          notFound: 'Solo quien manejó puede agregar acompañantes.',
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _addGuest() async {
    if (_busy) return;
    final typed = _guestName.text.trim();
    final name = typed.isEmpty ? 'Acompañante ${(_participants?.length ?? 0) + 1}' : typed;
    setState(() => _busy = true);
    try {
      final added = await widget.controller.addGuestParticipant(widget.trip.id, name);
      _guestName.clear();
      _onAdded(added);
    } on ObdApiException catch (e) {
      if (mounted) {
        ApiMessages.show(context, e, notFound: 'Solo quien manejó puede agregar acompañantes.');
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _remove(TripParticipant participant) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await widget.controller.removeParticipant(widget.trip.id, participant.id);
      if (mounted) {
        setState(() => _participants = _participants?.where((p) => p.id != participant.id).toList());
      }
      await _loadSplit();
    } on ObdApiException catch (e) {
      if (mounted) ApiMessages.show(context, e, notFound: 'Ya no estaba en el viaje.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _showCharge(TripShare share) async {
    final c = widget.controller;
    await _sheet<void>(
      context,
      _ChargeSheet(
        driverName: c.driverName(widget.trip.driverId),
        tripLabel: widget.trip.startedAt == null
            ? 'un viaje'
            : 'el viaje del ${TripFormat.dayMonth(widget.trip.startedAt!)}',
        amount: share.amount,
        initialPayTo: c.me?.userEmail,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final c = widget.controller;
    final trip = widget.trip;
    final isDriver = c.isMe(trip.driverId);
    final participants = _participants;

    return Padding(
      padding: _sheetPadding(context),
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * .82),
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'Acompañantes y gasto',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, letterSpacing: -.4),
              ),
              const SizedBox(height: 4),
              Text(
                trip.startedAt == null
                    ? 'Este viaje'
                    : 'Viaje del ${TripFormat.dayLabel(trip.startedAt!)} · conduce ${c.driverName(trip.driverId)}',
                style: TextStyle(fontSize: 12, color: t.muted),
              ),
              const SizedBox(height: 18),

              if (participants == null && _loadError == null)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 28),
                  child: Center(child: CircularProgressIndicator()),
                ),

              if (_loadError != null) _LoadErrorCard(error: _loadError!, onRetry: _load),

              if (participants != null) ...[
                _ParticipantsSection(
                  driverName: c.driverName(trip.driverId),
                  participants: participants,
                  canEdit: isDriver,
                  busy: _busy,
                  onRemove: _remove,
                ),

                if (isDriver) ...[
                  const SizedBox(height: 14),
                  AddPersonSection(
                    mode: _addMode,
                    onModeChanged: (m) => setState(() => _addMode = m == _addMode ? AddPersonMode.none : m),
                    busy: _busy,
                    selectableMembers: c.selectableMembers(driverId: trip.driverId, participants: participants),
                    onAddFromGroup: _addFromGroup,
                    guestNameController: _guestName,
                    onAddGuest: _addGuest,
                    inviteEmailController: _inviteEmail,
                    inviteNameController: _inviteName,
                    onScan: _scanForInvite,
                    onInvite: _invite,
                  ),
                ],

                const SizedBox(height: 20),
                _CostSection(
                  controller: _cost,
                  enabled: isDriver && !_busy,
                  onSave: _saveCost,
                ),

                if (_split != null) ...[
                  const SizedBox(height: 18),
                  _SplitSection(
                    split: _split!,
                    driverName: c.driverName(trip.driverId),
                    includeDriver: _includeDriver,
                    onIncludeDriverChanged: (value) {
                      setState(() => _includeDriver = value);
                      _loadSplit();
                    },
                    onCharge: _showCharge,
                  ),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Lista de acompañantes
// ---------------------------------------------------------------------------

class _ParticipantsSection extends StatelessWidget {
  final String driverName;
  final List<TripParticipant> participants;
  final bool canEdit;
  final bool busy;
  final ValueChanged<TripParticipant> onRemove;

  const _ParticipantsSection({
    required this.driverName,
    required this.participants,
    required this.canEdit,
    required this.busy,
    required this.onRemove,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionLabel('Acompañantes'),
          const SizedBox(height: 10),
          _PersonRow(name: driverName, subtitle: 'Conductor', icon: AppIcons.car),
          if (participants.isEmpty) ...[
            const Divider(height: 22),
            Text(
              canEdit
                  ? 'Todavía nadie más. Sumá gente del grupo, por correo/QR, o un invitado sin cuenta.'
                  : 'Todavía nadie más en este viaje.',
              style: TextStyle(fontSize: 12, color: t.muted),
            ),
          ] else
            for (final p in participants) ...[
              const Divider(height: 22),
              _PersonRow(
                name: p.name,
                subtitle: p.isGuest ? 'Invitado sin cuenta' : 'Con cuenta',
                icon: p.isGuest ? AppIcons.guest : AppIcons.shared,
                trailing: canEdit
                    ? IconButton(
                        tooltip: 'Quitar',
                        icon: Icon(AppIcons.removePerson, size: 19, color: t.muted),
                        onPressed: busy ? null : () => onRemove(p),
                      )
                    : null,
              ),
            ],
        ],
      ),
    );
  }
}

class _PersonRow extends StatelessWidget {
  final String name;
  final String subtitle;
  final IconData icon;
  final Widget? trailing;

  const _PersonRow({required this.name, required this.subtitle, required this.icon, this.trailing});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Row(
      children: [
        IconBadge(icon: icon, color: t.accent),
        const SizedBox(width: 11),
        Expanded(
          child: TextStack(title: name, subtitle: subtitle),
        ),
        if (trailing != null) trailing!,
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Costo del viaje
// ---------------------------------------------------------------------------

class _CostSection extends StatelessWidget {
  final TextEditingController controller;
  final bool enabled;
  final VoidCallback onSave;

  const _CostSection({required this.controller, required this.enabled, required this.onSave});

  @override
  Widget build(BuildContext context) {
    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionLabel('Costo del viaje'),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: controller,
                  enabled: enabled,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    prefixText: '\$ ',
                    hintText: 'Nafta, peajes...',
                    isDense: true,
                  ),
                  onSubmitted: enabled ? (_) => onSave() : null,
                ),
              ),
              const SizedBox(width: 10),
              FilledButton.tonal(onPressed: enabled ? onSave : null, child: const Text('Guardar')),
            ],
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Reparto
// ---------------------------------------------------------------------------

class _SplitSection extends StatelessWidget {
  final TripSplit split;
  final String driverName;
  final bool includeDriver;
  final ValueChanged<bool> onIncludeDriverChanged;
  final ValueChanged<TripShare> onCharge;

  const _SplitSection({
    required this.split,
    required this.driverName,
    required this.includeDriver,
    required this.onIncludeDriverChanged,
    required this.onCharge,
  });

  @override
  Widget build(BuildContext context) {
    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionHeader(title: 'Reparto', trailingText: '${split.shareCount} partes'),
          const SizedBox(height: 6),
          SwitchTile(
            title: 'Incluir la parte de quien manejó',
            subtitle: driverName,
            value: includeDriver,
            onChanged: onIncludeDriverChanged,
          ),
          const Divider(height: 22),
          for (var i = 0; i < split.shares.length; i++) ...[
            if (i > 0) const Divider(height: 18),
            _ShareRow(share: split.shares[i], driverName: driverName, onCharge: onCharge),
          ],
        ],
      ),
    );
  }
}

class _ShareRow extends StatelessWidget {
  final TripShare share;
  final String driverName;
  final ValueChanged<TripShare> onCharge;

  const _ShareRow({required this.share, required this.driverName, required this.onCharge});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final name = share.isDriver ? driverName : (share.name ?? 'Acompañante');

    return Row(
      children: [
        Expanded(
          child: TextStack(
            title: name,
            subtitle: share.isDriver ? 'Conductor' : (share.isGuest ? 'Sin cuenta' : 'Con cuenta'),
          ),
        ),
        Text(
          TripFormat.money(share.amount),
          style: TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: t.text),
        ),
        if (!share.isDriver) ...[
          const SizedBox(width: 6),
          IconButton(
            tooltip: 'Cobrar',
            icon: Icon(AppIcons.qr, size: 19, color: t.accent),
            onPressed: () => onCharge(share),
          ),
        ],
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// "Cobrar": QR de texto plano con el monto, para quien no tiene la app
// ---------------------------------------------------------------------------

class _ChargeSheet extends StatefulWidget {
  final String driverName;
  final String tripLabel;
  final int amount;
  final String? initialPayTo;

  const _ChargeSheet({
    required this.driverName,
    required this.tripLabel,
    required this.amount,
    this.initialPayTo,
  });

  @override
  State<_ChargeSheet> createState() => _ChargeSheetState();
}

class _ChargeSheetState extends State<_ChargeSheet> {
  late final TextEditingController _payTo = TextEditingController(text: widget.initialPayTo ?? '');

  @override
  void dispose() {
    _payTo.dispose();
    super.dispose();
  }

  String get _text => TripShareText.build(
    driverName: widget.driverName,
    tripLabel: widget.tripLabel,
    amount: widget.amount,
    payTo: _payTo.text,
  );

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _text));
    if (mounted) {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(const SnackBar(content: Text('Copiado. Ya lo podés pegar donde quieras.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Padding(
      padding: _sheetPadding(context),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Cobrar', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, letterSpacing: -.4)),
          const SizedBox(height: 4),
          Text(
            'Que lo escanee con la cámara de su teléfono, tenga o no la app.',
            style: TextStyle(fontSize: 12, color: t.muted),
          ),
          const SizedBox(height: 18),
          Center(
            child: Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(18)),
              child: QrImageView(data: _text, size: 200, backgroundColor: Colors.white),
            ),
          ),
          const SizedBox(height: 18),
          Text(
            TripFormat.money(widget.amount),
            style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800, color: t.text),
          ),
          const SizedBox(height: 14),
          TextField(
            controller: _payTo,
            onChanged: (_) => setState(() {}),
            textCapitalization: TextCapitalization.none,
            decoration: const InputDecoration(
              labelText: '¿Cómo te pueden pagar?',
              hintText: 'Alias, CBU, correo de MP...',
              isDense: true,
            ),
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: _copy,
              icon: const Icon(AppIcons.copy, size: 18),
              label: const Text('Copiar texto'),
            ),
          ),
        ],
      ),
    );
  }
}

class _LoadErrorCard extends StatelessWidget {
  final Object error;
  final VoidCallback onRetry;

  const _LoadErrorCard({required this.error, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(ApiMessages.of(error), style: TextStyle(fontSize: 13, color: t.text)),
          const SizedBox(height: 10),
          OutlinedButton(onPressed: onRetry, child: const Text('Reintentar')),
        ],
      ),
    );
  }
}
