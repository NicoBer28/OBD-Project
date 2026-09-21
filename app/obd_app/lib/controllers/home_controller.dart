import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:obd_app/data/api/obd_api.dart';

/// En qué punto está la carga inicial de [HomeController].
enum HomeStatus { loading, ready, failed }

/// Todo lo que las pestañas de [MainScreen] necesitan de la API, en un solo
/// lugar.
///
/// Sigue el mismo patrón que `ReservationsController`: un `ChangeNotifier`
/// que las pantallas escuchan con `ListenableBuilder`. Guarda el estado
/// (perfil, autos, grupos, viajes…) y expone las acciones; cada acción llama
/// al endpoint, actualiza el estado local con lo que el servidor devolvió y
/// notifica. Los errores se propagan tal cual (`ObdApiException`) para que la
/// pantalla que disparó la acción decida cómo mostrarlos.
///
/// Hay **un auto seleccionado** (`car`) y **un grupo seleccionado** (`group`).
/// Las pestañas Auto y Actividad giran alrededor del primero; Compartido,
/// alrededor del segundo. Al elegir un auto, el grupo pasa a ser el grupo con
/// el que ese auto está compartido, si tiene uno.
class HomeController extends ChangeNotifier {
  HomeController({ObdApi? api}) : _api = api ?? ObdApi.instance;

  final ObdApi _api;

  HomeStatus status = HomeStatus.loading;

  /// Por qué falló la carga inicial (solo con [HomeStatus.failed]).
  Object? loadError;

  /// True mientras un `refresh()` está en vuelo (la pantalla ya tiene datos).
  bool refreshing = false;

  UserProfile? me;
  List<Car> cars = const [];
  List<Group> groups = const [];
  List<PendingInvitation> invitations = const [];

  String? _selectedCarId;
  String? _selectedGroupId;

  // Detalle del auto / grupo seleccionados. Se cargan aparte porque cambian
  // cada vez que se elige otro auto u otro grupo.
  List<Trip> carTrips = const [];
  Trip? activeTrip;
  Device? device;
  List<GroupMember> members = const [];
  bool detailsLoading = false;

  List<CarModel>? _models;
  bool _disposed = false;

  // ---------------------------------------------------------------------------
  // Lecturas derivadas
  // ---------------------------------------------------------------------------

  Car? get car {
    final id = _selectedCarId;
    if (id == null) return null;
    for (final c in cars) {
      if (c.id == id) return c;
    }
    return null;
  }

  Group? get group {
    final id = _selectedGroupId;
    if (id == null) return null;
    for (final g in groups) {
      if (g.id == id) return g;
    }
    return null;
  }

  bool get hasCars => cars.isNotEmpty;
  bool get hasGroups => groups.isNotEmpty;

  /// Los autos compartidos con el grupo seleccionado, sacados de la lista que
  /// ya tenemos (es el mismo predicado que usa `GET /groups/{id}/cars`).
  List<Car> get groupCars {
    final g = group;
    if (g == null) return const [];
    return cars.where((c) => c.group?.id == g.id).toList(growable: false);
  }

  /// ¿El auto seleccionado está compartido con el grupo seleccionado?
  bool get carIsInGroup =>
      car != null && group != null && car!.group?.id == group!.id;

  /// Un auto sin grupo solo puede verlo su dueño, así que si está en mi lista
  /// y no tiene grupo, es mío. Con grupo no se puede saber sin probar.
  bool get carIsSurelyMine => car != null && car!.group == null;

  bool isMe(String userId) => me?.id == userId;

  /// ¿El viaje abierto lo estoy manejando yo?
  bool get activeTripIsMine =>
      activeTrip != null && me != null && activeTrip!.driverId == me!.id;

  /// Nombre de pila para mostrar junto a un viaje. Yo soy "Vos", como en el
  /// resto de la app; un conductor que no está en el grupo (se fue, o el auto
  /// se dejó de compartir) queda como "Miembro".
  String driverName(String userId) {
    if (isMe(userId)) return 'Vos';
    for (final m in members) {
      if (m.userId == userId) return m.name;
    }
    return 'Miembro';
  }

  // ---------------------------------------------------------------------------
  // Carga
  // ---------------------------------------------------------------------------

  /// Primera carga: perfil, autos, grupos e invitaciones en paralelo, y
  /// después el detalle del auto y grupo elegidos.
  Future<void> load() async {
    status = HomeStatus.loading;
    loadError = null;
    _notify();

    try {
      await _loadEverything();
      status = HomeStatus.ready;
      _notify();
    } on ObdApiException catch (error) {
      status = HomeStatus.failed;
      loadError = error;
      _notify();
      return;
    }

    await _loadDetails();
  }

  /// Vuelve a pedir todo sin pasar por la pantalla de carga (pull to
  /// refresh). Si falla, deja los datos que ya había.
  Future<void> refresh() async {
    if (refreshing) return;
    refreshing = true;
    _notify();
    try {
      await _loadEverything();
      await _loadDetails(notifyStart: false);
    } on ObdApiException {
      // Los datos viejos siguen siendo mejores que nada.
    } finally {
      refreshing = false;
      _notify();
    }
  }

  Future<void> _loadEverything() async {
    final results = await Future.wait<Object>([
      _api.users.me(),
      _api.cars.list(),
      _api.groups.list(),
      _api.invitations.pending(),
    ]);

    me = results[0] as UserProfile;
    cars = results[1] as List<Car>;
    groups = results[2] as List<Group>;
    invitations = results[3] as List<PendingInvitation>;

    // El auto elegido puede haber desaparecido (lo dejaron de compartir).
    if (car == null) _selectedCarId = cars.isEmpty ? null : cars.first.id;
    _pickGroupForCar();
  }

  /// El grupo "natural" del auto elegido, o el primero si el auto no está
  /// compartido. Nunca cambia un grupo elegido a mano que siga existiendo.
  void _pickGroupForCar() {
    final carGroup = car?.group?.id;
    if (carGroup != null && groups.any((g) => g.id == carGroup)) {
      _selectedGroupId = carGroup;
      return;
    }
    if (group == null) {
      _selectedGroupId = groups.isEmpty ? null : groups.first.id;
    }
  }

  Future<void> _loadDetails({bool notifyStart = true}) async {
    final c = car;
    final g = group;

    if (notifyStart) {
      detailsLoading = true;
      _notify();
    }

    try {
      final futures = <Future<Object?>>[
        if (c != null) _api.trips.forCar(c.id),
        if (c != null) _api.trips.active(c.id),
        if (c != null) _api.devices.forCarOrNull(c.id),
        if (g != null) _api.groups.members(g.id),
      ];
      final results = await Future.wait(futures);

      // Si mientras tanto se eligió otro auto/grupo, esta respuesta ya no
      // describe lo que la pantalla muestra.
      var i = 0;
      if (c != null) {
        final trips = results[i++] as List<Trip>;
        final active = results[i++] as Trip?;
        final paired = results[i++] as Device?;
        if (c.id == _selectedCarId) {
          carTrips = trips;
          activeTrip = active;
          device = paired;
        }
      } else {
        carTrips = const [];
        activeTrip = null;
        device = null;
      }
      if (g != null) {
        final list = results[i++] as List<GroupMember>;
        if (g.id == _selectedGroupId) members = list;
      } else {
        members = const [];
      }
    } on ObdApiException {
      // El detalle es secundario: la pantalla sigue con lo que tenía.
    } finally {
      detailsLoading = false;
      _notify();
    }
  }

  // ---------------------------------------------------------------------------
  // Selección
  // ---------------------------------------------------------------------------

  Future<void> selectCar(String carId) async {
    if (carId == _selectedCarId) return;
    _selectedCarId = carId;
    carTrips = const [];
    activeTrip = null;
    device = null;
    _pickGroupForCar();
    _notify();
    await _loadDetails();
  }

  Future<void> selectGroup(String groupId) async {
    if (groupId == _selectedGroupId) return;
    _selectedGroupId = groupId;
    members = const [];
    _notify();
    await _loadDetails();
  }

  /// Vuelve a pedir solo el auto elegido — después de una subida de telemetría
  /// que movió el snapshot, por ejemplo.
  Future<void> refreshCar() async {
    final c = car;
    if (c == null) return;
    try {
      _replaceCar(await _api.cars.byId(c.id));
      _notify();
    } on ObdApiException {
      // Se reintenta en el próximo refresh.
    }
  }

  // ---------------------------------------------------------------------------
  // Autos
  // ---------------------------------------------------------------------------

  /// El catálogo de modelos, pedido una sola vez.
  Future<List<CarModel>> models() async => _models ??= await _api.models.list();

  Future<Car> createCar({
    required String name,
    required String modelId,
    String? licensePlate,
    int? mileage,
  }) async {
    final created = await _api.cars.create(
      name: name,
      modelId: modelId,
      licensePlate: licensePlate,
      mileage: mileage,
    );
    cars = [...cars, created]..sort((a, b) => a.name.compareTo(b.name));
    _selectedCarId = created.id;
    carTrips = const [];
    activeTrip = null;
    device = null;
    _pickGroupForCar();
    _notify();
    await _loadDetails();
    return created;
  }

  Future<Car> shareCar({required String carId, required String groupId}) async {
    final updated = await _api.cars.share(carId: carId, groupId: groupId);
    _replaceCar(updated);
    if (carId == _selectedCarId) {
      _selectedGroupId = groupId;
      _notify();
      await _loadDetails();
    } else {
      _notify();
    }
    return updated;
  }

  Future<void> unshareCar(String carId) async {
    await _api.cars.unshare(carId);
    // `Car` no tiene copyWith: pedimos el auto de nuevo para que `group`
    // quede en null con la misma forma que lo lista el servidor.
    try {
      _replaceCar(await _api.cars.byId(carId));
    } on ObdApiException {
      // Lo veremos bien en el próximo refresh.
    }
    _notify();
  }

  // ---------------------------------------------------------------------------
  // Dongle
  // ---------------------------------------------------------------------------

  /// El dongle de cualquier auto de la lista; el del auto elegido ya está
  /// en [device], los demás se piden.
  Future<Device?> deviceFor(String carId) {
    if (carId == _selectedCarId && !detailsLoading) {
      return Future.value(device);
    }
    return _api.devices.forCarOrNull(carId);
  }

  Future<Device> pairDevice({
    required String carId,
    required String serial,
  }) async {
    final paired = await _api.devices.pair(carId: carId, serial: serial);
    if (carId == _selectedCarId) device = paired;
    _notify();
    return paired;
  }

  Future<void> unpairDevice(String carId) async {
    await _api.devices.unpair(carId);
    if (carId == _selectedCarId) device = null;
    _notify();
  }

  // ---------------------------------------------------------------------------
  // Grupos e invitaciones
  // ---------------------------------------------------------------------------

  Future<Group> createGroup(String name) async {
    final created = await _api.groups.create(name: name);
    groups = [...groups, created]..sort((a, b) => a.name.compareTo(b.name));
    _selectedGroupId = created.id;
    members = const [];
    _notify();
    await _loadDetails();
    return created;
  }

  Future<Invitation> invite(String email) {
    final g = group;
    if (g == null) throw StateError('No hay un grupo seleccionado');
    return _api.invitations.invite(groupId: g.id, email: email);
  }

  /// Acepta y pasa a mostrar ese grupo, que aparece en `GET /groups` al
  /// instante.
  Future<void> acceptInvitation(PendingInvitation invitation) async {
    await _api.invitations.accept(invitation.id);
    invitations = invitations
        .where((i) => i.id != invitation.id)
        .toList(growable: false);
    try {
      groups = await _api.groups.list();
      // Unirse a un grupo puede traer autos compartidos nuevos.
      cars = await _api.cars.list();
    } on ObdApiException {
      // Se completa en el próximo refresh.
    }
    _selectedGroupId = invitation.groupId;
    if (car == null && cars.isNotEmpty) _selectedCarId = cars.first.id;
    _notify();
    await _loadDetails();
  }

  Future<void> reloadInvitations() async {
    try {
      invitations = await _api.invitations.pending();
      _notify();
    } on ObdApiException {
      // No es crítico.
    }
  }

  // ---------------------------------------------------------------------------
  // Viajes
  // ---------------------------------------------------------------------------

  Future<Trip> startTrip({int? initialFuel}) async {
    final c = car;
    if (c == null) throw StateError('No hay un auto seleccionado');
    final trip = await _api.trips.start(carId: c.id, initialFuel: initialFuel);
    activeTrip = trip;
    carTrips = [trip, ...carTrips];
    _notify();
    return trip;
  }

  Future<Trip> finishTrip({int? finalFuel, int? distance}) async {
    final open = activeTrip;
    if (open == null) throw StateError('No hay un viaje abierto');
    final finished = await _api.trips.finish(
      open.id,
      tripFinalFuel: finalFuel,
      tripDistance: distance,
    );
    activeTrip = null;
    carTrips = [for (final t in carTrips) t.id == finished.id ? finished : t];
    _notify();
    return finished;
  }

  Future<void> cancelTrip() async {
    final open = activeTrip;
    if (open == null) return;
    await _api.trips.cancel(open.id);
    activeTrip = null;
    carTrips = carTrips.where((t) => t.id != open.id).toList(growable: false);
    _notify();
  }

  /// Vuelve a preguntar quién tiene el auto — otro miembro pudo haber
  /// arrancado o terminado un viaje desde su teléfono.
  Future<void> refreshActiveTrip() async {
    final c = car;
    if (c == null) return;
    try {
      final active = await _api.trips.active(c.id);
      if (c.id == _selectedCarId) {
        activeTrip = active;
        _notify();
      }
    } on ObdApiException {
      // No es crítico.
    }
  }

  // ---------------------------------------------------------------------------

  void _replaceCar(Car updated) {
    cars = [for (final c in cars) c.id == updated.id ? updated : c];
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
