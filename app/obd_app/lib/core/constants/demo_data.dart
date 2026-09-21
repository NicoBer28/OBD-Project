import 'package:obd_app/core/theme/app_theme.dart';
import 'package:obd_app/models/models.dart';

/// ---------------------------------------------------------------------------
/// Demo data
///
/// Keeping this separate from the UI makes it easy to replace later with
/// API/database/OBD data without changing the widgets.
/// ---------------------------------------------------------------------------

class DemoData {
  static List<MemberData> get members => [
    const MemberData(
      id: 'u1',
      initials: 'LM',
      name: 'Vos',
      color: AppPalette.member1,
      fuelShare: .42,
      fuelAmount: r'$35.364',
    ),
    const MemberData(
      id: 'u2',
      initials: 'SM',
      name: 'Sofía',
      color: AppPalette.member2,
      fuelShare: .31,
      fuelAmount: r'$26.102',
    ),
    const MemberData(
      id: 'u3',
      initials: 'MG',
      name: 'Martín',
      color: AppPalette.member3,
      fuelShare: .18,
      fuelAmount: r'$15.156',
    ),
    const MemberData(
      id: 'u4',
      initials: 'PA',
      name: 'Papá',
      color: AppPalette.member4,
      fuelShare: .09,
      fuelAmount: r'$7.578',
    ),
  ];

  static const car = CarData(
    id: 'car-1',
    name: 'Golf GTI',
    brand: 'Volkswagen',
    plate: 'AB 123 CD',
    fuelPercent: 88,
    fuelCapacityLiters: 52,
    kmPerLiter: 9,
    batteryVoltage: 12.4,
    service: '2.100 km',
    parkingAddress: 'Av. Corrientes 1234',
    parkingMeta: 'a 600 m tuyo',
    parkedBy: 'Sofía',
  );

  static FuelSummaryData get fuel => FuelSummaryData(
    periodLabel: 'septiembre',
    liters: 142,
    total: r'$84.200',
    members: members,
  );

  static List<TripData> get trips {
    final people = members;

    return [
      TripData(
        day: 'Hoy',
        route: 'Casa → Trabajo',
        distance: '12,4 km',
        duration: '18 min',
        drivers: [people[0]],
      ),
      TripData(
        day: 'Ayer',
        route: 'Ruta costera',
        distance: '42,8 km',
        duration: '51 min',
        drivers: [people[1], people[0]],
      ),
      TripData(
        day: 'Dom, 31 ago',
        route: 'Centro → Norte',
        distance: '8,6 km',
        duration: '16 min',
        drivers: [people[2]],
      ),
      TripData(
        day: 'Vie, 29 ago',
        route: 'Belgrano → Palermo',
        distance: '15,7 km',
        duration: '26 min',
        drivers: [people[0], people[1]],
      ),
      TripData(
        day: 'Jue, 28 ago',
        route: 'Trabajo → Gimnasio',
        distance: '6,2 km',
        duration: '14 min',
        drivers: [people[1]],
      ),
      TripData(
        day: 'Mié, 27 ago',
        route: 'Centro → Tigre',
        distance: '31,4 km',
        duration: '43 min',
        drivers: [people[2], people[3]],
      ),
    ];
  }

  static ActivitySummaryData get activity {
    final people = members;

    return ActivitySummaryData(
      distance: '1.284',
      drivingTime: '38 h 20 min',
      fuel: '142',
      mostVisited: 'Centro',
      consumption: [38, 52, 31, 64, 47, 80, 36, 27, 57, 69, 44, 100, 61, 49],
      driverDistances: [
        MemberDistance(people[0], '539 km'),
        MemberDistance(people[1], '398 km'),
        MemberDistance(people[2], '231 km'),
        MemberDistance(people[3], '116 km'),
      ],
    );
  }
}
