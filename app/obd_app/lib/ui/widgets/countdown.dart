import 'dart:async';

import 'package:flutter/material.dart';

/// Cuenta regresiva hasta [hasta], actualizada cada segundo. Le pasa a [builder] el tiempo
/// que falta como "m:ss" (se queda en "0:00" si ya pasó).
class CuentaRegresiva extends StatefulWidget {
  final DateTime hasta;
  final Widget Function(BuildContext context, String restante) builder;

  const CuentaRegresiva({super.key, required this.hasta, required this.builder});

  @override
  State<CuentaRegresiva> createState() => _CuentaRegresivaState();
}

class _CuentaRegresivaState extends State<CuentaRegresiva> {
  late final Timer _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _timer.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final falta = widget.hasta.difference(DateTime.now());
    final segundos = falta.isNegative ? 0 : falta.inSeconds;
    final restante = '${segundos ~/ 60}:${(segundos % 60).toString().padLeft(2, '0')}';
    return widget.builder(context, restante);
  }
}
