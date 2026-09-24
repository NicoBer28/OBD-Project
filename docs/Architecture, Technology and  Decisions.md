## Arquitectura y Decisiones: Telemetría OBD-II en Segundo Plano
### 1. Tecnologías Utilizadas
- Frontend (UI): Flutter (Dart). Utilizado exclusivamente como una capa de presentación reactiva y multiplataforma.

- Motor Nativo (Background): Kotlin (Android SDK). Encargado de la gestión de hardware a bajo nivel (Bluetooth y GPS) y de mantener el proceso vivo.

- Hardware (Periférico): ESP32 programado en C++ utilizando la librería NimBLE. (NimBLE se eligió por ser mucho más eficiente en el uso de memoria RAM que la pila Bluetooth estándar).

- Puente de Comunicación: Pigeon. Herramienta para generar código fuertemente tipado entre Dart y Kotlin.

- Persistencia (Próximo paso): SQLite a través de la librería Room (Android nativo) para almacenamiento local eficiente.

### 2. Estructura de la Arquitectura (Desacoplamiento)
La arquitectura se basa en un patrón de desacoplamiento estricto entre la interfaz gráfica y los recursos de hardware.

- Capa Reactiva (Flutter): Es una interfaz "tonta". No sabe cómo conectarse al Bluetooth ni cómo obtener coordenadas. Simplemente escucha un canal de eventos y dibuja los números (RPM, velocidad, nafta) en pantalla a 60 FPS.

- Capa Autónoma (Servicio Nativo): Es un Foreground Service del sistema operativo. Funciona de manera independiente a la UI. Es el dueño absoluto del escáner BLE y del GPS. Se encarga de suscribirse a las notificaciones del hardware, procesar los bytes crudos a datos útiles, y distribuirlos (a la UI si está abierta, o a la base de datos local).

### 3. Decisiones de Arquitectura y Rationale
#### A. Abandonar librerías BLE de Flutter en favor de Código Nativo
**Decisión:** No utilizar plugins multiplataforma (como flutter_blue_plus) para la conexión al OBD-II, programando el cliente GATT directamente en Kotlin (y a futuro en Swift).
**Por qué:** Los motores de UI multiplataforma son suspendidos o destruidos por el sistema operativo cuando el usuario apaga la pantalla o cierra la aplicación. Para lograr un rastreo continuo (registrar un viaje entero con el teléfono en el bolsillo), la conexión Bluetooth debía estar anclada a un proceso nativo prioritario que sobreviva a la muerte de la interfaz visual.

#### B. Despertar Autónomo mediante Companion Device
**Decisión:** Utilizar el API de dispositivos vinculados del sistema operativo (CompanionDeviceManager) en lugar de escaneos BLE manuales.
**Por qué:** Permitir que el sistema operativo registre la dirección MAC del auto como un "dispositivo de confianza". Esto delega la responsabilidad de vigilancia al propio Android, logrando que el celular despierte la aplicación "desde la tumba" de forma automática en el instante en que el auto se enciende, sin intervención del usuario y sin gastar batería escaneando constantemente.

#### C. Comunicación en Memoria (RAM) vs. Base de Datos (Disco) para la UI
**Decisión:** Inyectar los datos en tiempo real hacia Flutter a través de memoria RAM (usando Pigeon), reservando la Base de Datos nativa solo para puntos de guardado espaciados.
**Por qué:** El ESP32 envía telemetría cada 20 milisegundos. Escribir y consultar una base de datos local en disco a esa velocidad generaría un cuello de botella de Entrada/Salida (I/O), drenando la batería y trabando la interfaz. El puente en memoria garantiza fluidez visual sin costo de I/O.

#### D. Seguridad de Tipos en el Puente (Pigeon)
**Decisión:** Utilizar generación de código (Pigeon) en lugar de canales de mensajes crudos (MethodChannels con diccionarios/mapas).
**Por qué:** Minimiza los errores en tiempo de ejecución. Al obligar a Kotlin y a Dart a compartir el mismo "contrato" de clases (ej. un objeto de Telemetría con propiedades exactas), el compilador atrapa errores de desajuste de tipos o nombres de variables antes de construir la app.

#### E. Mitigación de Errores de Hardware (GATT 133)
**Decisión:** Forzar el transporte a Low Energy (TRANSPORT_LE) y delegar el tiempo de conexión al chip físico (autoConnect = true).
**Por qué:** La pila Bluetooth de los celulares es propensa a colapsar por congestión de peticiones (el infame Error 133). Al forzar el canal LE, evitamos que el celular intente protocolos antiguos. Al usar conexión delegada, evitamos exigirle a la antena que se conecte en el mismo milisegundo en que está procesando su aparición en el radar, logrando una conexión asíncrona pero extremadamente estable.