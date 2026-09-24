import 'package:flutter_test/flutter_test.dart';
import 'package:obd_app/core/utils/qr_payload.dart';

void main() {
  test('el QR de invitación va y vuelve', () {
    final raw = QrPayload.invite(email: ' Grace@Example.com ', name: 'Grace');
    expect(raw, startsWith('obdc://invite?'));

    final parsed = QrPayload.parseInvite(raw);
    expect(parsed, isNotNull);
    expect(parsed!.email, 'grace@example.com');
    expect(parsed.name, 'Grace');
  });

  test('sin nombre', () {
    final parsed = QrPayload.parseInvite(
      QrPayload.invite(email: 'ada@example.com'),
    );
    expect(parsed!.email, 'ada@example.com');
    expect(parsed.name, isNull);
  });

  test('ignora QRs ajenos', () {
    expect(QrPayload.parseInvite('https://example.com/menu'), isNull);
    expect(QrPayload.parseInvite('obdc://other?email=a@b.c'), isNull);
    expect(QrPayload.parseInvite('obdc://invite?email=no-arroba'), isNull);
    expect(QrPayload.parseInvite('obdc://invite'), isNull);
    expect(QrPayload.parseInvite(''), isNull);
  });
}
