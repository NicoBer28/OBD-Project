import 'package:http/http.dart' as http;
import 'package:obd_app/data/api/api_client.dart';
import 'package:obd_app/data/api/api_config.dart';
import 'package:obd_app/data/api/endpoints/auth_api.dart';
import 'package:obd_app/data/api/endpoints/cars_api.dart';
import 'package:obd_app/data/api/endpoints/devices_api.dart';
import 'package:obd_app/data/api/endpoints/groups_api.dart';
import 'package:obd_app/data/api/endpoints/invitations_api.dart';
import 'package:obd_app/data/api/endpoints/models_api.dart';
import 'package:obd_app/data/api/endpoints/telemetry_api.dart';
import 'package:obd_app/data/api/endpoints/trips_api.dart';
import 'package:obd_app/data/api/endpoints/users_api.dart';
import 'package:obd_app/data/api/session.dart';
import 'package:obd_app/data/api/session_store.dart';

export 'package:obd_app/data/api/api_client.dart' show ApiClient, ApiResponse;
export 'package:obd_app/data/api/api_config.dart';
export 'package:obd_app/data/api/api_exception.dart';
export 'package:obd_app/data/api/endpoints/auth_api.dart';
export 'package:obd_app/data/api/endpoints/cars_api.dart';
export 'package:obd_app/data/api/endpoints/devices_api.dart';
export 'package:obd_app/data/api/endpoints/groups_api.dart';
export 'package:obd_app/data/api/endpoints/invitations_api.dart';
export 'package:obd_app/data/api/endpoints/models_api.dart';
export 'package:obd_app/data/api/endpoints/telemetry_api.dart';
export 'package:obd_app/data/api/endpoints/trips_api.dart';
export 'package:obd_app/data/api/endpoints/users_api.dart';
export 'package:obd_app/data/api/models/api_models.dart';
export 'package:obd_app/data/api/session.dart';
export 'package:obd_app/data/api/session_store.dart';

/// The whole OBD API, grouped by resource.
///
/// ```dart
/// final api = ObdApi.instance;
///
/// await api.auth.login(userEmail: 'ada@example.com', userPassword: '…');
/// final cars = await api.cars.list();
/// final trip = await api.trips.start(carId: cars.first.id);
/// ```
///
/// One import (`package:obd_app/data/api/obd_api.dart`) brings in this class,
/// every model and every error type.
///
/// [instance] is a process-wide singleton because the app has no dependency
/// injection and the session has to be shared by every screen. Tests build
/// their own with a fake `http.Client` and never touch it.
class ObdApi {
  ObdApi({ApiConfig? config, http.Client? httpClient, ObdSession? session})
    : this.fromClient(
        ApiClient(config: config, httpClient: httpClient, session: session),
      );

  ObdApi.fromClient(this.client)
    : auth = AuthApi(client),
      users = UsersApi(client),
      cars = CarsApi(client),
      models = ModelsApi(client),
      devices = DevicesApi(client),
      groups = GroupsApi(client),
      invitations = InvitationsApi(client),
      trips = TripsApi(client),
      telemetry = TelemetryApi(client);

  /// The shared instance the UI uses. Its session is backed by the OS keystore,
  /// so the refresh token survives closing the app (see `main()` for the
  /// restore step). Instances built by tests have no store and keep nothing.
  static final ObdApi instance = ObdApi(
    session: ObdSession(store: SecureSessionStore()),
  );

  final ApiClient client;

  /// register · login · refresh · logout
  final AuthApi auth;

  /// GET /users/me
  final UsersApi users;

  /// create · list · byId · share · unshare · forGroup
  final CarsApi cars;

  /// The car model catalog: list · create (admin)
  final ModelsApi models;

  /// Dongle pairing: pair · forCar · unpair · resolve
  final DevicesApi devices;

  /// create · list · members
  final GroupsApi groups;

  /// invite · pending · accept
  final InvitationsApi invitations;

  /// start · mine · forCar · active · finish · cancel
  final TripsApi trips;

  /// upload · history · readAll
  final TelemetryApi telemetry;

  /// Who is signed in. Listen to it to react to logout.
  ObdSession get session => client.session;

  /// True once `auth.login` (or `register`) has succeeded.
  bool get isAuthenticated => session.isAuthenticated;

  /// Where this instance is pointed.
  ApiConfig get config => client.config;

  /// Wakes a sleeping deployment (see `ApiClient.warmUp`). Safe to call often.
  Future<void> warmUp() => client.warmUp();

  void dispose() => client.close();
}
