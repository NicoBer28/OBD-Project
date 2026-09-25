import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/qr_payload.dart';

/// Escanea el "Mi código QR" de otra persona y devuelve su correo.
///
/// Solo acepta códigos de la app (`obdc://invite?...`); cualquier otro QR se
/// ignora con un aviso, así un QR de un menú de restaurante no termina como
/// correo de invitación. Hace `pop` con `(email, name)` al primer código
/// válido.
class ScanQrScreen extends StatefulWidget {
  const ScanQrScreen({super.key});

  static Future<({String email, String? name})?> show(BuildContext context) {
    return Navigator.of(context).push<({String email, String? name})>(
      MaterialPageRoute(
        builder: (_) => const ScanQrScreen(),
        fullscreenDialog: true,
      ),
    );
  }

  @override
  State<ScanQrScreen> createState() => _ScanQrScreenState();
}

class _ScanQrScreenState extends State<ScanQrScreen> {
  final _scanner = MobileScannerController(
    detectionSpeed: DetectionSpeed.noDuplicates,
    formats: const [BarcodeFormat.qrCode],
  );
  bool _done = false;
  String? _hint;

  @override
  void dispose() {
    _scanner.dispose();
    super.dispose();
  }

  void _onDetect(BarcodeCapture capture) {
    if (_done) return;
    for (final code in capture.barcodes) {
      final raw = code.rawValue;
      if (raw == null) continue;
      final parsed = QrPayload.parseInvite(raw);
      if (parsed == null) {
        setState(() => _hint = 'Ese código no es de OBD-C.');
        continue;
      }
      _done = true;
      Navigator.of(context).pop(parsed);
      return;
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: const Text('Escanear código'),
        actions: [
          IconButton(
            tooltip: 'Linterna',
            onPressed: () => _scanner.toggleTorch(),
            icon: const Icon(Icons.flashlight_on_outlined),
          ),
        ],
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          MobileScanner(
            controller: _scanner,
            onDetect: _onDetect,
            errorBuilder: (context, error) => Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Text(
                  _cameraMessage(error),
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white),
                ),
              ),
            ),
          ),
          // Marco guía
          Center(
            child: Container(
              width: 240,
              height: 240,
              decoration: BoxDecoration(
                border: Border.all(color: Colors.white70, width: 2),
                borderRadius: BorderRadius.circular(24),
              ),
            ),
          ),
          Positioned(
            left: 24,
            right: 24,
            bottom: 40,
            child: Column(
              children: [
                Icon(AppIcons.qr, color: Colors.white70, size: 28),
                const SizedBox(height: 8),
                Text(
                  _hint ?? 'Apuntá al "Mi código QR" de la persona que querés invitar.',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: _hint == null ? Colors.white : t.warning,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _cameraMessage(MobileScannerException error) => switch (error.errorCode) {
    MobileScannerErrorCode.permissionDenied => 'Sin permiso de cámara. Habilitalo en Ajustes para escanear.',
    MobileScannerErrorCode.unsupported => 'Este dispositivo no puede escanear códigos.',
    _ => 'No se pudo abrir la cámara.',
  };
}
