import 'package:obd_app/models/member_data.dart';

class FuelSummaryData {
  final String periodLabel;
  final double liters;
  final String total;
  final List<MemberData> members;

  const FuelSummaryData({
    required this.periodLabel,
    required this.liters,
    required this.total,
    required this.members,
  });
}

class TripData {
  final String day;
  final String route;
  final String distance;
  final String duration;
  final List<MemberData> drivers;

  const TripData({
    required this.day,
    required this.route,
    required this.distance,
    required this.duration,
    required this.drivers,
  });
}

class ActivitySummaryData {
  final String distance;
  final String drivingTime;
  final String fuel;
  final String mostVisited;
  final List<double> consumption;
  final List<MemberDistance> driverDistances;

  const ActivitySummaryData({
    required this.distance,
    required this.drivingTime,
    required this.fuel,
    required this.mostVisited,
    required this.consumption,
    required this.driverDistances,
  });
}

class MemberDistance {
  final MemberData member;
  final String distance;

  const MemberDistance(this.member, this.distance);
}
