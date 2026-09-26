import 'package:flutter/material.dart';

import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/data/api/obd_api.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Sumar una persona — compartido por `StartTripSheet` (todavía no hay
/// `tripId`: cada alta se guarda en una lista local y se manda recién cuando
/// el viaje arranca) y `TripPeopleSheet` (el viaje ya existe: cada alta llama
/// a la API al toque).
///
/// El widget no sabe cuál de los dos casos es — solo junta la elección
/// (miembro del grupo / correo o QR / invitado sin cuenta) y avisa por el
/// callback correspondiente. Qué hacer con eso lo decide quien lo usa.
/// ---------------------------------------------------------------------------

enum AddPersonMode { none, group, email, guest }

class AddPersonSection extends StatelessWidget {
  final AddPersonMode mode;
  final ValueChanged<AddPersonMode> onModeChanged;
  final bool busy;

  /// A quién le ofrece elegir en "Del grupo" — ya filtrado por quien llama
  /// (sin quien maneja, sin quien ya esté sumado/en espera).
  final List<GroupMember> selectableMembers;
  final ValueChanged<GroupMember> onAddFromGroup;

  final TextEditingController guestNameController;
  final VoidCallback onAddGuest;

  final TextEditingController inviteEmailController;
  final TextEditingController inviteNameController;
  final VoidCallback onScan;
  final VoidCallback onInvite;

  const AddPersonSection({
    super.key,
    required this.mode,
    required this.onModeChanged,
    required this.busy,
    required this.selectableMembers,
    required this.onAddFromGroup,
    required this.guestNameController,
    required this.onAddGuest,
    required this.inviteEmailController,
    required this.inviteNameController,
    required this.onScan,
    required this.onInvite,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _ModeChip(
              label: 'Del grupo',
              icon: AppIcons.shared,
              mode: AddPersonMode.group,
              current: mode,
              onTap: onModeChanged,
            ),
            _ModeChip(
              label: 'Correo o QR',
              icon: AppIcons.qr,
              mode: AddPersonMode.email,
              current: mode,
              onTap: onModeChanged,
            ),
            _ModeChip(
              label: 'Invitado',
              icon: AppIcons.guest,
              mode: AddPersonMode.guest,
              current: mode,
              onTap: onModeChanged,
            ),
          ],
        ),
        if (mode != AddPersonMode.none) ...[
          const SizedBox(height: 12),
          SectionCard(child: _form(context, t)),
        ],
      ],
    );
  }

  Widget _form(BuildContext context, AppTokens t) {
    switch (mode) {
      case AddPersonMode.none:
        return const SizedBox.shrink();

      case AddPersonMode.group:
        if (selectableMembers.isEmpty) {
          return Text(
            'No hay más miembros del grupo para sumar. Probá "Correo o QR" si es alguien de afuera.',
            style: TextStyle(fontSize: 12, color: t.muted),
          );
        }
        return Column(
          children: [
            for (var i = 0; i < selectableMembers.length; i++) ...[
              if (i > 0) const Divider(height: 18),
              Row(
                children: [
                  Expanded(
                    child: TextStack(title: selectableMembers[i].name, subtitle: selectableMembers[i].email),
                  ),
                  TextButton(
                    onPressed: busy ? null : () => onAddFromGroup(selectableMembers[i]),
                    child: const Text('Sumar'),
                  ),
                ],
              ),
            ],
          ],
        );

      case AddPersonMode.guest:
        return Row(
          children: [
            Expanded(
              child: TextField(
                controller: guestNameController,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(
                  labelText: 'Nombre (opcional)',
                  hintText: 'Dejalo vacío para solo sumar una cabeza',
                  isDense: true,
                ),
              ),
            ),
            const SizedBox(width: 10),
            FilledButton(onPressed: busy ? null : onAddGuest, child: const Text('Sumar')),
          ],
        );

      case AddPersonMode.email:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Material(
              color: t.accent.withValues(alpha: .08),
              borderRadius: BorderRadius.circular(12),
              child: InkWell(
                onTap: busy ? null : onScan,
                borderRadius: BorderRadius.circular(12),
                child: Padding(
                  padding: const EdgeInsets.all(11),
                  child: Row(
                    children: [
                      Icon(AppIcons.scan, size: 19, color: t.accent),
                      const SizedBox(width: 10),
                      const Expanded(child: Text('Escanear su código QR')),
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: inviteEmailController,
              keyboardType: TextInputType.emailAddress,
              decoration: const InputDecoration(labelText: 'Correo', isDense: true),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: inviteNameController,
              textCapitalization: TextCapitalization.words,
              decoration: const InputDecoration(
                labelText: 'Nombre',
                helperText: 'Se usa solo si esa persona no tiene cuenta todavía',
                isDense: true,
              ),
            ),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: FilledButton(onPressed: busy ? null : onInvite, child: const Text('Sumar')),
            ),
          ],
        );
    }
  }
}

class _ModeChip extends StatelessWidget {
  final String label;
  final IconData icon;
  final AddPersonMode mode;
  final AddPersonMode current;
  final ValueChanged<AddPersonMode> onTap;

  const _ModeChip({
    required this.label,
    required this.icon,
    required this.mode,
    required this.current,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;
    final selected = mode == current;

    return ChoiceChip(
      label: Text(label),
      avatar: Icon(icon, size: 16, color: selected ? Colors.white : t.muted),
      selected: selected,
      onSelected: (_) => onTap(mode),
      selectedColor: t.accent,
      labelStyle: TextStyle(
        color: selected ? Colors.white : t.text,
        fontWeight: FontWeight.w700,
        fontSize: 12,
      ),
      side: BorderSide(color: selected ? t.accent : t.border),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(9)),
    );
  }
}
