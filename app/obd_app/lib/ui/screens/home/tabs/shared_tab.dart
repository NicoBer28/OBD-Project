import 'package:flutter/material.dart';
import 'package:obd_app/controllers/home_controller.dart';
import 'package:obd_app/controllers/reservations_controller.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/core/utils/api_mappers.dart';
import 'package:obd_app/core/utils/api_messages.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/models/models.dart';
import 'package:obd_app/ui/screens/groups/group_sheets.dart';
import 'package:obd_app/ui/screens/home/widgets/booking_sheet.dart';
import 'package:obd_app/ui/screens/home/widgets/home_state_widgets.dart';
import 'package:obd_app/ui/screens/home/widgets/shared_widgets.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Shared
///
/// El grupo ("familia") elegido: sus miembros, los autos compartidos con él,
/// la nafta del mes por miembro y las reservas del auto. Las invitaciones
/// pendientes se muestran arriba de todo, tenga grupo o no.
/// ---------------------------------------------------------------------------

class SharedTab extends StatefulWidget {
  final HomeController controller;
  final ReservationsController? reservations;

  const SharedTab({
    super.key,
    required this.controller,
    required this.reservations,
  });

  @override
  State<SharedTab> createState() => _SharedTabState();
}

class _SharedTabState extends State<SharedTab> {
  String? _acceptingId;
  bool _sharing = false;

  HomeController get c => widget.controller;

  void _showMessage(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _invite() async {
    final invitation = await InviteSheet.show(context, c);
    if (invitation != null && mounted) {
      _showMessage('Invitación enviada a ${invitation.invitationEmail}');
    }
  }

  Future<void> _createGroup() async {
    final group = await CreateGroupSheet.show(context, c);
    if (group != null && mounted) _showMessage('Grupo ${group.name} creado');
  }

  void _showMyQr() {
    final me = c.me;
    if (me == null) return;
    MyQrSheet.show(context, me);
  }

  Future<void> _accept(PendingInvitation invitation) async {
    setState(() => _acceptingId = invitation.id);
    try {
      await c.acceptInvitation(invitation);
      if (mounted) _showMessage('Ahora sos parte de ${invitation.groupName}');
    } on ObdApiException catch (error) {
      if (!mounted) return;
      ApiMessages.show(
        context,
        error,
        conflict: 'Esa invitación venció o ya fue usada.',
        notFound: 'Esa invitación ya no existe.',
      );
      c.reloadInvitations();
    } finally {
      if (mounted) setState(() => _acceptingId = null);
    }
  }

  Future<void> _shareCurrentCar() async {
    final car = c.car;
    final group = c.group;
    if (car == null || group == null || _sharing) return;

    setState(() => _sharing = true);
    try {
      await c.shareCar(carId: car.id, groupId: group.id);
      if (mounted) _showMessage('${car.name} ahora es de ${group.name}');
    } on ObdApiException catch (error) {
      if (mounted) {
        ApiMessages.show(
          context,
          error,
          notFound: 'Solo el dueño puede compartir ese auto.',
        );
      }
    } finally {
      if (mounted) setState(() => _sharing = false);
    }
  }

  Future<void> _switchGroup() async {
    final t = context.tokens;
    final chosen = await showModalBottomSheet<Group>(
      context: context,
      backgroundColor: t.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 24, 20, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '¿Qué grupo?',
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -.5,
                ),
              ),
              const SizedBox(height: 12),
              for (final g in c.groups)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: IconBadge(
                    icon: AppIcons.shared,
                    color: g.id == c.group?.id ? t.accent : t.muted,
                  ),
                  title: Text(
                    g.name,
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                      color: t.text,
                    ),
                  ),
                  subtitle: Text(
                    '${g.memberCount} miembros · ${g.callerIsAdmin ? 'administrás' : 'miembro'}',
                    style: TextStyle(fontSize: 11, color: t.muted),
                  ),
                  trailing: g.id == c.group?.id
                      ? Icon(AppIcons.check, color: t.accent)
                      : null,
                  onTap: () => Navigator.pop(sheetContext, g),
                ),
              const Divider(),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: IconBadge(icon: AppIcons.group, color: t.muted),
                title: Text(
                  'Crear otro grupo',
                  style: TextStyle(fontWeight: FontWeight.w700, color: t.text),
                ),
                onTap: () {
                  Navigator.pop(sheetContext);
                  _createGroup();
                },
              ),
            ],
          ),
        ),
      ),
    );
    if (chosen != null) c.selectGroup(chosen.id);
  }

  Future<void> _reserve(ReservationsController reservations) async {
    final created = await BookingSheet.show(context, controller: reservations);
    if (created != null && mounted) _showMessage('Reserva confirmada');
  }

  Future<void> _confirmCancel(
    ReservationsController reservations,
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
      if (mounted) _showMessage('Reserva cancelada');
    } on ReservationRejected {
      if (mounted) _showMessage('No se pudo cancelar esa reserva');
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: c,
      builder: (context, _) {
        final group = c.group;
        final invitations = c.invitations;

        final invitationsCard = invitations.isEmpty
            ? null
            : PendingInvitationsCard(
                invitations: invitations,
                acceptingId: _acceptingId,
                onAccept: _accept,
              );

        if (group == null) {
          return RefreshIndicator(
            onRefresh: c.refresh,
            child: ListView(
              key: const ValueKey('shared'),
              padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
              children: [
                const PageHeader(
                  title: 'Compartido',
                  subtitle: 'Todavía no estás en ningún grupo',
                ),
                const SizedBox(height: 18),
                if (invitationsCard != null) ...[
                  invitationsCard,
                  const SizedBox(height: 10),
                ],
                EmptyStateCard(
                  icon: AppIcons.shared,
                  title: 'Armá tu grupo',
                  message:
                      'Creá uno e invitá a tu familia, o mostrá tu código QR '
                      'para que te sumen al de ellos.',
                  actionLabel: 'Crear grupo',
                  actionIcon: AppIcons.group,
                  onAction: _createGroup,
                  secondaryLabel: 'Mi código QR',
                  onSecondary: c.me == null ? null : _showMyQr,
                ),
              ],
            ),
          );
        }

        final car = c.car;
        final members = ApiMappers.members(
          members: c.members,
          me: c.me,
          trips: ApiMappers.thisMonth(c.carTrips),
        );
        final fuel = car == null || !c.carIsInGroup
            ? null
            : ApiMappers.fuelSummary(
                members: members,
                monthTrips: ApiMappers.thisMonth(c.carTrips),
              );
        final groupCars = c.groupCars;
        final reservations = widget.reservations;

        return RefreshIndicator(
          onRefresh: c.refresh,
          child: ListView(
            key: const ValueKey('shared'),
            padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
            children: [
              PageHeader(
                title: 'Compartido',
                subtitle:
                    '${group.name} · ${group.memberCount} '
                    '${group.memberCount == 1 ? 'miembro' : 'miembros'}',
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (c.groups.length > 1)
                      IconButton(
                        tooltip: 'Cambiar de grupo',
                        onPressed: _switchGroup,
                        icon: const Icon(AppIcons.swap),
                      ),
                    if (group.callerIsAdmin)
                      IconButton(
                        tooltip: 'Invitar miembro',
                        onPressed: _invite,
                        icon: const Icon(AppIcons.invite),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: 18),
              if (invitationsCard != null) ...[
                invitationsCard,
                const SizedBox(height: 10),
              ],
              MemberHeader(
                members: members,
                onInvite: group.callerIsAdmin ? _invite : null,
                onShowQr: c.me == null ? null : _showMyQr,
              ),
              const SizedBox(height: 16),
              _MembersCard(members: c.members, controller: c),
              const SizedBox(height: 10),
              _GroupCarsCard(
                group: group,
                cars: groupCars,
                selectedCar: car,
                canShareSelected:
                    car != null && !c.carIsInGroup && c.carIsSurelyMine,
                sharing: _sharing,
                onShare: _shareCurrentCar,
                onSelectCar: (chosen) => c.selectCar(chosen.id),
              ),
              if (fuel != null) ...[
                const SizedBox(height: 10),
                FuelSummaryCard(data: fuel),
              ],
              if (reservations != null && car != null && c.carIsInGroup) ...[
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
                            title: 'Esta semana · ${car.name}',
                            actionLabel: 'Reservar',
                            actionIcon: AppIcons.calendar,
                            onAction: () => _reserve(reservations),
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
                                      ? () => _confirmCancel(reservations, r)
                                      : null,
                                  child: ScheduleSlotView(
                                    slot: reservations.slotFor(r),
                                  ),
                                ),
                              ),
                            ),
                          Padding(
                            padding: const EdgeInsets.only(top: 10),
                            child: Text(
                              upcoming.any(reservations.isMine)
                                  ? 'Tocá una reserva tuya para cancelarla. '
                                        'Las reservas viven solo en este teléfono por ahora.'
                                  : 'Las reservas viven solo en este teléfono por ahora.',
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
            ],
          ),
        );
      },
    );
  }
}

/// Quién es quién en el grupo, con su rol (`GET /groups/{id}/members`).
class _MembersCard extends StatelessWidget {
  final List<GroupMember> members;
  final HomeController controller;

  const _MembersCard({required this.members, required this.controller});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionLabel('Miembros'),
          const SizedBox(height: 10),
          if (members.isEmpty)
            Text(
              controller.detailsLoading ? 'Cargando…' : 'Sin datos del grupo.',
              style: TextStyle(fontSize: 12, color: t.muted),
            )
          else
            for (var i = 0; i < members.length; i++) ...[
              if (i > 0) const SizedBox(height: 8),
              Row(
                children: [
                  Container(
                    width: 27,
                    height: 27,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: ApiMappers.memberColor(i),
                      shape: BoxShape.circle,
                    ),
                    child: Text(
                      ApiMappers.initialsOf(members[i].name, '?'),
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 9,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                  const SizedBox(width: 9),
                  Expanded(
                    child: TextStack(
                      title: controller.isMe(members[i].userId)
                          ? '${members[i].name} (vos)'
                          : members[i].name,
                      subtitle: members[i].email,
                      titleStyle: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: t.text,
                      ),
                    ),
                  ),
                  Text(
                    members[i].isAdmin ? 'ADMIN' : 'MIEMBRO',
                    style: TextStyle(
                      fontSize: 9,
                      fontWeight: FontWeight.w800,
                      letterSpacing: .6,
                      color: members[i].isAdmin ? t.accent : t.muted,
                    ),
                  ),
                ],
              ),
            ],
        ],
      ),
    );
  }
}

/// Los autos compartidos con el grupo, y el botón para sumar el mío.
class _GroupCarsCard extends StatelessWidget {
  final Group group;
  final List<Car> cars;
  final Car? selectedCar;
  final bool canShareSelected;
  final bool sharing;
  final VoidCallback onShare;
  final ValueChanged<Car> onSelectCar;

  const _GroupCarsCard({
    required this.group,
    required this.cars,
    required this.selectedCar,
    required this.canShareSelected,
    required this.sharing,
    required this.onShare,
    required this.onSelectCar,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionLabel('Autos del grupo'),
          const SizedBox(height: 10),
          if (cars.isEmpty)
            Text(
              'Nadie compartió un auto con ${group.name} todavía.',
              style: TextStyle(fontSize: 12, color: t.muted),
            )
          else
            for (var i = 0; i < cars.length; i++) ...[
              if (i > 0) const SizedBox(height: 8),
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: cars[i].id == selectedCar?.id
                    ? null
                    : () => onSelectCar(cars[i]),
                child: Row(
                  children: [
                    IconBadge(
                      icon: AppIcons.car,
                      color: cars[i].id == selectedCar?.id ? t.accent : t.muted,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: TextStack(
                        title: cars[i].name,
                        subtitle: [
                          cars[i].model.label,
                          if (cars[i].licensePlate != null)
                            cars[i].licensePlate!,
                        ].join(' · '),
                        titleStyle: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                          color: t.text,
                        ),
                      ),
                    ),
                    if (cars[i].id == selectedCar?.id)
                      Icon(AppIcons.check, size: 18, color: t.accent)
                    else
                      Icon(AppIcons.arrow, size: 18, color: t.muted),
                  ],
                ),
              ),
            ],
          if (canShareSelected) ...[
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: sharing ? null : onShare,
                icon: sharing
                    ? const SizedBox(
                        width: 14,
                        height: 14,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(AppIcons.share, size: 16),
                label: Text('Compartir ${selectedCar!.name} con el grupo'),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
