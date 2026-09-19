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
        trailing: CircleAvatar(
          backgroundColor: AppColors.accent,
          child: Text(_initials),
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
                backgroundColor: const Color(0xFFF0F3F1),
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
      Container(
        padding: const EdgeInsets.all(13),
        decoration: BoxDecoration(
          color: AppColors.accentSubtle,
          borderRadius: BorderRadius.circular(15),
        ),
        child: const Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Eyebrow('Tu próximo turno'),
                  SizedBox(height: 3),
                  Text(
                    'Hoy · 18:00 – 21:00',
                    style: TextStyle(fontWeight: FontWeight.w700),
                  ),
                ],
              ),
            ),
            Text(
              'Calendario ›',
              style: TextStyle(
                color: AppColors.accent,
                fontWeight: FontWeight.w700,
                fontSize: 12,
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 16),
      ElevatedButton.icon(
        onPressed: () {},
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
      subtitle: 'Familia X· 4 miembros',
      trailing: IconButton(
        onPressed: () {},
        icon: const Icon(Icons.person_add_alt_1),
      ),
    ),
    const SizedBox(height: 18),
    Row(
      children: [
        _avatar('LM', AppColors.accent),
        _avatar('SM', const Color(0xFF6D4AFF)),
        _avatar('MG', const Color(0xFFE05A3E)),
        _avatar('PA', const Color(0xFFC2820B)),
        const SizedBox(width: 8),
        const Text(
          'Invitar por QR o link',
          style: TextStyle(fontSize: 12, color: AppColors.muted),
        ),
      ],
    ),
    const SizedBox(height: 16),
    SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Eyebrow('Hoy tiene el auto'),
          const SizedBox(height: 9),
          Row(
            children: [
              _avatar('SM', const Color(0xFF6D4AFF)),
              const SizedBox(width: 9),
              const Expanded(
                child: Text(
                  'Sofía Gimenez',
                  style: TextStyle(fontWeight: FontWeight.w700),
                ),
              ),
              const Text(
                'Hasta 18:00',
                style: TextStyle(color: AppColors.muted, fontSize: 12),
              ),
            ],
          ),
        ],
      ),
    ),
    const SizedBox(height: 10),
    SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Eyebrow('Próximos turnos'),
          const SizedBox(height: 10),
          _schedule('Hoy', '18:00 – 21:00', 'Lucas'),
          const Divider(),
          _schedule('Mañana', '08:00 – 11:00', 'Martín'),
          const Divider(),
          _schedule('Vie', '19:00 – 23:00', 'Sofía'),
        ],
      ),
    ),
    const SizedBox(height: 10),
    SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Eyebrow('Gastos de septiembre'),
          const SizedBox(height: 7),
          const Text(
            r'$ 84.200',
            style: TextStyle(fontSize: 27, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 10),
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: const LinearProgressIndicator(
              value: .68,
              minHeight: 9,
              color: AppColors.accent,
              backgroundColor: Color(0xFF6D4AFF),
            ),
          ),
          const SizedBox(height: 10),
          const Text(
            'Lucas 42% · Sofía 31% · Martín 18% · Paula 9%',
            style: TextStyle(fontSize: 11, color: AppColors.muted),
          ),
        ],
      ),
    ),
  ]);
  Widget _avatar(String name, Color color) => Container(
    margin: const EdgeInsets.only(right: 3),
    width: 33,
    height: 33,
    decoration: BoxDecoration(
      color: color,
      shape: BoxShape.circle,
      border: Border.all(color: Colors.white, width: 2),
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
  Widget _schedule(String day, String time, String person) => Row(
    children: [
      SizedBox(
        width: 58,
        child: Text(
          day,
          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
        ),
      ),
      Expanded(child: Text(time, style: const TextStyle(fontSize: 12))),
      Text(
        person,
        style: const TextStyle(fontSize: 12, color: AppColors.muted),
      ),
    ],
  );

  Widget _activity() => _page(const ValueKey('activity'), [
    PageHeader(title: 'Actividad', subtitle: 'Golf GTI · últimos 30 días'),
    const SizedBox(height: 16),
    Container(
      decoration: BoxDecoration(
        color: const Color(0xFFF0F3F1),
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
          _trip('Hoy', 'Casa → Trabajo', '12,4 km', '18 min'),
          const Divider(),
          _trip('Ayer', 'Ruta costera', '42,8 km', '51 min'),
          const Divider(),
          _trip('Dom, 31 ago', 'Centro → Norte', '8,6 km', '16 min'),
        ],
      ),
    ),
  ];
  Widget _trip(String day, String route, String km, String duration) => Row(
    children: [
      SizedBox(
        width: 75,
        child: Text(
          day,
          style: const TextStyle(fontSize: 11, color: AppColors.muted),
        ),
      ),
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
  List<Widget> _summaryContent() => [
    Row(
      children: [
        Expanded(child: _stat('Distancia', '1.284', 'km', '+12%')),
        const SizedBox(width: 8),
        Expanded(child: _stat('Viajes', '37', '', '+4 vs. ago')),
      ],
    ),
    const SizedBox(height: 8),
    Row(
      children: [
        Expanded(child: _stat('Combustible', '118', 'L', '-8%')),
        const SizedBox(width: 8),
        Expanded(child: _stat('Promedio', '9,2', 'L/100', 'estable')),
      ],
    ),
    const SizedBox(height: 12),
    SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Eyebrow('Distancia por semana'),
          const SizedBox(height: 16),
          SizedBox(
            height: 100,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [32, 54, 43, 76, 61, 88, 67]
                  .map(
                    (v) => Expanded(
                      child: Container(
                        margin: const EdgeInsets.symmetric(horizontal: 2),
                        height: v.toDouble(),
                        decoration: BoxDecoration(
                          color: AppColors.accent,
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
        ],
      ),
    ),
  ];
  Widget _stat(String label, String value, String unit, String delta) =>
      SectionCard(
        padding: const EdgeInsets.all(11),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Eyebrow(label),
            const SizedBox(height: 7),
            RichText(
              text: TextSpan(
                style: const TextStyle(color: AppColors.text),
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
              style: const TextStyle(
                fontSize: 10,
                color: AppColors.success,
                fontWeight: FontWeight.w700,
              ),
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
