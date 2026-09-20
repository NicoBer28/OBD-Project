import 'package:flutter/material.dart';
import 'package:obd_app/core/theme/app_theme.dart';

class IconBadge extends StatelessWidget {
  final IconData icon;
  final Color color;

  const IconBadge({super.key, required this.icon, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 38,
      height: 38,
      decoration: BoxDecoration(
        color: color.withValues(alpha: .10),
        shape: BoxShape.circle,
      ),
      child: Icon(icon, size: 19, color: color),
    );
  }
}

class ProfileAvatar extends StatelessWidget {
  final String initials;
  final VoidCallback? onTap;
  final double radius;

  const ProfileAvatar({
    super.key,
    required this.initials,
    this.onTap,
    this.radius = 20,
  });

  @override
  Widget build(BuildContext context) {
    final t = context.tokens;

    final avatar = CircleAvatar(
      radius: radius,
      backgroundColor: t.accent,
      child: Text(
        initials,
        style: TextStyle(
          color: Colors.white,
          fontSize: radius * .55,
          fontWeight: FontWeight.w800,
        ),
      ),
    );

    return onTap == null
        ? avatar
        : Semantics(
            button: true,
            label: 'Abrir perfil',
            child: GestureDetector(onTap: onTap, child: avatar),
          );
  }
}
