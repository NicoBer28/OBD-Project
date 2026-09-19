import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import '../ui/app_theme.dart';
import 'login_screen.dart';

const _uartServiceUuid = '6E400001-B5A3-F393-E0A9-E50E24DCCA9E';
const _uartWriteUuid = '6E400002-B5A3-F393-E0A9-E50E24DCCA9E';
const _uartReadUuid = '6E400003-B5A3-F393-E0A9-E50E24DCCA9E';

class MainScreen extends StatefulWidget {
  final String nombreUsuario;
  final BluetoothDevice? device;
  const MainScreen({super.key, required this.nombreUsuario, this.device});
  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> {
  int _tab = 0;
  bool _summary = false, _maintenanceAlerts = true, _tripAlerts = true;
  String _period = '30 d';
  double _fuel = 75;
  int _speed = 0, _rpm = 0;
  BluetoothCharacteristic? _write, _read;
  StreamSubscription<List<int>>? _receiveSub;
  StreamSubscription<BluetoothConnectionState>? _connectionSub;
  String _connectionStatus = 'Modo demo: sin conexión BLE';
  String _receivedData = 'Sin datos recibidos';
  bool _sending = false;
  final _command = TextEditingController();

  @override
  void initState() {
    super.initState();
    if (widget.device != null) {
      _connectionSub = widget.device!.connectionState.listen((state) {
        if (mounted) {
          setState(
            () =>
                _connectionStatus = state == BluetoothConnectionState.connected
                ? 'Conectado'
                : 'Desconectado',
          );
        }
      });
      _prepareBle();
    }
  }

  Future<void> _prepareBle() async {
    final device = widget.device!;
    try {
      if (!device.isConnected) {
        await device.connect(license: License.nonprofit, autoConnect: false);
      }
      final service = (await device.discoverServices())
          .cast<BluetoothService?>()
          .firstWhere(
            (item) => item!.uuid == Guid(_uartServiceUuid),
            orElse: () => null,
          );
      if (service == null) {
        throw StateError('No se encontró el servicio UART de la ESP32');
      }
      for (final characteristic in service.characteristics) {
        if (characteristic.uuid == Guid(_uartWriteUuid)) {
          _write = characteristic;
        }
        if (characteristic.uuid == Guid(_uartReadUuid)) {
          _read = characteristic;
        }
      }
      if (_read != null &&
          (_read!.properties.notify || _read!.properties.indicate)) {
        await _read!.setNotifyValue(true);
        _receiveSub = _read!.onValueReceived.listen(_handlePacket);
      }
      if (mounted) {
        setState(
          () => _connectionStatus = _write == null
              ? 'Conectado, sin canal de escritura'
              : 'Conectado a ${device.advName.isEmpty ? device.remoteId : device.advName}',
        );
      }
    } catch (error) {
      if (mounted) {
        setState(() => _connectionStatus = 'Error preparando BLE: $error');
      }
    }
  }

  void _handlePacket(List<int> value) {
    if (!mounted || value.isEmpty) return;
    final bytes = Uint8List.fromList(value);
    final data = ByteData.sublistView(bytes);
    setState(() {
      if (data.getUint8(0) == 1 && bytes.length >= 4) {
        _speed = data.getUint8(1);
        _rpm = data.getUint16(2, Endian.little);
        _receivedData = 'Vel: $_speed km/h · RPM: $_rpm';
      } else if (data.getUint8(0) == 2 && bytes.length >= 3) {
        _fuel = data.getUint8(2).toDouble();
        _receivedData = 'Nivel de nafta: ${_fuel.toInt()}%';
      }
    });
  }

  Future<void> _send() async {
    if (_command.text.trim().isEmpty || _write == null || _sending) return;
    setState(() => _sending = true);
    try {
      await _write!.write(
        utf8.encode(_command.text.trim()),
        withoutResponse:
            _write!.properties.writeWithoutResponse &&
            !_write!.properties.write,
      );
      _command.clear();
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('No se pudo enviar: $error')));
      }
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  Future<void> _readOnce() async {
    if (_read == null || !_read!.properties.read) return;
    try {
      final value = await _read!.read();
      if (mounted) {
        setState(
          () => _receivedData = utf8.decode(value, allowMalformed: true),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('No se pudo leer: $error')));
      }
    }
  }

  @override
  void dispose() {
    _receiveSub?.cancel();
    _connectionSub?.cancel();
    _command.dispose();
    super.dispose();
  }

  void _showInviteDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Invitar al grupo'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Center(
              child: Icon(Icons.qr_code_2, size: 160, color: AppColors.text),
            ),
            const SizedBox(height: 16),
            const Text(
              'Escaneá este código para unirte al auto, o enviá una invitación directa:',
              style: TextStyle(fontSize: 13, color: AppColors.muted),
            ),
            const SizedBox(height: 20),
            TextField(
              decoration: InputDecoration(
                labelText: 'Correo electrónico',
                prefixIcon: const Icon(Icons.email_outlined),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.send, color: AppColors.accent),
                  onPressed: () {
                    Navigator.pop(context);
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Invitación enviada')),
                    );
                  },
                ),
              ),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: () {
                Navigator.pop(context);
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Enlace copiado al portapapeles'),
                  ),
                );
              },
              icon: const Icon(Icons.link),
              label: const Text('Copiar enlace'),
            ),
          ],
        ),
      ),
    );
  }

  void _showStartJourneySheet(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Theme.of(context).colorScheme.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (context) => Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.of(context).viewInsets.bottom,
          left: 24,
          right: 24,
          top: 24,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Configurar Viaje',
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 24),

            // Start Location
            TextField(
              decoration: InputDecoration(
                labelText: 'Punto de partida',
                hintText: 'Ubicación actual',
                prefixIcon: const Icon(
                  Icons.my_location,
                  color: AppColors.accent,
                ),
                filled: true,
                fillColor: Theme.of(context).colorScheme.surfaceContainerHighest
                    .withOpacity(0.3),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide.none,
                ),
              ),
            ),
            const SizedBox(height: 16),

            // End Location
            TextField(
              decoration: InputDecoration(
                labelText: 'Destino',
                hintText: 'Ej: Av. Corrientes 980',
                prefixIcon: const Icon(Icons.flag_outlined),
                filled: true,
                fillColor: Theme.of(context).colorScheme.surfaceContainerHighest
                    .withOpacity(0.3),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide.none,
                ),
              ),
            ),
            const SizedBox(height: 24),

            // QR Code Section for Non-Members
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: AppColors.accent.withOpacity(0.1),
                border: Border.all(color: AppColors.accent.withOpacity(0.2)),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Row(
                children: [
                  const Icon(
                    Icons.qr_code_2,
                    size: 42,
                    color: AppColors.accent,
                  ),
                  const SizedBox(width: 16),
                  const Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Sumar pasajeros',
                          style: TextStyle(
                            fontWeight: FontWeight.w700,
                            fontSize: 15,
                          ),
                        ),
                        Text(
                          'Mostrá el QR para dividir el costo con no-miembros',
                          style: TextStyle(
                            fontSize: 12,
                            color: AppColors.muted,
                          ),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    onPressed: () {
                      // TODO: Navigate to full-screen QR
                    },
                    icon: const Icon(
                      Icons.arrow_forward_ios,
                      size: 16,
                      color: AppColors.accent,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 32),

            // Start Action
            SizedBox(
              width: double.infinity,
              height: 56,
              child: ElevatedButton(
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppColors.accent,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                  elevation: 0,
                ),
                onPressed: () => Navigator.pop(context),
                child: const Text(
                  'Iniciar Recorrido',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800),
                ),
              ),
            ),
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }

  Widget _saldarCard(BuildContext context) {
    final t = context.tokens;
    return SectionCard(
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Tu saldo pendiente',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    color: t.muted,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Debés \$12.400',
                  style: TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w800,
                    color: t.accent2,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  '3 consumos sin liquidar · Último: Nafta Super',
                  style: TextStyle(fontSize: 11, color: t.muted),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          // Colors come from elevatedButtonTheme; only size/shape are overridden.
          // minimumSize is required: the theme's Size.fromHeight(52) has an
          // infinite minimum width, which breaks inside a Row.
          ElevatedButton(
            onPressed: _settleUp,
            style: ElevatedButton.styleFrom(
              minimumSize: const Size(0, 34),
              padding: const EdgeInsets.symmetric(horizontal: 14),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
              ),
              textStyle: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
            child: const Text('Saldar'),
          ),
        ],
      ),
    );
  }

  Widget _proximoTurnoCard() {
    final t = context.tokens;
    return Material(
      color: t.surface2,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: t.member1.withValues(alpha: 0.4)),
      ),
      child: InkWell(
        onTap: () => setState(() => _tab = 1), // Compartido tab (calendar)
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          child: Row(
            children: [
              Icon(Icons.calendar_today_rounded, size: 18, color: t.member1),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Próximo turno',
                      style: TextStyle(
                        fontSize: 10,
                        color: t.muted,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'HOY · 18:00 a 21:00 hs',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w800,
                        color: t.text,
                      ),
                    ),
                  ],
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: t.member1,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: const Text(
                  'Vos',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final pages = [_car(), _shared(), _activity(), _profile()];
    return Scaffold(
      body: SafeArea(
        child: AnimatedSwitcher(
          duration: const Duration(milliseconds: 180),
          child: pages[_tab],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (value) => setState(() => _tab = value),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.directions_car_outlined),
            selectedIcon: Icon(Icons.directions_car),
            label: 'Auto',
          ),
          NavigationDestination(
            icon: Icon(Icons.group_outlined),
            selectedIcon: Icon(Icons.group),
            label: 'Compartido',
          ),
          NavigationDestination(
            icon: Icon(Icons.bar_chart_outlined),
            selectedIcon: Icon(Icons.bar_chart),
            label: 'Actividad',
          ),
          NavigationDestination(
            icon: Icon(Icons.person_outline),
            selectedIcon: Icon(Icons.person),
            label: 'Perfil',
          ),
        ],
      ),
    );
  }

  Widget _page(Key key, List<Widget> children) => ListView(
    key: key,
    padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
    children: children,
  );
  Widget _car() {
    final connected = _connectionStatus.toLowerCase().startsWith('conectado');
    return _page(const ValueKey('car'), [
      PageHeader(
        title: 'Golf GTI',
        subtitle: 'Volkswagen · AB 123 CD',
        trailing: GestureDetector(
          onTap: () => setState(() => _tab = 3), // Redirects to the Profile tab
          child: CircleAvatar(
            backgroundColor: AppColors.accent,
            child: Text(
              _initials,
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
        ),
      ),
      const SizedBox(height: 16),
      SectionCard(
        padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Center(
              child: Icon(
                Icons.directions_car_outlined,
                color: AppColors.accent,
                size: 100,
              ),
            ),
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Eyebrow('Autonomía'),
                      const SizedBox(height: 4),
                      Text(
                        '${(_fuel * 5.5).round()} km',
                        style: const TextStyle(
                          fontSize: 29,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -1,
                        ),
                      ),
                    ],
                  ),
                ),
                Text(
                  '${_fuel.toInt()}%',
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 9),
            ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: LinearProgressIndicator(
                value: _fuel / 100,
                minHeight: 7,
                color: AppColors.accent,
                backgroundColor: AppPalette.surface2,
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 10),
      Row(
        children: [
          Expanded(child: _vital('Batería', '12,4 V')),
          const SizedBox(width: 8),
          Expanded(child: _vital('Estado', 'OK', dot: AppColors.success)),
          const SizedBox(width: 8),
          Expanded(
            child: _vital('Service', '2.100 km', color: AppColors.warning),
          ),
        ],
      ),
      const SizedBox(height: 10),
      SectionCard(
        child: Row(
          children: [
            const CircleAvatar(
              backgroundColor: AppColors.accentSubtle,
              foregroundColor: AppColors.accent,
              child: Icon(Icons.location_on_outlined),
            ),
            const SizedBox(width: 10),
            const Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Eyebrow('Estacionado hace 2 h'),
                  SizedBox(height: 3),
                  Text(
                    'Av. Corrientes 1234',
                    style: TextStyle(fontWeight: FontWeight.w700),
                  ),
                  SizedBox(height: 2),
                  Text(
                    'a 600 m tuyo · lo dejó Sofía',
                    style: TextStyle(fontSize: 11, color: AppColors.muted),
                  ),
                ],
              ),
            ),
            TextButton(onPressed: () {}, child: const Text('Ir')),
          ],
        ),
      ),
      const SizedBox(height: 10),
      _proximoTurnoCard(),
      const SizedBox(height: 16),
      ElevatedButton.icon(
        onPressed: () => _showStartJourneySheet(context),
        icon: const Icon(Icons.play_arrow_rounded),
        label: const Text('Iniciar viaje'),
      ),
      const SizedBox(height: 16),
      _bleCard(connected),
    ]);
  }

  Widget _vital(String label, String value, {Color? dot, Color? color}) =>
      SectionCard(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Eyebrow(label),
            const SizedBox(height: 6),
            Row(
              children: [
                if (dot != null)
                  Container(
                    width: 7,
                    height: 7,
                    margin: const EdgeInsets.only(right: 5),
                    decoration: BoxDecoration(
                      color: dot,
                      shape: BoxShape.circle,
                    ),
                  ),
                Flexible(
                  child: Text(
                    value,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: color,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      );
  Widget _bleCard(bool connected) => SectionCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(
              Icons.circle,
              size: 10,
              color: connected ? AppColors.success : AppColors.warning,
            ),
            const SizedBox(width: 7),
            Expanded(
              child: Text(
                _connectionStatus,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
          ],
        ),
        const SizedBox(height: 7),
        Text(
          'Último dato: $_receivedData',
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 12, color: AppColors.muted),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _command,
                enabled: _write != null,
                decoration: const InputDecoration(
                  isDense: true,
                  labelText: 'Enviar comando',
                ),
              ),
            ),
            IconButton(
              onPressed: _write == null ? null : _send,
              icon: _sending
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.send_outlined),
            ),
            IconButton(
              onPressed: _read?.properties.read == true ? _readOnce : null,
              icon: const Icon(Icons.download_outlined),
            ),
          ],
        ),
        const SizedBox(height: 4),
        const Text(
          'Simulador de nafta',
          style: TextStyle(fontSize: 11, color: AppColors.muted),
        ),
        Slider(
          value: _fuel,
          min: 0,
          max: 100,
          onChanged: (value) => setState(() => _fuel = value),
        ),
      ],
    ),
  );

  Widget _shared() => _page(const ValueKey('shared'), [
    PageHeader(
      title: 'Compartido',
      subtitle: 'Familia · 4 miembros',
      // The trailing IconButton has been removed entirely
    ),
    const SizedBox(height: 18),
    Row(
      children: [
        SizedBox(
          width: 93, // Squeezed width constraint
          height: 33,
          child: Stack(
            children: [
              Positioned(
                left: 0,
                child: _avatar('LM', AppPalette.member1, hasBorder: false),
              ),
              Positioned(
                left: 20,
                child: _avatar('SM', AppPalette.member2, hasBorder: false),
              ),
              Positioned(
                left: 40,
                child: _avatar('MG', AppPalette.member3, hasBorder: false),
              ),
              Positioned(
                left: 60,
                child: _avatar('PA', AppPalette.member4, hasBorder: false),
              ),
            ],
          ),
        ),
        const SizedBox(width: 8),
        InkWell(
          onTap: _showInviteDialog,
          borderRadius: BorderRadius.circular(16.5),
          child: Container(
            width: 33,
            height: 33,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(
                color: AppColors.muted.withValues(alpha: 0.4),
                width: 1.5,
              ),
            ),
            alignment: Alignment.center,
            child: const Icon(Icons.add, size: 18, color: AppColors.muted),
          ),
        ),
        const SizedBox(width: 8),
        const Expanded(
          child: Text(
            'Invitar por QR o link',
            style: TextStyle(fontSize: 12, color: AppColors.muted),
          ),
        ),
      ],
    ),
    const SizedBox(height: 16),
    _fuelSplitCard(),
    const SizedBox(height: 10),
    _saldarCard(context),
    const SizedBox(height: 10),
    SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Eyebrow('Esta semana'),
              TextButton.icon(
                onPressed: () {},
                icon: const Icon(Icons.add, size: 16),
                label: const Text('Reservar'),
              ),
            ],
          ),
          _calendarSlot('HOY', '18–21', 'Vos', AppPalette.member1),
          const Divider(),
          _calendarSlot('SÁB', '09–14', 'Sofía · Pilar', AppPalette.member2),
          const Divider(),
          _calendarSlot('DOM', 'todo', 'Martín', AppPalette.member3),
          const Divider(),
          _calendarSlot('LUN', '—', 'Libre', AppPalette.surface2),
        ],
      ),
    ),
  ]);

  Widget _fuelSplitCard() => SectionCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Eyebrow('Nafta · septiembre'),
            Text(
              '142 L',
              style: TextStyle(fontSize: 11, color: AppColors.muted),
            ),
          ],
        ),
        const SizedBox(height: 6),
        const Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text(
              r'$84.200',
              style: TextStyle(
                fontSize: 36,
                fontWeight: FontWeight.w800,
              ), // Increased size and weight
            ),
            SizedBox(width: 7),
            Padding(
              padding: EdgeInsets.only(
                bottom: 6,
              ), // Adjusted to align with the larger text
              child: Text(
                'total del grupo',
                style: TextStyle(fontSize: 11, color: AppColors.muted),
              ),
            ),
          ],
        ),
        const SizedBox(height: 11),
        ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: const SizedBox(
            height: 9,
            child: Row(
              children: [
                Expanded(
                  flex: 42,
                  child: ColoredBox(color: AppPalette.member1),
                ),
                SizedBox(width: 2),
                Expanded(
                  flex: 31,
                  child: ColoredBox(color: AppPalette.member2),
                ),
                SizedBox(width: 2),
                Expanded(
                  flex: 18,
                  child: ColoredBox(color: AppPalette.member3),
                ),
                SizedBox(width: 2),
                Expanded(flex: 9, child: ColoredBox(color: AppPalette.member4)),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        _fuelMember('LM', 'Vos', '42%', r'$35.364', AppPalette.member1),
        _fuelMember('SM', 'Sofía', '31%', r'$26.102', AppPalette.member2),
        _fuelMember('MG', 'Martín', '18%', r'$15.156', AppPalette.member3),
        _fuelMember('PA', 'Papá', '9%', r'$7.578', AppPalette.member4),
      ],
    ),
  );
  Widget _fuelMember(
    String initials,
    String name,
    String percent,
    String amount,
    Color color,
  ) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Row(
      children: [
        _avatar(initials, color),
        const SizedBox(width: 5),
        Expanded(
          child: Text(
            name,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
          ),
        ),
        SizedBox(
          width: 34,
          child: Text(
            percent,
            style: const TextStyle(fontSize: 11, color: AppColors.muted),
          ),
        ),
        Text(
          amount,
          style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
        ),
      ],
    ),
  );
  Widget _calendarSlot(String day, String time, String person, Color color) =>
      Row(
        children: [
          SizedBox(
            width: 51,
            child: Text(
              '$day\n$time',
              style: const TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w700,
                color: AppColors.muted,
              ),
            ),
          ),
          Expanded(
            child: Container(
              height: 29,
              padding: const EdgeInsets.symmetric(horizontal: 9),
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(8),
              ),
              alignment: Alignment.centerLeft,
              child: Text(
                person,
                style: TextStyle(
                  fontSize: 12,
                  color: person == 'Libre' ? AppColors.muted : Colors.white,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ),
        ],
      );
  void _settleUp() => ScaffoldMessenger.of(context).showSnackBar(
    const SnackBar(
      content: Text('Pago registrado. Actualizaremos el saldo del grupo.'),
    ),
  );
  Widget _avatar(String name, Color color, {bool hasBorder = true}) =>
      Container(
        width: 33,
        height: 33,
        decoration: BoxDecoration(
          color: color,
          shape: BoxShape.circle,
          border: hasBorder
              ? Border.all(
                  color: Theme.of(context).colorScheme.surface,
                  width: 2,
                )
              : null,
        ),
        alignment: Alignment.center,
        child: Text(
          name,
          style: const TextStyle(
            color: Colors.white,
            fontSize: 10,
            fontWeight: FontWeight.w700,
          ),
        ),
      );
  Widget _activity() => _page(const ValueKey('activity'), [
    PageHeader(title: 'Actividad', subtitle: 'Golf GTI · últimos 30 días'),
    const SizedBox(height: 16),
    Container(
      decoration: BoxDecoration(
        color: AppPalette.surface2,
        borderRadius: BorderRadius.circular(12),
      ),
      padding: const EdgeInsets.all(3),
      child: Row(
        children: [
          Expanded(child: _segment('Viajes', !_summary)),
          Expanded(child: _segment('Resumen', _summary)),
        ],
      ),
    ),
    if (_summary) ...[const SizedBox(height: 12), _periodPicker()],
    const SizedBox(height: 16),
    if (_summary) ..._summaryContent() else ..._tripContent(),
  ]);
  Widget _segment(String text, bool selected) => InkWell(
    onTap: () => setState(() => _summary = text == 'Resumen'),
    child: Container(
      padding: const EdgeInsets.symmetric(vertical: 9),
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: selected ? Colors.white : Colors.transparent,
        borderRadius: BorderRadius.circular(9),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w700,
          color: selected ? AppColors.text : AppColors.muted,
        ),
      ),
    ),
  );
  List<Widget> _tripContent() => [
    const Text(
      'Últimos viajes',
      style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
    ),
    const SizedBox(height: 8),
    SectionCard(
      child: Column(
        children: [
          _trip(
            'Hoy',
            'Casa → Trabajo',
            '12,4 km',
            '18 min',
            const ['LM'],
            const [AppPalette.member1],
          ),
          const Divider(),
          _trip(
            'Ayer',
            'Ruta costera',
            '42,8 km',
            '51 min',
            const ['SM', 'LM'],
            const [AppPalette.member2, AppPalette.member1],
          ),
          const Divider(),
          _trip(
            'Dom, 31 ago',
            'Centro → Norte',
            '8,6 km',
            '16 min',
            const ['MG'],
            const [AppPalette.member3],
          ),
          const Divider(),
          _trip(
            'Vie, 29 ago',
            'Belgrano → Palermo',
            '15,7 km',
            '26 min',
            const ['LM', 'SM'],
            const [AppPalette.member1, AppPalette.member2],
          ),
          const Divider(),
          _trip(
            'Jue, 28 ago',
            'Trabajo → Gimnasio',
            '6,2 km',
            '14 min',
            const ['SM'],
            const [AppPalette.member2],
          ),
          const Divider(),
          _trip(
            'Mié, 27 ago',
            'Centro → Tigre',
            '31,4 km',
            '43 min',
            const ['MG', 'PA'],
            const [AppPalette.member3, AppPalette.member4],
          ),
        ],
      ),
    ),
  ];
  Widget _trip(
    String day,
    String route,
    String km,
    String duration,
    List<String> initials,
    List<Color> colors,
  ) => Row(
    children: [
      SizedBox(
        width: 75,
        child: Text(
          day,
          style: const TextStyle(fontSize: 11, color: AppColors.muted),
        ),
      ),
      _tripAvatars(initials, colors),
      const SizedBox(width: 5),
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              route,
              style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
            ),
            Text(
              duration,
              style: const TextStyle(fontSize: 11, color: AppColors.muted),
            ),
          ],
        ),
      ),
      Text(
        km,
        style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
      ),
    ],
  );
  Widget _tripAvatars(List<String> initials, List<Color> colors) => SizedBox(
    width: initials.length == 1 ? 33 : 49,
    height: 33,
    child: Stack(
      children: List.generate(
        initials.length,
        (index) => Positioned(
          left: index * 17.0,
          child: Container(
            width: 33,
            height: 33,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: colors[index],
              shape: BoxShape.circle,
              border: Border.all(
                color: Theme.of(context).colorScheme.surface,
                width: 2,
              ),
            ),
            child: Text(
              initials[index],
              style: const TextStyle(
                color: Colors.white,
                fontSize: 10,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ),
      ),
    ),
  );
  Widget _periodPicker() => SizedBox(
    height: 34,
    child: ListView.separated(
      scrollDirection: Axis.horizontal,
      itemCount: 5,
      separatorBuilder: (_, index) => const SizedBox(width: 6),
      itemBuilder: (_, index) {
        const periods = ['7 d', '30 d', '3 m', '6 m', '1 a'];
        final value = periods[index];
        final selected = value == _period;
        return ChoiceChip(
          label: Text(value),
          selected: selected,
          onSelected: (_) => setState(() => _period = value),
          selectedColor: AppColors.accent,
          labelStyle: TextStyle(
            color: selected ? Colors.white : AppColors.muted,
            fontWeight: FontWeight.w700,
            fontSize: 12,
          ),
          side: BorderSide(
            color: selected ? AppColors.accent : AppColors.border,
          ),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(9)),
        );
      },
    ),
  );
  List<Widget> _summaryContent() => [
    Row(
      children: [
        Expanded(
          child: _stat(
            Icons.route_outlined,
            'Distancia',
            '1.284',
            'km',
            '↑ 12% vs. agosto',
            AppColors.success,
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: _stat(
            Icons.schedule_outlined,
            'Al volante',
            '38',
            'h 20 min',
            '↑ 8%',
            AppColors.success,
          ),
        ),
      ],
    ),
    const SizedBox(height: 8),
    Row(
      children: [
        Expanded(
          child: _stat(
            Icons.water_drop_outlined,
            'Nafta',
            '142',
            'L · 11,1 L/100',
            '↓ 6% de consumo',
            AppColors.success,
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: _stat(
            Icons.location_on_outlined,
            'Más visitado',
            'Centro',
            '',
            '8 viajes · 96 km',
            AppColors.muted,
          ),
        ),
      ],
    ),
    const SizedBox(height: 12),
    _consumptionChart(),
    const SizedBox(height: 12),
    _driverBreakdown(),
  ];
  Widget _stat(
    IconData icon,
    String label,
    String value,
    String unit,
    String delta,
    Color deltaColor,
  ) => SectionCard(
    padding: const EdgeInsets.all(11),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, size: 13, color: AppColors.muted),
            const SizedBox(width: 4),
            Eyebrow(label),
          ],
        ),
        const SizedBox(height: 7),
        RichText(
          text: TextSpan(
            style: TextStyle(color: Theme.of(context).colorScheme.onSurface),
            children: [
              TextSpan(
                text: value,
                style: const TextStyle(
                  fontSize: 21,
                  fontWeight: FontWeight.w700,
                ),
              ),
              TextSpan(
                text: ' $unit',
                style: const TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                  color: AppColors.muted,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 4),
        Text(
          delta,
          style: TextStyle(
            fontSize: 10,
            color: deltaColor,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    ),
  );

  Widget _consumptionChart() {
    const values = [38, 52, 31, 64, 47, 80, 36, 27, 57, 69, 44, 100, 61, 49];
    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Eyebrow('Consumo por día'),
              Text(
                'pico: sáb 14',
                style: TextStyle(fontSize: 11, color: AppColors.muted),
              ),
            ],
          ),
          const SizedBox(height: 12),
          SizedBox(
            height: 104,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: values
                  .map(
                    (value) => Expanded(
                      child: Container(
                        margin: const EdgeInsets.symmetric(horizontal: 2),
                        height: value.toDouble() * .78,
                        decoration: BoxDecoration(
                          color: value == 100
                              ? AppColors.accent
                              : AppColors.accent.withValues(alpha: .72),
                          borderRadius: const BorderRadius.vertical(
                            top: Radius.circular(4),
                          ),
                        ),
                      ),
                    ),
                  )
                  .toList(),
            ),
          ),
          const Padding(
            padding: EdgeInsets.only(top: 6),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '1 sep',
                  style: TextStyle(fontSize: 10, color: AppColors.muted),
                ),
                Text(
                  '15 sep',
                  style: TextStyle(fontSize: 10, color: AppColors.muted),
                ),
                Text(
                  '30 sep',
                  style: TextStyle(fontSize: 10, color: AppColors.muted),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _driverBreakdown() => SectionCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Eyebrow('Kilómetros por conductor'),
        const SizedBox(height: 12),
        Row(
          children: [
            SizedBox(
              width: 94,
              height: 94,
              child: CustomPaint(
                painter: _DonutPainter(),
                child: const Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        '1.284',
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        'km',
                        style: TextStyle(fontSize: 10, color: AppColors.muted),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(width: 14),
            const Expanded(
              child: Column(
                children: [
                  _LegendDot(
                    color: AppColors.accent,
                    name: 'Vos',
                    value: '539 km',
                  ),
                  SizedBox(height: 7),
                  _LegendDot(
                    color: AppPalette.member2,
                    name: 'Sofía',
                    value: '398 km',
                  ),
                  SizedBox(height: 7),
                  _LegendDot(
                    color: AppPalette.member3,
                    name: 'Martín',
                    value: '231 km',
                  ),
                  SizedBox(height: 7),
                  _LegendDot(
                    color: AppPalette.member4,
                    name: 'Papá',
                    value: '116 km',
                  ),
                ],
              ),
            ),
          ],
        ),
      ],
    ),
  );

  Widget _profile() => _page(const ValueKey('profile'), [
    PageHeader(title: 'Perfil', subtitle: widget.nombreUsuario),
    const SizedBox(height: 18),
    SectionCard(
      child: Row(
        children: [
          CircleAvatar(
            radius: 25,
            backgroundColor: AppColors.accent,
            child: Text(
              _initials,
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  widget.nombreUsuario,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                const Text(
                  'Plan personal',
                  style: TextStyle(fontSize: 12, color: AppColors.muted),
                ),
              ],
            ),
          ),
          const Icon(Icons.chevron_right, color: AppColors.muted),
        ],
      ),
    ),
    const SizedBox(height: 16),
    const Eyebrow('Vehículo y dispositivo'),
    const SizedBox(height: 7),
    SectionCard(
      padding: EdgeInsets.zero,
      child: Column(
        children: [
          _setting(Icons.bluetooth_outlined, 'Conexión OBD', _connectionStatus),
          const Divider(height: 1),
          _setting(
            Icons.directions_car_outlined,
            'Vehículo',
            'Volkswagen Golf GTI',
          ),
        ],
      ),
    ),
    const SizedBox(height: 16),
    const Eyebrow('Apariencia'),
    const SizedBox(height: 7),
    ValueListenableBuilder<ThemeMode>(
      valueListenable: themeModeNotifier,
      builder: (_, mode, _) => SectionCard(
        padding: EdgeInsets.zero,
        child: _switch(
          'Modo oscuro',
          'Usar la interfaz oscura',
          mode == ThemeMode.dark,
          (enabled) => themeModeNotifier.value = enabled
              ? ThemeMode.dark
              : ThemeMode.light,
        ),
      ),
    ),
    const SizedBox(height: 16),
    const Eyebrow('Alertas'),
    const SizedBox(height: 7),
    SectionCard(
      padding: EdgeInsets.zero,
      child: Column(
        children: [
          _switch(
            'Mantenimiento',
            'Service y fallas del vehículo',
            _maintenanceAlerts,
            (v) => setState(() => _maintenanceAlerts = v),
          ),
          const Divider(height: 1),
          _switch(
            'Viajes',
            'Inicio y fin de cada recorrido',
            _tripAlerts,
            (v) => setState(() => _tripAlerts = v),
          ),
        ],
      ),
    ),
    const SizedBox(height: 24),
    OutlinedButton.icon(
      onPressed: () => Navigator.pushReplacement(
        context,
        MaterialPageRoute(builder: (_) => const LoginScreen()),
      ),
      icon: const Icon(Icons.logout, color: AppColors.danger),
      label: const Text(
        'Cerrar sesión',
        style: TextStyle(color: AppColors.danger),
      ),
    ),
  ]);
  Widget _setting(IconData icon, String title, String subtitle) => ListTile(
    leading: Icon(icon, color: AppColors.muted),
    title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
    subtitle: Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis),
    trailing: const Icon(Icons.chevron_right, color: AppColors.muted),
  );
  Widget _switch(
    String title,
    String subtitle,
    bool value,
    ValueChanged<bool> changed,
  ) => SwitchListTile(
    value: value,
    onChanged: changed,
    activeTrackColor: AppColors.accent,
    title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
    subtitle: Text(subtitle),
    contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 3),
  );
  String get _initials {
    final name = widget.nombreUsuario.trim().split('@').first;
    return name.isEmpty
        ? 'LM'
        : name.substring(0, name.length.clamp(0, 2)).toUpperCase();
  }
}

class _LegendDot extends StatelessWidget {
  final Color color;
  final String name;
  final String value;
  const _LegendDot({
    required this.color,
    required this.name,
    required this.value,
  });

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Container(
        width: 8,
        height: 8,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      ),
      const SizedBox(width: 7),
      Expanded(
        child: Text(
          name,
          style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
        ),
      ),
      Text(
        value,
        style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700),
      ),
    ],
  );
}

class _DonutPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    const colors = [
      AppPalette.member1,
      AppPalette.member2,
      AppPalette.member3,
      AppPalette.member4,
    ];
    const parts = [.42, .31, .18, .09];
    final rect = Offset.zero & size;
    var start = -1.5708;
    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 16
      ..strokeCap = StrokeCap.butt;
    for (var index = 0; index < parts.length; index++) {
      paint.color = colors[index];
      final sweep = parts[index] * 6.28318 - .025;
      canvas.drawArc(rect.deflate(8), start, sweep, false, paint);
      start += sweep + .025;
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
