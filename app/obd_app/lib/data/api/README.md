# Cliente de la API — `lib/data/api`

Todo lo que la app necesita para hablar con el backend de Spring que vive en
`api/`. Un solo import trae el cliente, los modelos y los errores:

```dart
import 'package:obd_app/data/api/obd_api.dart';
```

La referencia del servidor (qué devuelve cada endpoint y por qué está diseñado
así) es [`api/README.md`](../../../../../api/README.md). Este archivo documenta el
lado Dart: cómo se llama a cada cosa y qué parámetros lleva.

---

## Índice

1. [Configurar a dónde apunta](#1-configurar-a-dónde-apunta)
2. [Cómo funciona](#2-cómo-funciona)
3. [Manejo de errores](#3-manejo-de-errores)
4. [Endpoints](#4-endpoints)
5. [Recetas](#5-recetas)
6. [Qué falta](#6-qué-falta)

---

## 1. Configurar a dónde apunta

La URL base sale de un `--dart-define`, así que el mismo código sirve para la
notebook, el emulador y producción sin tocar fuentes:

```bash
flutter run --dart-define=OBD_API_BASE_URL=http://10.0.2.2:8080
```

| Dónde corre la app | URL base |
|---|---|
| Emulador de Android | `http://10.0.2.2:8080` |
| Simulador de iOS | `http://localhost:8080` |
| Celular físico en la misma Wi-Fi | `http://<ip-de-tu-máquina>:8080` |
| Producción | `https://<host>` |

Sin `--dart-define` el default es `http://192.168.1.19:8080`, que era la IP
hardcodeada que ya tenían las pantallas de login y registro
(`ApiConfig._defaultBaseUrl`). Cambiala ahí si tu LAN usa otra.

> **Android:** el tráfico en texto plano (`http://`) está bloqueado por defecto
> desde Android 9. Para pegarle a un backend local hace falta un
> `network_security_config.xml` que permita cleartext para esa IP. Con `https://`
> no hace falta nada.

Para tests se construye una instancia aparte, sin tocar el singleton:

```dart
final api = ObdApi(
  config: const ApiConfig(baseUrl: 'http://test.local'),
  httpClient: MockClient(...),
);
```

---

## 2. Cómo funciona

```
ObdApi.instance
  ├── .auth .users .cars .models .devices .groups .invitations .trips .telemetry
  ├── .session   -> ObdSession: tokens + usuario logueado (ChangeNotifier)
  └── .client    -> ApiClient: el único lugar que habla HTTP
```

`ApiClient` se ocupa de las cuatro cosas que si no habría que repetir en cada
endpoint:

**1. El bearer.** Todo lo que no sea `auth/*` viaja con
`Authorization: Bearer <accessToken>`. Ningún método de endpoint arma headers.

**2. La cookie de refresh.** La API manda el refresh token como cookie
`httpOnly`, y `package:http` no tiene cookie jar (en el celular no hay
navegador que la guarde). Entonces el cliente lee el `Set-Cookie` de la
respuesta, se guarda el valor en la sesión y lo manda a mano como header
`Cookie` cuando llama a `POST /auth/refresh`.

**3. Re-autenticación silenciosa.** Si una llamada vuelve `401`, el cliente
refresca una vez y la reintenta. El access token dura 15 minutos, así que esto
pasa seguido y el usuario no se entera.

Los refreshes están serializados a propósito: si cinco requests fallan al mismo
tiempo, comparten **un** refresh. Los refresh tokens son de un solo uso, y
presentar uno ya rotado revoca toda la familia y obliga a loguearse de nuevo —
o sea que refrescar cinco veces en paralelo *cerraría* la sesión en vez de
salvarla.

**4. Los errores.** Cualquier 4xx/5xx se convierte en `ApiException` y un host
inalcanzable en `NetworkException`. Ningún método de endpoint mira un status
code.

### La sesión

`ObdSession` guarda el access token, la cookie de refresh, cuándo vence, el
`userId` y el email. Es un `ChangeNotifier`, así que un widget puede escucharlo
con `ListenableBuilder` para reaccionar al logout.

**No se persiste nada en disco.** Cerrar la app desloguea. Agregarlo es una
clase: guardar los tres campos en `flutter_secure_storage` y restaurarlos antes
de `runApp`. Quedó afuera a propósito — un refresh token en
`SharedPreferences` en texto plano es peor que volver a pedir la contraseña.

---

## 3. Manejo de errores

```dart
try {
  await api.trips.start(carId: auto.id);
} on ApiException catch (e) {
  if (e.isConflict) {
    // 409: alguien ya está usando el auto
  } else if (e.isNotFound) {
    // 404: no existe, o no es tuyo — la API no distingue a propósito
  }
} on NetworkException {
  // nunca llegó al servidor
}
```

Las dos derivan de `ObdApiException`, que es `sealed`: un `switch` sobre ella
es exhaustivo, y con `on ObdApiException` se atrapan las dos.

| Getter | Status | Qué significa en esta API |
|---|---|---|
| `isValidation` | 400 | Body mal formado, UUID inválido en la ruta, o validación fallida. Mirá `fieldErrors` |
| `isUnauthorized` | 401 | Sin token, vencido o rechazado |
| `isForbidden` | 403 | Autenticado pero sin permiso. Solo aparece donde el que llama ya sabe que la cosa existe (no es admin del grupo, no es admin de cuenta) |
| `isNotFound` | 404 | **No existe *o* no es tuyo.** La API contesta igual en los dos casos para no confirmarle un id a un desconocido |
| `isConflict` | 409 | Choque de estado: email tomado, patente tomada, auto ya en viaje, invitación ya aceptada, dongle pareado a otro auto |
| `isServerError` | 5xx | Problema del servidor |

En un `400` el servidor manda un mapa campo → mensaje, que llega en
`fieldErrors` y se puede colgar directamente de cada `TextFormField`:

```dart
{'userPassword': 'size must be between 8 and 72'}
```

En un lote de telemetría las claves vienen indexadas:
`readings[2].positionComplete`.

---

## 4. Endpoints

Los 31 endpoints de la API, agrupados igual que en el servidor. Base:
`/api/v1`. Todo menos `auth/*` necesita sesión.

### `api.auth` — autenticación

| Método | Endpoint |
|---|---|
| `register(...)` | `POST /auth/register` |
| `login(...)` | `POST /auth/login` |
| `refresh()` | `POST /auth/refresh` |
| `logout()` | `POST /auth/logout` |

```dart
await api.auth.register(
  userName: 'Ada',              // requerido, nombre de pila
  userLastName: 'Lovelace',     // requerido
  userEmail: 'ada@example.com', // requerido, email válido
  userPassword: 'supersecret1', // requerido, 8–72 caracteres
  userPhone: '+54 11 5555 5555' // opcional, se omite si viene vacío
);
```

`register` **además loguea**: devuelve los mismos tokens que `login`, así que
después de crear la cuenta se puede entrar directo.

```dart
await api.auth.login(
  userEmail: 'ada@example.com',  // requerido
  userPassword: 'supersecret1',  // requerido, 8–72
);
```

Errores: `login` tira `isUnauthorized` con credenciales malas; `register` tira
`isConflict` si el email ya existe.

`refresh()` casi nunca se llama a mano — el cliente lo hace solo ante un `401`.
Devuelve `false` cuando la sesión terminó de verdad (ahí conviene mandar al
usuario al login).

`logout()` revoca **todos** los refresh tokens del usuario en el servidor y
limpia la sesión local pase lo que pase.

### `api.users` — perfil

| Método | Endpoint |
|---|---|
| `me()` | `GET /users/me` |

Sin parámetros: el usuario es el dueño del token. No existe `GET /users/{id}`,
a propósito. Devuelve `UserProfile` con `userName` (nombre de pila),
`userLastName`, `userEmail`, `userPhone`, más `fullName` e `initials` para la UI.

### `api.cars` — autos

| Método | Endpoint |
|---|---|
| `create(...)` | `POST /cars` |
| `list()` | `GET /cars` |
| `byId(carId)` | `GET /cars/{carId}` |
| `share(...)` | `PUT /cars/{carId}/group` |
| `unshare(carId)` | `DELETE /cars/{carId}/group` |
| `forGroup(groupId)` | `GET /groups/{groupId}/cars` |

```dart
await api.cars.create(
  name: "El Gol de Ada",  // requerido, ≤ 60 chars
  modelId: modelo.modelId,// requerido, sale de api.models.list()
  licensePlate: 'AB123CD',// opcional, ≤ 16 chars
  mileage: 120000,        // opcional, ≥ 0
);
```

El dueño sale del token, nunca del body. Nafta, batería y posición **no** se
aceptan acá: solo llegan desde el dispositivo.

`list()` trae los autos propios **más** los compartidos con algún grupo del
usuario, ordenados por nombre. Vacío para un usuario nuevo, nunca error.

Los campos de telemetría del `Car` (`fuelLevel`, `batteryLevel`, `latitude`,
`longitude`, `mileage`, `snapshotAt`) son una copia cacheada de la última
lectura; `snapshotAt` dice de cuál. Todos `null` en un auto que nunca reportó
(`car.hasSnapshot == false`).

```dart
await api.cars.share(carId: auto.id, groupId: grupo.id);
```

Un auto está en **un grupo a la vez**, por eso es `PUT`: compartir con un
segundo grupo lo *mueve*. Solo el dueño. Al des-compartir, un viaje abierto
queda como está — un viaje es el registro de lo que pasó, no un permiso.

Errores: `create` tira `isNotFound` con un `modelId` desconocido e
`isConflict` si ya tenés un auto con esa patente (las patentes son únicas **por
dueño**, no globalmente). `share` tira `isNotFound` tanto si el auto no es tuyo
como si no pertenecés al grupo.

### `api.models` — catálogo de modelos

| Método | Endpoint |
|---|---|
| `list()` | `GET /models` |
| `create(...)` | `POST /models` (solo admin) |

De acá sale el `modelId` que pide `cars.create`. El formulario de alta de auto
debería ser un selector sobre `list()`, no dos campos de texto libre — el
catálogo es curado justamente para que la tabla no se llene de `VW` / `vw` /
`Volkswagen`. Vienen ocho modelos sembrados con ids fijos, así que nunca está
vacío.

```dart
await api.models.create(
  modelBrand: 'Renault',              // requerido, ≤ 60
  modelName: 'Clio',                  // requerido, ≤ 60
  modelProtocol: 'ISO 15765-4 (CAN)', // requerido, ≤ 40
);
```

Requiere una cuenta con rol `ADMIN` (el rol de cuenta, no el de grupo).
`register` siempre crea usuarios comunes y ningún endpoint promueve a nadie, así
que el primer admin se hace con SQL:
`update users set role = 'ADMIN' where email = '…';` (toma efecto en el
siguiente login). Para todos los demás: `isForbidden`.

### `api.devices` — dongles OBD

| Método | Endpoint |
|---|---|
| `pair(...)` | `PUT /cars/{carId}/device` |
| `forCar(carId)` / `forCarOrNull(carId)` | `GET /cars/{carId}/device` |
| `unpair(carId)` | `DELETE /cars/{carId}/device` |
| `resolve(serial)` / `resolveOrNull(serial)` | `GET /devices/{serial}` |

Todos los dongles se anuncian con el mismo nombre BLE (`OBD-C`), así que un
celular que ve uno no puede distinguir dos autos de la misma familia. El mapeo
serial → auto vive en el servidor: todos los celulares resuelven igual, y mover
un dongle de auto re-rutea a todos de una.

```dart
await api.devices.pair(
  carId: auto.id,             // requerido, un auto propio
  serial: 'a4:cf:12:8b:3c:7e' // requerido, ≤ 64 chars: letras, dígitos, : _ -
);
```

El serial se guarda normalizado (trim + mayúsculas), así que se manda tal cual
lo reporte el stack BLE. Parear un serial nuevo **reemplaza** el dongle del
auto; parear el mismo otra vez es no-op. Solo el dueño: los miembros del grupo
manejan el auto, pero qué aparato habla por él lo decide el dueño.

`resolve(serial)` es el *"¿de qué auto es este dongle?"* que pregunta el
celular apenas se conecta, para mostrar el nombre del auto y ofrecer iniciar un
viaje. No es obligatorio antes de subir: `telemetry.upload` acepta el serial y
hace esa búsqueda solo.

Las variantes `…OrNull` devuelven `null` en vez de tirar cuando simplemente no
hay dongle, que es el estado normal de un auto sin parear.

Errores: `pair` tira `isConflict` si el serial está pareado a **otro** auto
(hay que desparearlo allá primero, para que mover un dongle sea siempre
deliberado). `resolve` tira `isNotFound` igual para un serial inexistente que
para uno de un auto que no podés ver.

### `api.groups` — grupos ("familias")

| Método | Endpoint |
|---|---|
| `create(name:)` | `POST /groups` |
| `list()` | `GET /groups` |
| `members(groupId)` | `GET /groups/{groupId}/members` |

`name` es requerido, ≤ 60 chars, y **no es único**: dos familias sin relación
pueden llamarse igual. El creador queda como `ADMIN` en la misma transacción.

`list()` trae los grupos del usuario con `memberCount` y `callerRole` — el rol
*de este usuario* en *ese* grupo, así una pantalla decide si muestra "invitar"
sin una segunda request (`grupo.callerIsAdmin`).

`members(groupId)` trae nombre, email y rol de cada uno, admins primero. Cada
fila ya trae todo lo que necesita la pantalla: no hay `GET /users/{id}` para
resolver ids, y una request por miembro sería justo el N+1 que esto evita.

Errores: `members` tira `isNotFound` tanto para un no-miembro como para un
grupo que no existe.

### `api.invitations` — invitaciones

| Método | Endpoint |
|---|---|
| `invite(...)` | `POST /invitations/invite/{groupId}` |
| `pending()` | `GET /invitations/pending` |
| `accept(invitationId)` | `POST /invitations/{id}/accept` |

```dart
await api.invitations.invite(
  groupId: grupo.id,          // requerido, un grupo donde seas ADMIN
  email: 'grace@example.com', // requerido, se guarda en minúsculas
);
```

Las invitaciones van por **email, no por id de usuario**, porque el caso normal
en una app familiar es invitar a alguien que todavía no la instaló: la fila
espera, y cuando ese email se registra la invitación ya está ahí. Invitar no
escribe nada en la tabla de miembros — entrar es consentimiento, lo hace el
invitado.

La respuesta es idéntica exista o no una cuenta con ese email, así que esto no
sirve como oráculo de "¿este mail está registrado?". Caducan a los 7 días.

`pending()` no lleva parámetros: el email sale siempre del token, que es lo que
impide listar (y después aceptar) las invitaciones de otro. Devuelve solo las
aceptables ahora mismo, con el **nombre** del grupo.

`accept(id)` marca la invitación y entra al grupo como `MEMBER` en una sola
sentencia atómica. Después aparece en `api.groups.list()`.

Errores: `invite` tira `isNotFound` si no sos miembro (un `403` confirmaría que
el grupo existe), `isForbidden` si sos miembro pero no admin, `isConflict` si
ese email ya es miembro. `accept` tira `isNotFound` para una invitación
desconocida **o** dirigida a otro, e `isConflict` si ya fue aceptada o venció.

### `api.trips` — viajes

| Método | Endpoint |
|---|---|
| `start(...)` | `POST /trips` |
| `mine()` | `GET /trips` |
| `forCar(carId)` | `GET /cars/{carId}/trips` |
| `active(carId)` | `GET /cars/{carId}/trips/active` |
| `finish(...)` | `POST /trips/{tripId}/finish` |
| `cancel(tripId)` | `DELETE /trips/{tripId}` |

```dart
final viaje = await api.trips.start(
  carId: auto.id,   // requerido, un auto que podés usar
  initialFuel: 70,  // opcional; si falta, se usa el fuelLevel cacheado del auto
);
```

El conductor sale del token y la hora del reloj del servidor: un viaje no se
puede registrar a nombre de otro ni antedatar. **Un solo viaje abierto por
auto**, garantizado por un índice único parcial, no por un chequeo en el
servicio: arranques en paralelo dan un `201` y el resto `409`.

```dart
await api.trips.finish(
  viaje.id,
  tripFinalFuel: 50, // opcional, ≥ 0. Ausente = gasto desconocido, no cero
  tripDistance: 140, // opcional, > 0
);
```

Solo el conductor cierra su viaje — el dueño del auto no puede cerrárselo por
abajo. `tripFinalFuel` puede ser mayor al inicial: cargó nafta, y `fuelUsed`
sale negativo en vez de "validarse".

`active(carId)` es el *"¿quién tiene el auto ahora?"*: devuelve `null` cuando
está libre (la API contesta `204`, que es una respuesta normal — un `404` acá
significa "no existe ese auto").

`cancel(tripId)` borra un viaje empezado por error, como si nunca hubiera
pasado. Solo uno **abierto** y propio: uno terminado es historia, tiene un
gasto asociado, y se queda.

`mine()` son los viajes que manejó el usuario, en cualquier auto.
`forCar(carId)` es la historia **del auto**: todos los viajes de todos los
conductores, que es para lo que sirve un auto compartido — quién lo usó y quién
gastó la nafta. Los dos sin paginar por ahora.

### `api.telemetry` — lecturas

| Método | Endpoint |
|---|---|
| `upload(...)` | `POST /telemetry` |
| `history(...)` | `GET /telemetry` |
| `readAll(...)` | `GET /telemetry` en loop |

El ESP32 habla BLE y nunca HTTP, así que **el celular es el relay**: guarda lo
que mandó el dongle y lo sube en lotes, quizá tarde, quizá repitiendo algo de
lo que no estaba seguro. Los dos endpoints están hechos para eso.

```dart
await api.telemetry.upload(
  serial: 'a4:cf:12:8b:3c:7e', // exactamente uno de serial / carId
  readings: [
    TelemetryReading(
      recordedAt: DateTime.now().toUtc(), // requerido: hora del *dispositivo*
      latitude: -34.6037,   // opcional, pero lat y lng van juntas o ninguna
      longitude: -58.3816,
      speed: 40,            // opcional, ≥ 0
      fuelLevel: 70,        // opcional, ≥ 0
      batteryLevel: 85,     // opcional, ≥ 0
      mileage: 120000,      // opcional, ≥ 0
      raw: {'pids': {'04': '5020'}}, // opcional: el frame tal cual
    ),
  ],
);
```

| Parámetro | Requerido | Notas |
|---|---|---|
| `serial` / `carId` | exactamente uno | Pasar los dos o ninguno es `ArgumentError` del lado Dart y `400` del lado servidor |
| `readings` | sí | 1–500 lecturas |

**Con `serial`** el servidor resuelve el auto pareado: el celular no sabe —ni
se le pregunta— en qué auto está, así que no puede equivocarse, y además se
actualiza el `lastSeenAt` del dongle. **Con `carId`** se nombra el auto
directo, para autos sin dongle, carga manual o tests.

Lo que vuelve (`TelemetryIngestResult`) es todo sobre el próximo paso del
cliente:

| Campo | Para qué |
|---|---|
| `stored` / `duplicates` | Cuántas eran nuevas y cuántas ya estaban. **Las dos son éxito**: un reintento que encuentra sus lecturas hizo su trabajo. Sirven para recortar el buffer |
| `carId` | En qué auto cayeron — subiendo por serial, así es como el celular se entera |
| `tripId` | El viaje abierto del auto, o `null`: la señal para ofrecerle al conductor iniciar uno |
| `snapshotUpdated` | Si se movió el estado cacheado del auto. `false` = no hay nada que redibujar |
| `latestRecordedAt` | El cursor de sincronización |

Una lectura mal formada rechaza **el lote entero**, con los errores indexados
(`readings[2].positionComplete`): aplicar la mitad dejaría al celular sin saber
qué borrar del buffer. Los duplicados, en cambio, son éxito — no hay `409`.

```dart
final pagina = await api.telemetry.history(
  carId: auto.id,     // requerido
  since: ultimoCursor,// opcional: lecturas estrictamente posteriores
  limit: 500,         // opcional, 1–500, default 100
);
// guardar pagina.nextSince y repetir mientras pagina.hasMore
```

Viene **de la más vieja a la más nueva**. Lo más nuevo primero con un límite no
se puede paginar bien: se saltea en silencio lo que cayó entre las N más nuevas
y el cursor. `since` es exclusivo porque el cliente devuelve un valor que ya
tiene (`nextSince` de la página anterior o `latestRecordedAt` de una subida).

`readAll(carId:, since:)` hace ese loop y devuelve todo junto.

---

## 5. Recetas

### Del dongle a la base

El camino completo, en orden:

```dart
// 1. Una vez, el dueño: decir qué dongle es de qué auto.
await api.devices.pair(carId: auto.id, serial: serialLeidoPorBle);

// 2. El celular se conecta a "OBD-C" y lee el serial por BLE.
//    (Falta la mitad del firmware: todavía no expone el serial.)

// 3. Opcional: "¿de qué auto es esto?" para mostrarlo y ofrecer el viaje.
final device = await api.devices.resolveOrNull(serialLeidoPorBle);

// 4. Subir el buffer. El celular nunca dice qué auto es.
final r = await api.telemetry.upload(serial: serialLeidoPorBle, readings: buffer);
if (!r.hasOpenTrip) { /* ofrecer iniciar viaje */ }

// 5. Cualquier miembro lee la historia.
final pagina = await api.telemetry.history(carId: r.carId, since: cursor);
```

### Compartir un auto con la familia

```dart
final grupo = await api.groups.create(name: 'Familia Lazzari');
await api.cars.share(carId: auto.id, groupId: grupo.id);
await api.invitations.invite(groupId: grupo.id, email: 'grace@example.com');

// Del lado de Grace:
final invitaciones = await api.invitations.pending();
await api.invitations.accept(invitaciones.first.id);
// Ahora el auto le aparece en api.cars.list() y puede iniciar viajes.
```

### Reemplazar los datos de demo

Las pantallas todavía se dibujan con `DemoData`. El reemplazo es por partes,
sin tocar los widgets: `CarTab` ya recibe un `CarData`, así que alcanza con
mapear `Car` (API) → `CarData` (UI) y pasárselo a `MainScreen`. Lo mismo para
`members` desde `api.groups.members()` y `trips` desde
`api.trips.forCar(carId)`.

`ReservationRepository` es el caso más limpio: ya está definido como interfaz
justamente para esto. Una `ApiReservationRepository` que la implemente cambia
una línea en `MainScreen` — aunque las reservas todavía no tienen endpoints en
el backend.

---

## 6. Qué falta

- **Persistir la sesión.** Hoy cerrar la app desloguea (ver arriba).
- **Reservas.** La app las tiene en memoria; la API no las expone todavía.
- **Paginar viajes.** `GET /trips` y `GET /cars/{id}/trips` vienen sin paginar
  del servidor, así que `mine()` y `forCar()` tampoco paginan.
- **Nafta: ¿porcentaje o litros?** El servidor todavía no lo define (acepta
  cualquier valor ≥ 0), así que `fuelLevel` y `fuelUsed` se tratan como números
  opacos y no se les pone unidad en la UI.
- **El firmware no expone un serial.** La API de dispositivos está completa,
  pero hasta que el ESP32 publique el Device Information Service (`0x180A`,
  Serial Number `0x2A25`) el serial es lo que tipee la persona que parea.
- **Generar el cliente.** El `ROADMAP.md` de la API propone agregar springdoc:
  con eso estos modelos se generarían desde el contrato en vez de escribirse a
  mano. Este cliente es más legible y documenta el *por qué*, pero es una
  segunda copia del contrato que hay que mantener en sincronía.
