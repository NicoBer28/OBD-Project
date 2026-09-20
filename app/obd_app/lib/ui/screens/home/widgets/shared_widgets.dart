import 'package:flutter/material.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/models/models.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

class MemberHeader extends StatelessWidget {
  final List<MemberData> members;
  final VoidCallback onInvite;

  const MemberHeader({
    super.key,
    required this.members,
    required this.onInvite,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: AvatarStack(members: members)),
        OutlinedButton.icon(
          onPressed: onInvite,
          icon: const Icon(AppIcons.invite, size: 16),
          label: const Text('Invitar'),
        ),
      ],
    );
  }
}

class AvatarStack extends StatelessWidget {
  final List<MemberData> members;

  const AvatarStack({super.key, required this.members});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 24.0 + (members.length - 1) * 16.0,
      height: 24.0,
      child: Stack(
        children: [
          for (int i = 0; i < members.length; i++)
            Positioned(
              left: i * 16.0,
              child: Container(
                width: 24,
                height: 24,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: members[i].color,
                  border: Border.all(color: context.tokens.surface, width: 2),
                ),
                alignment: Alignment.center,
                child: Text(
                  members[i].initials,
                  style: const TextStyle(
                    fontSize: 9,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _Avatar extends StatelessWidget {
  final String initials;
  final Color color;
  final double size;

  const _Avatar({required this.initials, required this.color, this.size = 34});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
        border: Border.all(color: t.surface, width: size >= 30 ? 2 : 1.5),
      ),
      child: Text(
        initials,
        style: TextStyle(
          color: Colors.white,
          fontSize: size * .30,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }
}

class FuelSummaryCard extends StatelessWidget {
  final FuelSummaryData data;

  const FuelSummaryCard({super.key, required this.data});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'NAFTA · ${data.periodLabel.toUpperCase()}',
                style: TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                  letterSpacing: .7,
                  color: t.muted,
                ),
              ),
              Text(
                '${data.liters.toStringAsFixed(0)} L',
                style: TextStyle(fontSize: 11, color: t.muted),
              ),
            ],
          ),
          const SizedBox(height: 7),
          Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text(
                data.total,
                style: TextStyle(
                  fontSize: 32,
                  fontWeight: FontWeight.w800,
                  letterSpacing: -1,
                  color: t.text,
                ),
              ),
              const SizedBox(width: 7),
              Text(
                'total del grupo',
                style: TextStyle(fontSize: 11, color: t.muted),
              ),
            ],
          ),
          const SizedBox(height: 11),
          ClipRRect(
            borderRadius: BorderRadius.circular(99),
            child: SizedBox(
              height: 9,
              child: Row(
                children: [
                  for (final member in data.members)
                    if (member.fuelShare > 0) // Guard against flex: 0
                      Expanded(
                        flex: (member.fuelShare * 100).round(),
                        child: ColoredBox(color: member.color),
                      ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 13),
          for (final member in data.members)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                children: [
                  _Avatar(
                    initials: member.initials,
                    color: member.color,
                    size: 27,
                  ),
                  const SizedBox(width: 7),
                  Expanded(
                    child: Text(
                      member.name,
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: t.text,
                      ),
                    ),
                  ),
                  Text(
                    '${(member.fuelShare * 100).round()}%',
                    style: TextStyle(fontSize: 11, color: t.muted),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    member.fuelAmount,
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w800,
                      color: t.text,
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class BalanceCard extends StatelessWidget {
  final VoidCallback onSettle;

  const BalanceCard({super.key, required this.onSettle});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return SectionCard(
      padding: const EdgeInsets.all(15),
      child: Row(
        children: [
          Expanded(
            child: TextStack(
              title: 'Tu saldo pendiente',
              subtitle: 'Debés \$12.400',
              titleStyle: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: t.muted,
              ),
              subtitleStyle: TextStyle(
                fontSize: 21,
                fontWeight: FontWeight.w800,
                color: t.accent2,
              ),
              spacing: 4,
            ),
          ),
          FilledButton(onPressed: onSettle, child: const Text('Saldar')),
        ],
      ),
    );
  }
}

class ScheduleSlotView extends StatelessWidget {
  final ScheduleSlot slot;

  const ScheduleSlotView({super.key, required this.slot});

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    final textColor = slot.available ? t.muted : Colors.white;

    return Row(
      children: [
        SizedBox(
          width: 52,
          child: Text(
            '${slot.day}\n${slot.time}',
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w800,
              color: t.muted,
              height: 1.25,
            ),
          ),
        ),
        Expanded(
          child: Container(
            height: 31,
            alignment: Alignment.centerLeft,
            padding: const EdgeInsets.symmetric(horizontal: 10),
            decoration: BoxDecoration(
              color: slot.color,
              borderRadius: BorderRadius.circular(9),
            ),
            child: Text(
              slot.person,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w800,
                color: textColor,
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class LocationField extends StatelessWidget {
  final String label;
  final String hint;
  final IconData icon;

  const LocationField({
    super.key,
    required this.label,
    required this.hint,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return TextField(
      decoration: InputDecoration(
        labelText: label,
        hintText: hint,
        prefixIcon: Icon(icon, color: t.accent),
      ),
    );
  }
}
