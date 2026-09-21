import 'package:flutter/material.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/api_messages.dart';
import 'package:obd_app/core/utils/trip_format.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/cars/create_car_screen.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// Mis autos: los propios y los que me compartieron (`GET /cars`).
///
/// Desde acá se elige cuál mostrar en el dashboard, se agrega uno nuevo y
/// se administra cada uno: compartirlo con un grupo, dejar de compartirlo y
/// vincular el dongle OBD.
class MyCarsScreen extends StatelessWidget {
  final HomeController controller;

  const MyCarsScreen({super.key, required this.controller});

  static Future<void> show(BuildContext context, HomeController controller) {
    return Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => MyCarsScreen(controller: controller)),
    );
  }

  Future<void> _agregar(BuildContext context) async {
    final created = await CreateCarScreen.show(context, controller);
    if (created != null && context.mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('${created.name} agregado')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Mis autos'),
        actions: [
          IconButton(
            tooltip: 'Agregar auto',
            onPressed: () => _agregar(context),
            icon: const Icon(AppIcons.add),
          ),
        ],
      ),
      body: ListenableBuilder(
        listenable: controller,
        builder: (context, _) {
          final cars = controller.cars;

          if (cars.isEmpty) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(AppIcons.car, size: 56, color: t.muted),
                    const SizedBox(height: 14),
                    Text(
                      'Todavía no tenés autos.',
                      style: TextStyle(
                        fontWeight: FontWeight.w700,
                        color: t.text,
                      ),
                    ),
                    const SizedBox(height: 18),
                    FilledButton.icon(
                      onPressed: () => _agregar(context),
                      icon: const Icon(AppIcons.add),
                      label: const Text('Agregar mi auto'),
                    ),
                  ],
                ),
              ),
            );
          }

          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
            children: [
              for (final car in cars) ...[
                _CarTile(
                  car: car,
                  selected: car.id == controller.car?.id,
                  onTap: () => CarActionsSheet.show(context, controller, car),
                ),
                const SizedBox(height: 8),
              ],
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: () => _agregar(context),
                icon: const Icon(AppIcons.add),
                label: const Text('Agregar otro auto'),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _CarTile extends StatelessWidget {
  final Car car;
  final bool selected;
  final VoidCallback onTap;

  const _CarTile({
    required this.car,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final meta = <String>[
      car.model.label,
      if (car.licensePlate != null) car.licensePlate!,
      if (car.isShared) 'compartido con ${car.group!.name}',
    ];

    return Material(
      color: t.surface,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(
              color: selected ? t.accent : t.border,
              width: selected ? 1.5 : 1,
            ),
          ),
          child: Row(
            children: [
              IconBadge(
                icon: AppIcons.car,
                color: selected ? t.accent : t.muted,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: TextStack(title: car.name, subtitle: meta.join(' · ')),
              ),
              if (selected)
                Padding(
                  padding: const EdgeInsets.only(right: 4),
                  child: Icon(AppIcons.check, size: 18, color: t.accent),
                ),
              Icon(AppIcons.arrow, color: t.muted),
            ],
          ),
        ),
      ),
    );
  }
}

/// Acciones sobre un auto: usarlo, compartirlo, vincular el dongle.
class CarActionsSheet extends StatefulWidget {
  final HomeController controller;
  final Car car;

  const CarActionsSheet({
    super.key,
    required this.controller,
    required this.car,
  });

  static Future<void> show(
    BuildContext context,
    HomeController controller,
    Car car,
  ) {
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: context.tokens.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (_) => CarActionsSheet(controller: controller, car: car),
    );
  }

  @override
  State<CarActionsSheet> createState() => _CarActionsSheetState();
}

class _CarActionsSheetState extends State<CarActionsSheet> {
  Device? _device;
  bool _deviceLoading = true;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _cargarDongle();
  }

  /// El auto tal como está ahora en el controller (cambia al compartirlo).
  Car get _car {
    for (final c in widget.controller.cars) {
      if (c.id == widget.car.id) return c;
    }
    return widget.car;
  }

  Future<void> _cargarDongle() async {
    try {
      final d = await widget.controller.deviceFor(widget.car.id);
      if (mounted) setState(() => _device = d);
    } on ObdApiException {
      // Sin dongle que mostrar; la acción de vincular sigue disponible.
    } finally {
      if (mounted) setState(() => _deviceLoading = false);
    }
  }

  Future<void> _run(Future<void> Function() action, {String? done}) async {
    if (_busy) return;
    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await action();
      if (done != null) {
        messenger
          ..hideCurrentSnackBar()
          ..showSnackBar(SnackBar(content: Text(done)));
      }
    } on ObdApiException catch (error) {
      if (!mounted) return;
      ApiMessages.show(
        context,
        error,
        notFound: 'Solo el dueño del auto puede hacer eso.',
        conflict:
            'Ese dongle ya está vinculado a otro auto. Desvinculalo ahí primero.',
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _compartir() async {
    final groups = widget.controller.groups;
    if (groups.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Primero creá un grupo desde la pestaña Compartido.'),
        ),
      );
      return;
    }

    Group? target = groups.length == 1 ? groups.first : null;
    target ??= await showDialog<Group>(
      context: context,
      builder: (dialogContext) => SimpleDialog(
        title: const Text('¿Con qué grupo?'),
        children: [
          for (final g in groups)
            SimpleDialogOption(
              onPressed: () => Navigator.pop(dialogContext, g),
              child: Text('${g.name} · ${g.memberCount} miembros'),
            ),
        ],
      ),
    );
    if (target == null || !mounted) return;

    final chosen = target;
    await _run(
      () => widget.controller.shareCar(carId: _car.id, groupId: chosen.id),
      done: 'Compartido con ${chosen.name}',
    );
  }

  Future<void> _dejarDeCompartir() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Dejar de compartir'),
        content: Text(
          'Los miembros de ${_car.group?.name ?? 'el grupo'} dejarán de ver '
          '${_car.name}. Un viaje en curso no se corta.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Dejar de compartir'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await _run(
      () => widget.controller.unshareCar(_car.id),
      done: 'Ya no está compartido',
    );
  }

  Future<void> _vincularDongle() async {
    final serial = await showDialog<String>(
      context: context,
      builder: (_) => _SerialDialog(current: _device?.serial),
    );
    if (serial == null || serial.isEmpty || !mounted) return;

    await _run(() async {
      final d = await widget.controller.pairDevice(
        carId: _car.id,
        serial: serial,
      );
      if (mounted) setState(() => _device = d);
    }, done: 'Dongle vinculado');
  }

  Future<void> _desvincularDongle() async {
    await _run(() async {
      await widget.controller.unpairDevice(_car.id);
      if (mounted) setState(() => _device = null);
    }, done: 'Dongle desvinculado');
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final car = _car;
    final isCurrent = widget.controller.car?.id == car.id;
    final device = _device;

    return ListenableBuilder(
      listenable: widget.controller,
      builder: (context, _) {
        return Padding(
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
              Text(
                car.name,
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -.5,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                [
                  car.model.label,
                  if (car.licensePlate != null) car.licensePlate!,
                  if (car.mileage != null) TripFormat.km(car.mileage!),
                ].join(' · '),
                style: TextStyle(color: t.muted),
              ),
              const SizedBox(height: 22),

              if (!isCurrent) ...[
                ActionTile(
                  icon: AppIcons.check,
                  title: 'Usar este auto',
                  subtitle: 'Mostrarlo en el inicio y la actividad.',
                  onTap: _busy
                      ? () {}
                      : () {
                          widget.controller.selectCar(car.id);
                          Navigator.pop(context);
                        },
                ),
                const SizedBox(height: 10),
              ],

              SectionCard(
                padding: EdgeInsets.zero,
                child: Column(
                  children: [
                    if (car.isShared)
                      SettingTile(
                        icon: AppIcons.unshare,
                        title: 'Dejar de compartir',
                        subtitle: 'Compartido con ${car.group!.name}',
                        onTap: _busy ? null : _dejarDeCompartir,
                      )
                    else
                      SettingTile(
                        icon: AppIcons.share,
                        title: 'Compartir con un grupo',
                        subtitle: 'Toda la familia lo va a poder usar.',
                        onTap: _busy ? null : _compartir,
                      ),
                    const Divider(height: 1),
                    SettingTile(
                      icon: AppIcons.dongle,
                      title: device == null
                          ? 'Vincular dongle OBD'
                          : 'Dongle vinculado',
                      subtitle: _deviceLoading
                          ? 'Consultando…'
                          : device == null
                          ? 'Ningún dispositivo asociado todavía.'
                          : '${device.serial}'
                                '${device.lastSeenAt != null ? ' · visto ${TripFormat.ago(device.lastSeenAt!)}' : ' · nunca reportó'}',
                      onTap: _busy || _deviceLoading ? null : _vincularDongle,
                    ),
                    if (device != null) ...[
                      const Divider(height: 1),
                      SettingTile(
                        icon: AppIcons.unshare,
                        title: 'Desvincular dongle',
                        subtitle: 'El serial queda libre para otro auto.',
                        onTap: _busy ? null : _desvincularDongle,
                      ),
                    ],
                  ],
                ),
              ),
              if (_busy) ...[
                const SizedBox(height: 14),
                const Center(
                  child: SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              ],
            ],
          ),
        );
      },
    );
  }
}

/// Pide el serial del dongle. Hasta que el firmware lo exponga por BLE, es lo
/// que la persona lee en la etiqueta del dispositivo.
class _SerialDialog extends StatefulWidget {
  final String? current;

  const _SerialDialog({this.current});

  @override
  State<_SerialDialog> createState() => _SerialDialogState();
}

class _SerialDialogState extends State<_SerialDialog> {
  late final _controller = TextEditingController(text: widget.current ?? '');
  final _formKey = GlobalKey<FormState>();

  static final _valid = RegExp(r'^[A-Za-z0-9:_-]{1,64}$');

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _ok() {
    if (!_formKey.currentState!.validate()) return;
    Navigator.pop(context, _controller.text.trim());
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Serial del dongle'),
      content: Form(
        key: _formKey,
        child: TextFormField(
          controller: _controller,
          autofocus: true,
          textCapitalization: TextCapitalization.characters,
          onFieldSubmitted: (_) => _ok(),
          decoration: const InputDecoration(
            labelText: 'Serial',
            hintText: 'A4:CF:12:8B:3C:7E',
            prefixIcon: Icon(AppIcons.dongle),
          ),
          validator: (value) {
            final v = value?.trim() ?? '';
            if (v.isEmpty) return 'Ingresá el serial';
            if (!_valid.hasMatch(v)) {
              return 'Solo letras, números, ":", "_" y "-"';
            }
            return null;
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancelar'),
        ),
        FilledButton(onPressed: _ok, child: const Text('Vincular')),
      ],
    );
  }
}
