import 'package:flutter/material.dart';

class MemberData {
  final String id;
  final String initials;
  final String name;
  final Color color;
  final double fuelShare;
  final String fuelAmount;

  const MemberData({
    required this.id,
    required this.initials,
    required this.name,
    required this.color,
    required this.fuelShare,
    required this.fuelAmount,
  });
}
