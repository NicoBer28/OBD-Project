import 'package:flutter/material.dart';
import 'package:obd_app/core/constants/app_icons.dart';
import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/ui/widgets/badges_and_avatars.dart';
import 'package:obd_app/ui/widgets/text_components.dart';

class SettingTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback? onTap;

  const SettingTile({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return ListTile(
      leading: Icon(icon, color: t.muted),
      title: Text(
        title,
        style: TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w700,
          color: t.text,
        ),
      ),
      subtitle: Text(
        subtitle,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 11, color: t.muted),
      ),
      onTap: onTap,
      trailing: Icon(AppIcons.arrow, color: t.muted),
    );
  }
}

class SwitchTile extends StatelessWidget {
  final String title;
  final String subtitle;
  final bool value;
  final ValueChanged<bool> onChanged;

  const SwitchTile({
    super.key,
    required this.title,
    required this.subtitle,
    required this.value,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return SwitchListTile(
      value: value,
      onChanged: onChanged,
      title: Text(title),
      subtitle: Text(subtitle),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 3),
    );
  }
}

class ActionTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  const ActionTile({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    return Material(
      color: t.accent.withValues(alpha: .08),
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.all(13),
          child: Row(
            children: [
              IconBadge(icon: icon, color: t.accent),
              const SizedBox(width: 11),
              Expanded(
                child: TextStack(title: title, subtitle: subtitle),
              ),
              Icon(AppIcons.arrow, color: t.muted),
            ],
          ),
        ),
      ),
    );
  }
}
