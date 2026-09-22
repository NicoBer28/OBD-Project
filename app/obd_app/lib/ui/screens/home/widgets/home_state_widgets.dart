import 'package:flutter/material.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/trip_format.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Estados sin datos y tarjetas de la home que dependen de la API
/// ---------------------------------------------------------------------------

/// Una tarjeta grande con ícono, texto y una acción: "no tenés auto", "no
/// estás en un grupo", "no pudimos cargar".
class EmptyStateCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String message;
  final String? actionLabel;
  final IconData? actionIcon;
  final VoidCallback? onAction;
  final String? secondaryLabel;
  final IconData? secondaryIcon;
  final VoidCallback? onSecondary;

  const EmptyStateCard({
    super.key,
    required this.icon,
    required this.title,
    required this.message,
    this.actionLabel,
    this.actionIcon,
    this.onAction,
    this.secondaryLabel,
    this.secondaryIcon,
    this.onSecondary,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      radius: 20,
      padding: const EdgeInsets.fromLTRB(20, 26, 20, 20),
      child: Column(
        children: [
          Container(
            width: 62,
            height: 62,
            decoration: BoxDecoration(
              color: t.accentSubtle,
              borderRadius: BorderRadius.circular(18),
            ),
            child: Icon(icon, size: 32, color: t.accent),
          ),
          const SizedBox(height: 16),
          Text(
            title,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w800,
              letterSpacing: -.3,
              color: t.text,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            message,
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 13, color: t.muted),
          ),
          if (actionLabel != null) ...[
            const SizedBox(height: 18),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: onAction,
                icon: Icon(actionIcon ?? AppIcons.add),
                label: Text(actionLabel!),
              ),
            ),
          ],
          if (secondaryLabel != null) ...[
            const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: onSecondary,
                icon: Icon(secondaryIcon ?? AppIcons.qr, size: 16),
                label: Text(secondaryLabel!),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// Las invitaciones que me llegaron (`GET /invitations/pending`), con el
/// botón para aceptarlas.
class PendingInvitationsCard extends StatelessWidget {
  final List<PendingInvitation> invitations;
  final String? acceptingId;
  final ValueChanged<PendingInvitation> onAccept;

  const PendingInvitationsCard({
    super.key,
    required this.invitations,
    required this.onAccept,
    this.acceptingId,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(AppIcons.invite, size: 16, color: t.accent2),
              const SizedBox(width: 7),
              Expanded(
                child: Text(
                  invitations.length == 1
                      ? 'Te invitaron a un grupo'
                      : 'Te invitaron a ${invitations.length} grupos',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w800,
                    color: t.text,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          for (var i = 0; i < invitations.length; i++) ...[
            if (i > 0) const Divider(height: 16),
            Row(
              children: [
                IconBadge(icon: AppIcons.shared, color: t.accent2),
                const SizedBox(width: 11),
                Expanded(
                  child: TextStack(
                    title: invitations[i].groupName,
                    subtitle: invitations[i].invitationExpiresAt == null
                        ? 'Invitación pendiente'
                        : 'Vence el ${TripFormat.dayMonth(invitations[i].invitationExpiresAt!)}',
                  ),
                ),
                FilledButton(
                  onPressed: acceptingId == null
                      ? () => onAccept(invitations[i])
                      : null,
                  child: acceptingId == invitations[i].id
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('Unirme'),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

/// "Quién tiene el auto ahora": el viaje abierto del auto elegido.
class ActiveTripCard extends StatelessWidget {
  final Trip trip;
  final String driverName;
  final bool isMine;
  final VoidCallback? onFinish;

  const ActiveTripCard({
    super.key,
    required this.trip,
    required this.driverName,
    required this.isMine,
    this.onFinish,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final started = trip.startedAt;

    return Material(
      color: isMine ? t.accent.withValues(alpha: .10) : t.surface2,
      borderRadius: BorderRadius.circular(14),
      child: Padding(
        padding: const EdgeInsets.all(13),
        child: Row(
          children: [
            Icon(AppIcons.trip, size: 19, color: isMine ? t.accent : t.warning),
            const SizedBox(width: 10),
            Expanded(
              child: TextStack(
                title: isMine ? 'Estás en viaje' : '$driverName tiene el auto',
                subtitle: started == null
                    ? 'En curso'
                    : 'Desde las ${TripFormat.clock(started)} · ${TripFormat.duration(trip.elapsed ?? Duration.zero)}',
                titleStyle: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w800,
                  color: t.text,
                ),
                subtitleStyle: TextStyle(fontSize: 11, color: t.muted),
              ),
            ),
            if (isMine && onFinish != null)
              FilledButton.tonal(
                onPressed: onFinish,
                child: const Text('Terminar'),
              ),
          ],
        ),
      ),
    );
  }
}
