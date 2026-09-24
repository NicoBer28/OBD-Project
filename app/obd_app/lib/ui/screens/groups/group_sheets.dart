import 'package:flutter/material.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/api_messages.dart';
import 'package:obd_app/core/utils/qr_payload.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/screens/groups/scan_qr_screen.dart';
import 'package:qr_flutter/qr_flutter.dart';

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

/// ---------------------------------------------------------------------------
/// Crear grupo — `POST /groups`
/// ---------------------------------------------------------------------------

class CreateGroupSheet extends StatefulWidget {
  final HomeController controller;

  const CreateGroupSheet({super.key, required this.controller});

  static Future<Group?> show(BuildContext context, HomeController controller) =>
      _sheet<Group>(context, CreateGroupSheet(controller: controller));

  @override
  State<CreateGroupSheet> createState() => _CreateGroupSheetState();
}

class _CreateGroupSheetState extends State<CreateGroupSheet> {
  final _formKey = GlobalKey<FormState>();
  final _nombre = TextEditingController();
  bool _guardando = false;

  @override
  void dispose() {
    _nombre.dispose();
    super.dispose();
  }

  Future<void> _crear() async {
    if (_guardando || !_formKey.currentState!.validate()) return;
    final navigator = Navigator.of(context);
    setState(() => _guardando = true);
    try {
      final group = await widget.controller.createGroup(_nombre.text.trim());
      if (mounted) navigator.pop(group);
    } on ObdApiException catch (error) {
      if (mounted) ApiMessages.show(context, error);
    } finally {
      if (mounted) setState(() => _guardando = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    return Padding(
      padding: _sheetPadding(context),
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Crear un grupo',
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.w800,
                letterSpacing: -.5,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              'Tu familia, tus amigos: con quienes compartís el auto. '
              'Vos quedás como administrador.',
              style: TextStyle(color: t.muted),
            ),
            const SizedBox(height: 20),
            TextFormField(
              controller: _nombre,
              autofocus: true,
              enabled: !_guardando,
              maxLength: 60,
              textCapitalization: TextCapitalization.words,
              onFieldSubmitted: (_) => _crear(),
              decoration: const InputDecoration(
                labelText: 'Nombre del grupo',
                hintText: 'Ej. Familia Lazzari',
                prefixIcon: Icon(AppIcons.shared),
                counterText: '',
              ),
              validator: (value) => (value == null || value.trim().isEmpty) ? 'Ponele un nombre' : null,
            ),
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              height: 54,
              child: FilledButton.icon(
                onPressed: _guardando ? null : _crear,
                icon: _guardando
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(AppIcons.group),
                label: const Text('Crear grupo'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// ---------------------------------------------------------------------------
/// Invitar — `POST /invitations/invite/{groupId}`
/// ---------------------------------------------------------------------------

/// El admin invita un correo. Se puede tipear o **escanear el QR** que la
/// otra persona muestra desde "Mi código QR"; en ambos casos la invitación
/// queda pendiente hasta que esa persona la acepte desde su teléfono.
class InviteSheet extends StatefulWidget {
  final HomeController controller;

  const InviteSheet({super.key, required this.controller});

  static Future<Invitation?> show(
    BuildContext context,
    HomeController controller,
  ) => _sheet<Invitation>(context, InviteSheet(controller: controller));

  @override
  State<InviteSheet> createState() => _InviteSheetState();
}

class _InviteSheetState extends State<InviteSheet> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  String? _scannedName;
  bool _enviando = false;

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  Future<void> _escanear() async {
    final result = await ScanQrScreen.show(context);
    if (result == null || !mounted) return;
    setState(() {
      _email.text = result.email;
      _scannedName = result.name;
    });
  }

  Future<void> _enviar() async {
    if (_enviando || !_formKey.currentState!.validate()) return;
    final navigator = Navigator.of(context);
    setState(() => _enviando = true);
    try {
      final invitation = await widget.controller.invite(_email.text.trim());
      if (mounted) navigator.pop(invitation);
    } on ObdApiException catch (error) {
      if (mounted) {
        ApiMessages.show(
          context,
          error,
          forbidden: 'Solo un administrador del grupo puede invitar.',
          conflict: 'Esa persona ya es parte del grupo (o ya tiene una invitación pendiente).',
          notFound: 'Ya no sos parte de este grupo.',
        );
      }
    } finally {
      if (mounted) setState(() => _enviando = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final group = widget.controller.group;

    return Padding(
      padding: _sheetPadding(context),
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Invitar al grupo',
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.w800,
                letterSpacing: -.5,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              group == null
                  ? 'Elegí un grupo primero.'
                  : 'La persona va a ver la invitación a ${group.name} '
                        'en su app y decide si acepta.',
              style: TextStyle(color: t.muted),
            ),
            const SizedBox(height: 20),
            Material(
              color: t.accent.withValues(alpha: .08),
              borderRadius: BorderRadius.circular(14),
              child: InkWell(
                onTap: _enviando ? null : _escanear,
                borderRadius: BorderRadius.circular(14),
                child: Padding(
                  padding: const EdgeInsets.all(13),
                  child: Row(
                    children: [
                      Container(
                        width: 38,
                        height: 38,
                        decoration: BoxDecoration(
                          color: t.accent.withValues(alpha: .10),
                          shape: BoxShape.circle,
                        ),
                        child: Icon(AppIcons.scan, size: 19, color: t.accent),
                      ),
                      const SizedBox(width: 11),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Escanear su código QR',
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w700,
                                color: t.text,
                              ),
                            ),
                            const SizedBox(height: 3),
                            Text(
                              _scannedName == null
                                  ? 'Pedile que abra "Mi código QR" en su app.'
                                  : 'Código de $_scannedName leído.',
                              style: TextStyle(fontSize: 11, color: t.muted),
                            ),
                          ],
                        ),
                      ),
                      Icon(AppIcons.arrow, color: t.muted),
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(height: 14),
            Row(
              children: [
                Expanded(child: Divider(color: t.border)),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                  child: Text(
                    'o escribí su correo',
                    style: TextStyle(fontSize: 11, color: t.muted),
                  ),
                ),
                Expanded(child: Divider(color: t.border)),
              ],
            ),
            const SizedBox(height: 14),
            TextFormField(
              controller: _email,
              enabled: !_enviando,
              keyboardType: TextInputType.emailAddress,
              autocorrect: false,
              onChanged: (_) {
                if (_scannedName != null) setState(() => _scannedName = null);
              },
              onFieldSubmitted: (_) => _enviar(),
              decoration: const InputDecoration(
                labelText: 'Correo electrónico',
                prefixIcon: Icon(Icons.email_outlined),
              ),
              validator: (value) {
                final v = value?.trim() ?? '';
                if (v.isEmpty) return 'Ingresá un correo';
                if (!v.contains('@') || !v.contains('.')) {
                  return 'El formato del correo no es válido';
                }
                return null;
              },
            ),
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              height: 54,
              child: FilledButton.icon(
                onPressed: _enviando || group == null ? null : _enviar,
                icon: _enviando
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(AppIcons.send),
                label: const Text('Enviar invitación'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// ---------------------------------------------------------------------------
/// Mi código QR
/// ---------------------------------------------------------------------------

/// Lo que uno muestra para que un admin lo invite sin tipear el correo.
class MyQrSheet extends StatelessWidget {
  final UserProfile profile;

  const MyQrSheet({super.key, required this.profile});

  static Future<void> show(BuildContext context, UserProfile profile) =>
      _sheet<void>(context, MyQrSheet(profile: profile));

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final data = QrPayload.invite(
      email: profile.userEmail,
      name: profile.userName,
    );

    return Padding(
      padding: _sheetPadding(context),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Mi código QR',
            style: TextStyle(
              fontSize: 24,
              fontWeight: FontWeight.w800,
              letterSpacing: -.5,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            'Mostráselo a quien administre el grupo: al escanearlo te llega '
            'la invitación y la aceptás desde acá.',
            style: TextStyle(color: t.muted),
          ),
          const SizedBox(height: 22),
          Center(
            child: Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: t.border),
              ),
              child: QrImageView(
                data: data,
                size: 220,
                backgroundColor: Colors.white,
                eyeStyle: const QrEyeStyle(
                  eyeShape: QrEyeShape.square,
                  color: Color(0xFF131413),
                ),
                dataModuleStyle: const QrDataModuleStyle(
                  dataModuleShape: QrDataModuleShape.square,
                  color: Color(0xFF131413),
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),
          Center(
            child: Column(
              children: [
                Text(
                  profile.fullName,
                  style: TextStyle(fontWeight: FontWeight.w800, color: t.text),
                ),
                const SizedBox(height: 2),
                Text(
                  profile.userEmail,
                  style: TextStyle(fontSize: 12, color: t.muted),
                ),
              ],
            ),
          ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }
}
