import 'package:flutter/material.dart';
import 'package:obd_app/models/models.dart';
import 'package:obd_app/ui/screens/home/widgets/activity_widgets.dart';
import 'package:obd_app/ui/widgets/widgets.dart';

/// ---------------------------------------------------------------------------
/// Activity
/// ---------------------------------------------------------------------------

class ActivityTab extends StatelessWidget {
  final CarData carData;
  final List<TripData> trips;
  final ActivitySummaryData activityData;
  final bool showSummary;
  final String period;
  final ValueChanged<bool> onShowSummaryChanged;
  final ValueChanged<String> onPeriodChanged;

  const ActivityTab({
    super.key,
    required this.carData,
    required this.trips,
    required this.activityData,
    required this.showSummary,
    required this.period,
    required this.onShowSummaryChanged,
    required this.onPeriodChanged,
  });

  @override
  Widget build(BuildContext context) {
    return ListView(
      key: const ValueKey('activity'),
      padding: const EdgeInsets.fromLTRB(16, 18, 16, 28),
      children: [
        PageHeader(
          title: 'Actividad',
          subtitle: '${carData.name} · últimos 30 días',
        ),
        const SizedBox(height: 18),
        SegmentedControl(
          labels: const ['Viajes', 'Resumen'],
          selectedIndex: showSummary ? 1 : 0,
          onChanged: (index) => onShowSummaryChanged(index == 1),
        ),
        if (showSummary) ...[
          const SizedBox(height: 12),
          PeriodPicker(value: period, onChanged: onPeriodChanged),
          const SizedBox(height: 16),
          SummaryContent(data: activityData),
        ] else ...[
          const SizedBox(height: 18),
          TripsContent(trips: trips),
        ],
      ],
    );
  }
}
