import 'package:flutter/material.dart';
import 'package:obd_app/controllers/reservations_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/models/models.dart';
import 'package:obd_app/ui/screens/home/widgets/booking_sheet.dart';
import 'package:obd_app/ui/screens/home/widgets/shared_widgets.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Shared
/// ---------------------------------------------------------------------------

class SharedTab extends StatelessWidget {
  final List<MemberData> members;
  final FuelSummaryData fuelData;
  final ReservationsController reservations;

  const SharedTab({
    super.key,
    required this.members,
    required this.fuelData,
    required this.reservations,
  });

  void _showMessage(BuildContext context, String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  void _settleUp(BuildContext context) {
    _showMessage(context, 'Pago registrado. Actualizaremos el saldo del grupo.');
  }

  void _inviteMember(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (dialogContext) {
        final t = dialogContext.tokens;

        return AlertDialog(
          title: const Text('Invitar al grupo'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(AppIcons.qr, size: 128, color: t.text),
              const SizedBox(height: 16),
              const Text(
                'Escaneá este código para unirte al auto, '
                'o enviá una invitación directa.',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 20),
              const TextField(
                keyboardType: TextInputType.emailAddress,
                decoration: InputDecoration(
                  labelText: 'Correo electrónico',
                  prefixIcon: Icon(Icons.email_outlined),
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () {
                        Navigator.pop(dialogContext);
                        _showMessage(context, 'Enlace copiado al portapapeles');
                      },
                      icon: const Icon(AppIcons.link),
                      label: const Text('Copiar enlace'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filled(
                    tooltip: 'Enviar invitación',
                    onPressed: () {
                      Navigator.pop(dialogContext);
                      _showMessage(context, 'Invitación enviada');
                    },
                    icon: const Icon(AppIcons.send),
                  ),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _reserve(BuildContext context) async {
    final created = await BookingSheet.show(context, controller: reservations);

    if (created != null && context.mounted) {
      _showMessage(context, 'Reserva confirmada');
    }
  }

  Future<void> _confirmCancel(
    BuildContext context,
    Reservation reservation,
  ) async {
    final slot = reservations.slotFor(reservation);

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Cancelar reserva'),
        content: Text('${slot.day} · ${slot.time}'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Mantener'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Cancelar reserva'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await reservations.cancel(reservation);
      if (context.mounted) _showMessage(context, 'Reserva cancelada');
    } on ReservationRejected {
      if (context.mounted) {
        _showMessage(context, 'No se pudo cancelar esa reserva');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListView(
      key: const ValueKey('shared'),
      padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
      children: [
        PageHeader(
          title: 'Compartido',
          subtitle: 'Familia · ${members.length} miembros',
          trailing: IconButton(
            tooltip: 'Invitar miembro',
            onPressed: () => _inviteMember(context),
            icon: const Icon(AppIcons.invite),
          ),
        ),
        const SizedBox(height: 18),
        MemberHeader(members: members, onInvite: () => _inviteMember(context)),
        const SizedBox(height: 16),
        FuelSummaryCard(data: fuelData),
        const SizedBox(height: 10),
        BalanceCard(onSettle: () => _settleUp(context)),
        const SizedBox(height: 10),
        ListenableBuilder(
          listenable: reservations,
          builder: (context, _) {
            final upcoming = reservations.upcoming();

            return SectionCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SectionHeader(
                    title: 'Esta semana',
                    actionLabel: 'Reservar',
                    actionIcon: AppIcons.calendar,
                    onAction: () => _reserve(context),
                  ),
                  const SizedBox(height: 4),
                  if (upcoming.isEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(
                        'Nadie reservó el auto esta semana.',
                        style: TextStyle(
                          fontSize: 12,
                          color: context.tokens.muted,
                        ),
                      ),
                    )
                  else
                    ...upcoming.map(
                      (r) => Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: reservations.isMine(r)
                              ? () => _confirmCancel(context, r)
                              : null,
                          child: ScheduleSlotView(
                            slot: reservations.slotFor(r),
                          ),
                        ),
                      ),
                    ),
                  if (upcoming.any(reservations.isMine))
                    Padding(
                      padding: const EdgeInsets.only(top: 10),
                      child: Text(
                        'Tocá una reserva tuya para cancelarla.',
                        style: TextStyle(
                          fontSize: 11,
                          color: context.tokens.muted,
                        ),
                      ),
                    ),
                ],
              ),
            );
          },
        ),
      ],
    );
  }
}
