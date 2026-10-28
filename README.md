This is a Kotlin Multiplatform project targeting Desktop (JVM).

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Base de datos (PostgreSQL + PostGIS)

La app lee y guarda en PostgreSQL. Para configurarla en una PC nueva:

1. Crear la base (por ejemplo `dron_control`) y cargar el esquema: [`database/dron_control.sql`](./database/dron_control.sql).
   En una base creada antes, aplicar los cambios numerados en orden (`database/001_*.sql`, …).
2. Definir la conexión en la variable de entorno `AGROSCAN_DB_URL` (la contraseña no va en el código):
   ```
   setx AGROSCAN_DB_URL "jdbc:postgresql://localhost:5432/dron_control?user=postgres&password=..."
   ```
3. Reiniciar el IDE y detener el daemon de Gradle (`./gradlew --stop`) para que tomen la variable.

### Cuentas y sesión

- Sin sesión, la app muestra el login en lugar de cualquier pantalla; cada pantalla muestra solo los datos del usuario que entró.
- Las cuentas se identifican por **cédula ecuatoriana** (única) y contraseña; la contraseña se guarda como hash PBKDF2.
- La sesión queda guardada en la PC y se restaura al abrir la app **sin consultar la base** (funciona sin conexión);
  dura hasta que el usuario la cierre desde el avatar.

Detalles en [`features/login/Auth.kt`](./desktopApp/src/main/kotlin/org/agroscan/project/features/login/Auth.kt).

### Telemetría en vivo (desde el teléfono)

La app del dron manda por **UDP, puerto 8765**, la posición, batería, velocidad y estado de la misión (cada 0,5 s) y el nombre y la ruta de la misión (cada 5 s).
[`features/telemetry/TelemetryReceiver.kt`](./desktopApp/src/main/kotlin/org/agroscan/project/features/telemetry/TelemetryReceiver.kt) los recibe desde que abre la app y
los deja para la pantalla de **Telemetría** (mapa con la ruta, la estela y el dron; tarjetas de velocidad, altitud, batería, recorrido, fotos y GPS) y la tarjeta de batería de **Inicio**.

- Hay señal si llegó un paquete hace menos de 3 s; si no, la pantalla muestra "Sin señal · último dato hace N s" y la última posición en rojo.
- **El puerto solo existe en la red de la zona móvil:** la app lo abre en `192.168.137.1` y solo mientras la Red Local está encendida.
  Desde cualquier otra red (casa, oficina, café) no hay puerto que alcanzar.
- **Firewall sin comandos:** la primera vez que se enciende la Red Local, Windows pregunta una sola vez *"¿Permitir que esta aplicación
  haga cambios?"* → **Sí**, y la app crea dos reglas: `AgroScan telemetria xxxxxxxx` (UDP 8765) y `AgroScan registros xxxxxxxx`
  (TCP 8766), cada una solo para esa red y solo para AgroScan.
  Ver [`features/redlocal/LocalFirewall.kt`](./desktopApp/src/main/kotlin/org/agroscan/project/features/redlocal/LocalFirewall.kt).
  Si el usuario responde No, la Red Local avisa; apagarla y encenderla vuelve a preguntar. Conviene desactivar el ahorro de energía de la zona móvil.
- Solo se aceptan paquetes de la red de la zona móvil (`192.168.137.x`) y el protocolo no lleva órdenes: la PC solo lee y muestra.
  Detalle de seguridad en `docs/PROTOCOLO_PC.md` del proyecto del dron.
- Antes de despegar (al iniciar la salida) el teléfono manda también el contorno del lote, el origen, el número de salida, la altura y el modo de captura: el mapa los dibuja y la barra superior muestra "Salida 2 de 3 · 25 m · Video".
- Formato de los paquetes y cómo probar sin el teléfono: `docs/PROTOCOLO_PC.md` del proyecto del dron (la app Android).
- Sin mapa satelital todavía (el mapa dibuja ruta y estela sobre fondo liso, sin internet) y el botón "ABORTAR (RTL)" sigue sin función: no hay canal PC → dron.

### Registros de vuelo y videos (para el procesamiento)

Cada salida de una misión necesita en la PC tres cosas, que llegan por caminos distintos y se juntan en una carpeta:

```
Documentos\AgroScan\vuelos\
  2026-10-27_Finca-Sanchez_Sector-1_a1b2c3d4\     ← un vuelo (fecha, lote, misión, inicio de su UUID); vuelo.json
    salida-1\
      registro.csv     ← del teléfono, por Wi-Fi: posición cada ~200 ms
      eventos.jsonl    ← del teléfono, por Wi-Fi: las marcas (segundo del video + posición) y demás eventos
      salida.json      ← datos de la salida (incluye cuándo empezó y terminó de grabar)
      video\           ← de la microSD: los MP4 de esa salida
```

- **Registro y marcas — llegan solos.** Al terminar cada salida el teléfono los envía por HTTP (TCP 8766, solo en la Red Local) si está
  conectado; lo que se voló sin la PC cerca se envía con **Enviar vuelos pendientes** en la app del teléfono. La PC los recibe en
  [`features/flightlog/FlightLogServer.kt`](./desktopApp/src/main/kotlin/org/agroscan/project/features/flightlog/FlightLogServer.kt)
  y los guarda **una sola vez** (un reintento o un envío falso no los pisa).
- **Video — se importa de la microSD.** En **Procesamiento → Importar desde la microSD** se elige la tarjeta; cada video se **copia**
  (la tarjeta se reutiliza) a la salida en que se grabó, por la hora del archivo y la que guardó el teléfono. Los videos de vuelos
  manuales se ignoran. Supone que el teléfono y la PC están en la misma zona horaria.
- **Carpeta:** `Documentos\AgroScan` por defecto; se cambia en **Procesamiento → Carpeta de datos** (p. ej. a un disco más grande).
  Cambiarla no mueve lo ya guardado. Ver [`core/DataFolder.kt`](./desktopApp/src/main/kotlin/org/agroscan/project/core/DataFolder.kt).
- Lo que se haga después con esos archivos (extraer cuadros, ortomosaico) es de otro módulo: lee esta carpeta.
- Formato de los envíos: sección "Registros de vuelo" de `docs/PROTOCOLO_PC.md` del proyecto del dron.

### Estructura de la app de escritorio

`desktopApp/src/main/kotlin/org/agroscan/project/`:

| Carpeta | Contiene |
|---|---|
| `core/` | Solo infraestructura compartida: la conexión a la base (`Database.kt`; cambiar de base no toca las consultas), la carpeta de datos (`DataFolder.kt`) y el lector de JSON (`MiniJson.kt`) |
| `features/<funcionalidad>/` | Lógica y consultas de cada funcionalidad (`login`, `home`, `telemetry`, `flightlog`, `redlocal`…) |
| `ui/` | Solo diseño (Compose): llama a `features`, nunca a SQL directamente |

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Desktop tests: `./gradlew :shared:jvmTest :desktopApp:test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…