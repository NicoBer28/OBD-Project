import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/api_messages.dart';
import 'package:obd_app/data/api/obd_api.dart';

/// Alta de un auto: `POST /api/v1/cars`.
///
/// El modelo se elige de `GET /models` (catálogo curado, no texto libre: así
/// el servidor no se llena de "VW" / "vw" / "Volkswagen"). El dueño sale del
/// token, nunca del formulario. Devuelve el [Car] creado al hacer `pop`.
class CreateCarScreen extends StatefulWidget {
  final HomeController controller;

  const CreateCarScreen({super.key, required this.controller});

  static Future<Car?> show(BuildContext context, HomeController controller) {
    return Navigator.of(context).push<Car>(
      MaterialPageRoute(
        builder: (_) => CreateCarScreen(controller: controller),
      ),
    );
  }

  @override
  State<CreateCarScreen> createState() => _CreateCarScreenState();
}

class _CreateCarScreenState extends State<CreateCarScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nombreController = TextEditingController();
  final _patenteController = TextEditingController();
  final _kmController = TextEditingController();

  List<CarModel>? _models;
  Object? _modelsError;
  CarModel? _model;
  bool _guardando = false;

  @override
  void initState() {
    super.initState();
    _cargarModelos();
  }

  @override
  void dispose() {
    _nombreController.dispose();
    _patenteController.dispose();
    _kmController.dispose();
    super.dispose();
  }

  Future<void> _cargarModelos() async {
    setState(() => _modelsError = null);
    try {
      final models = await widget.controller.models();
      if (!mounted) return;
      setState(() => _models = models);
    } on ObdApiException catch (error) {
      if (!mounted) return;
      setState(() => _modelsError = error);
    }
  }

  Future<void> _guardar() async {
    if (_guardando || !_formKey.currentState!.validate()) return;
    final model = _model;
    if (model == null) return;

    final navigator = Navigator.of(context);
    setState(() => _guardando = true);

    try {
      final km = _kmController.text.trim();
      final created = await widget.controller.createCar(
        name: _nombreController.text.trim(),
        modelId: model.modelId,
        licensePlate: _patenteController.text.trim(),
        mileage: km.isEmpty ? null : int.tryParse(km),
      );
      if (!mounted) return;
      navigator.pop(created);
    } on ObdApiException catch (error) {
      if (!mounted) return;
      ApiMessages.show(
        context,
        error,
        notFound: 'Ese modelo ya no está en el catálogo.',
        conflict: 'Ya tenés un auto con esa patente.',
      );
    } finally {
      if (mounted) setState(() => _guardando = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final models = _models;

    return Scaffold(
      appBar: AppBar(title: const Text('Agregar auto')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 62,
                height: 62,
                decoration: BoxDecoration(
                  color: t.accentSubtle,
                  borderRadius: BorderRadius.circular(18),
                ),
                child: Icon(AppIcons.car, size: 32, color: t.accent),
              ),
              const SizedBox(height: 18),
              const Text(
                'Tu auto',
                style: TextStyle(
                  fontSize: 25,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -0.5,
                ),
              ),
              const SizedBox(height: 5),
              Text(
                'Ponele un nombre y elegí el modelo del catálogo.',
                style: TextStyle(color: t.muted),
              ),
              const SizedBox(height: 26),

              // Campo: Nombre (name)
              TextFormField(
                controller: _nombreController,
                enabled: !_guardando,
                maxLength: 60,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(
                  labelText: 'Nombre',
                  hintText: 'Ej. Golf de Sofía',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(AppIcons.car),
                  counterText: '',
                ),
                validator: (value) {
                  if (value == null || value.trim().isEmpty) {
                    return 'Ponele un nombre al auto';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 16),

              // Campo: Modelo (modelId) — del catálogo
              if (models == null && _modelsError == null)
                const _LoadingField(label: 'Cargando modelos…')
              else if (_modelsError != null)
                _ErrorField(
                  message: ApiMessages.of(_modelsError!),
                  onRetry: _cargarModelos,
                )
              else
                DropdownButtonFormField<CarModel>(
                  initialValue: _model,
                  isExpanded: true,
                  decoration: const InputDecoration(
                    labelText: 'Modelo',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.category_outlined),
                  ),
                  items: [
                    for (final m in models!)
                      DropdownMenuItem(
                        value: m,
                        child: Text(m.label, overflow: TextOverflow.ellipsis),
                      ),
                  ],
                  onChanged: _guardando ? null : (value) => setState(() => _model = value),
                  validator: (value) => value == null ? 'Elegí el modelo' : null,
                ),
              if (_model != null) ...[
                const SizedBox(height: 6),
                Padding(
                  padding: const EdgeInsets.only(left: 12),
                  child: Text(
                    'Protocolo OBD: ${_model!.modelProtocol}',
                    style: TextStyle(fontSize: 11, color: t.muted),
                  ),
                ),
              ],
              const SizedBox(height: 16),

              // Campo: Patente (licensePlate) — opcional
              TextFormField(
                controller: _patenteController,
                enabled: !_guardando,
                maxLength: 16,
                textCapitalization: TextCapitalization.characters,
                decoration: const InputDecoration(
                  labelText: 'Patente (opcional)',
                  hintText: 'AB 123 CD',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(AppIcons.plate),
                  counterText: '',
                ),
              ),
              const SizedBox(height: 16),

              // Campo: Kilometraje (mileage) — opcional
              TextFormField(
                controller: _kmController,
                enabled: !_guardando,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: const InputDecoration(
                  labelText: 'Kilometraje actual (opcional)',
                  hintText: '120000',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(AppIcons.odometer),
                  suffixText: 'km',
                ),
              ),
              const SizedBox(height: 32),

              SizedBox(
                width: double.infinity,
                height: 52,
                child: ElevatedButton(
                  onPressed: _guardando || models == null ? null : _guardar,
                  child: _guardando
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text(
                          'GUARDAR AUTO',
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _LoadingField extends StatelessWidget {
  final String label;

  const _LoadingField({required this.label});

  @override
  Widget build(BuildContext context) {
    return InputDecorator(
      decoration: const InputDecoration(
        border: OutlineInputBorder(),
        prefixIcon: Icon(Icons.category_outlined),
      ),
      child: Row(
        children: [
          const SizedBox(
            width: 16,
            height: 16,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
          const SizedBox(width: 12),
          Text(label, style: TextStyle(color: context.tokens.muted)),
        ],
      ),
    );
  }
}

class _ErrorField extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;

  const _ErrorField({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return InputDecorator(
      decoration: const InputDecoration(
        border: OutlineInputBorder(),
        prefixIcon: Icon(Icons.category_outlined),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              message,
              style: TextStyle(color: t.danger, fontSize: 12),
            ),
          ),
          TextButton(onPressed: onRetry, child: const Text('Reintentar')),
        ],
      ),
    );
  }
}
