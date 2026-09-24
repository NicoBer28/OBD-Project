import 'package:flutter/material.dart';

class ScheduleSlot {
  final String day;
  final String time;
  final String person;
  final Color color;
  final bool available;

  const ScheduleSlot({
    required this.day,
    required this.time,
    required this.person,
    required this.color,
    this.available = false,
  });
}
