# Base de datos de AgroScan (PostgreSQL + PostGIS)

Qué guarda la app de escritorio en la base, cómo se conecta, qué funciona hoy y **cómo se conectarían los datos del teléfono**, que
todavía no llegan a la base. Para instalarla en una PC nueva, ver también la sección "Base de datos" del [README](../README.md).

## 1. Resumen

```
 TELÉFONO (Room/SQLite)                         PC
 ┌──────────────────────┐                ┌──────────────────────────────────────────────┐
 │ lotes, vértices,     │  HTTP 8766     │ Carpeta de datos (Documentos\AgroScan)        │
 │ parámetros, misiones,│ ─────────────▶ │   vuelos\…\salida-N\ registro.csv, eventos,   │  ← HOY: registros de vuelo,
 │ vuelos, salidas      │  (registros    │   salida.json, video\  ·  logs\               │    marcas, videos y logs
 │ + registro de vuelo  │   de vuelo)    │                                              │
 └──────────────────────┘                │ PostgreSQL "dron_control"                     │
            │                            │   usuario  ← login y registro (HOY)           │
            └ ─ ─ ─ ─ POST /sync ─ ─ ─ ▶ │   lote, vertice, parametros_vuelo, mision,    │  ← PENDIENTE: el historial
              (pendiente, sección 6)     │   vuelo, salida, dispositivo                  │    (hoy esas tablas están vacías)
                                         └──────────────────────────────────────────────┘
```

| Dato | Dónde vive | Estado |
|---|---|---|
| Cuentas (cédula, nombre, hash de la contraseña) | Tabla `usuario` | **Funciona** |
| Sesión abierta | En la PC (Preferences de Java), **no** en la base: así se entra sin conexión | **Funciona** |
| Registro de vuelo, marcas del video, videos, logs del enlace | Archivos en la carpeta de datos (son grandes y se usan sin conexión) | **Funciona** |
| Lotes, misiones, vuelos y salidas del teléfono | Tablas `lote`, `vertice`, `parametros_vuelo`, `mision`, `vuelo`, `salida` | **Pendiente**: el esquema existe, pero nada las llena todavía. Por eso Inicio muestra 0 / N/A |

## 2. Cómo se conecta la app

- **Motor:** PostgreSQL 16 con la extensión PostGIS 3.6 (la usa `lote.perimetro`, un polígono en WGS84, SRID 4326).
- **Base:** `dron_control`. Esquema completo en [`database/dron_control.sql`](../database/dron_control.sql); los cambios posteriores
  van numerados en `database/001_*.sql`, `002_*.sql`… (hoy solo `001_login_por_cedula.sql`).
- **Dónde está el código:** solo [`core/Database.kt`](../desktopApp/src/main/kotlin/org/agroscan/project/core/Database.kt) sabe
  conectarse (`Database.connect()`); las consultas viven en cada funcionalidad (`features/…`). Cambiar de base o de servidor no toca
  las consultas.
- **Datos de conexión:** la URL JDBC completa, con usuario y contraseña, viene de la variable de entorno de Windows
  `AGROSCAN_DB_URL`. La contraseña **nunca** está en el código ni en git:
  ```
  setx AGROSCAN_DB_URL "jdbc:postgresql://localhost:5432/dron_control?user=postgres&password=..."
  ```
  Símbolos en la contraseña: `&`→`%26`, `#`→`%23`, `@`→`%40`, `%`→`%25`, `?`→`%3F`, espacio→`%20`.
  Después de `setx` hay que reiniciar el IDE y detener el daemon de Gradle (`./gradlew --stop`).
- **Cómo se usa:** una conexión por consulta (`Database.connect().use { … }`), siempre desde un hilo de fondo, y siempre con
  `PreparedStatement` (los datos nunca se pegan dentro del SQL).
- **Indicador:** la barra de Inicio muestra "BD local: dron_control" en verde o "BD sin conexión" en rojo; el motivo del error sale
  en la consola.
- **Instalador (.exe):** el Java recortado del instalador necesita el módulo `java.management` para el driver de PostgreSQL (ya está
  en `desktopApp/build.gradle.kts`). Sin él el `.exe` dice "BD sin conexión" aunque todo esté bien configurado.

### Instalar la base en una PC nueva
1. Instalar PostgreSQL 16 con PostGIS (Stack Builder → Spatial Extensions → PostGIS).
2. Crear la base y cargar el esquema:
   ```
   createdb -U postgres dron_control
   psql -U postgres -d dron_control -f database/dron_control.sql
   ```
   En una base creada antes de algún cambio, aplicar en orden los `database/00N_*.sql` que falten.
3. Crear `AGROSCAN_DB_URL` (arriba) y reiniciar el IDE, o abrir la app instalada.

## 3. Qué usa la app hoy

| Pantalla / función | Tablas | Código |
|---|---|---|
| Iniciar sesión y registrarse (cédula única) | `usuario` | [`features/login/Auth.kt`](../desktopApp/src/main/kotlin/org/agroscan/project/features/login/Auth.kt) |
| Inicio: hectáreas escaneadas, vuelos del mes, misiones recientes (solo las del usuario con sesión: `creado_por`) | `lote`, `mision`, `parametros_vuelo`, `vuelo` | [`features/home/HomeData.kt`](../desktopApp/src/main/kotlin/org/agroscan/project/features/home/HomeData.kt) |
| Telemetría: misión en curso según la base (si no llega la del teléfono en vivo) | `vuelo`, `mision`, `lote` | [`features/telemetry/TelemetryData.kt`](../desktopApp/src/main/kotlin/org/agroscan/project/features/telemetry/TelemetryData.kt) |

Tablas que existen pero ninguna pantalla escribe todavía: `lote`, `vertice`, `parametros_vuelo`, `mision`, `vuelo`, `salida`,
`dispositivo`.

## 4. El esquema, tabla por tabla

Todas las tablas usan **UUID** como clave: el mismo UUID que el teléfono pone a cada lote, misión, vuelo y salida, así un registro
no se duplica aunque llegue varias veces o desde otra PC.

| Tabla | Para qué | Columnas principales |
|---|---|---|
| `usuario` | Cuentas de la app de escritorio | `cedula` (única, obligatoria), `nombre`, `password_hash` (PBKDF2), `email` (opcional), `activo` |
| `dispositivo` | Teléfonos que envían datos | `usuario_id`, `tipo`, `nombre`, `ultimo_sync` |
| `lote` | Terreno a mapear | `nombre`, `area_ha`, `perimetro` (PostGIS Polygon 4326), `creado_por`, `creado_en`, `eliminado_en` |
| `vertice` | Vértices del lote, en orden | `lote_id`, `orden` (único por lote), `latitud`, `longitud` |
| `parametros_vuelo` | Cómo se planificó la ruta | `altura_m`, `traslape_lateral_pct`, `traslape_frontal_pct`, `margen_interno_m`, `altura_copa_m`, `modo_captura` |
| `mision` | El plan de un lote | `lote_id`, `parametros_id`, `nombre`, `estado` (`BORRADOR`, `SINCRONIZADO`, `COMPLETADO`), `ruta_waypoints` (jsonb), `creado_por` |
| `vuelo` | Una ejecución de la misión | `mision_id`, `estado` (`EN_CURSO`, `COMPLETADO`, `INTERRUMPIDO`), `inicio`, `fin`, `perimetro_volado`, `ruta_volada` (jsonb), `siguiente_waypoint`, `iniciado_por` |
| `salida` | Un despegue (una batería) | `vuelo_id`, `indice`, `desde_wp`, `hasta_wp`, `estado`, `motivo`, `inicio_ms`, `fin_ms`, `bateria_inicio/fin`, `video_inicio_ms/fin_ms`, `distancia_m`, `operador_id`, `dispositivo_id` |

## 5. Lo que no va a la base (y por qué)

Los registros de vuelo (`registro.csv`, posición cada ~200 ms), los eventos con las marcas del video, los videos de la microSD y los
logs del enlace se guardan como **archivos** en la carpeta de datos (`Documentos\AgroScan`; ver README):

- Un video son varios GB y un registro de vuelo miles de filas que solo lee el módulo de procesamiento, de corrido.
- Tienen que funcionar sin conexión con la base.
- La carpeta de cada salida se encuentra por el UUID de la salida (`salida.json`), el mismo de la tabla `salida`: la base puede
  apuntar a los archivos sin copiarlos.

## 6. Cómo se conectarían los datos del teléfono con la base (pendiente)

Hoy el teléfono envía a la PC **los registros de vuelo** de cada salida, pero no su **historial** (lotes, misiones, vuelos). Esta es
la forma de llenar las tablas, siguiendo lo que ya funciona para los registros:

**Quién manda:** el teléfono es la fuente de verdad (vuela sin internet); la base de la PC es la copia. Va en un solo sentido:
teléfono → PC.

**Cómo:** una petición nueva en el mismo servidor HTTP de la Red Local (puerto 8766, sin otro permiso del Firewall):
`POST /sync` con **todo** el historial del teléfono en JSON (son pocos KB; mandar todo evita llevar la cuenta de qué falta y nunca se
desincroniza). Se mandaría junto con los registros de vuelo: al cerrar cada salida y con "Enviar vuelos pendientes".

**Qué hace la PC con cada envío**, en **una sola transacción** (si algo falla, no queda nada a medias):
1. Inserta o actualiza cada registro **por UUID** (`INSERT … ON CONFLICT (id) DO UPDATE`), padres antes que hijos:
   lote → vértices → parámetros → misión → vuelo → salida. Mandar lo mismo dos veces no duplica nada.
2. `creado_por` (lote, misión), `iniciado_por` (vuelo) y `operador_id` (salida) = el **usuario con sesión en la PC**: el teléfono no
   tiene cuentas.
3. Arma `lote.perimetro` con los vértices (PostGIS: `ST_MakePolygon` con el primer vértice repetido al final, SRID 4326) y
   `area_ha` con `ST_Area(perimetro::geography) / 10000`.
4. Registra el teléfono en `dispositivo` y lo pone en `salida.dispositivo_id`.

**De Room (teléfono) a PostgreSQL (PC):** el esquema de la PC se hizo a partir del del teléfono, así que es casi 1 a 1:

| Teléfono (Room) | PC (PostgreSQL) | Notas |
|---|---|---|
| `lotes`: `uuid`, `nombre`, `areaEstimadaHa`, `fechaCreacion` | `lote`: `id`, `nombre`, `area_ha`, `creado_en` | `perimetro` se arma con los vértices |
| `vertices`: `ordenSecuencia`, `latitud`, `longitud` | `vertice`: `orden`, `latitud`, `longitud` | No tienen UUID: en cada envío se reemplazan los del lote |
| `parametros_vuelo`: `alturaMetros`, `traslapePorcentaje`, `traslapeFrontalPorcentaje`, `margenInternoMetros`, `alturaCopaMetros`, `modoCaptura` | `parametros_vuelo`: `altura_m`, `traslape_lateral_pct`, `traslape_frontal_pct`, `margen_interno_m`, `altura_copa_m`, `modo_captura` | No tienen UUID: se usa el de su misión (es 1 a 1) |
| `misiones`: `uuid`, `nombre`, `estado`, `duracionEstimadaMin`, `fotosEstimadas`, `rutaWaypointsJson`, `fechaCreacion`, `fechaActualizacion` | `mision`: `id`, `nombre`, `estado`, `duracion_estimada_min`, `fotos_estimadas`, `ruta_waypoints`, `creado_en`, `actualizado_en` | |
| `vuelos`: `uuid`, `estado`, `fechaInicio`, `fechaFin`, `modoCaptura`, `perimetroJson`, `rutaJson`, `totalWaypoints`, `siguienteWaypoint` | `vuelo`: `id`, `estado`, `inicio`, `fin`, `modo_captura`, `perimetro_volado`, `ruta_volada`, `total_waypoints`, `siguiente_waypoint` | |
| `salidas`: `uuid`, `indice`, `desdeWaypoint`, `hastaWaypoint`, `estado`, `motivo`, `inicioMs`, `finMs`, `bateriaInicio`, `bateriaFin`, `videoInicioMs`, `videoFinMs`, `distanciaM` | `salida`: `id`, `indice`, `desde_wp`, `hasta_wp`, `estado`, `motivo`, `inicio_ms`, `fin_ms`, `bateria_inicio`, `bateria_fin`, `video_inicio_ms`, `video_fin_ms`, `distancia_m` | `logPath` no va: los archivos están en la carpeta de la salida |

Fechas: el teléfono guarda milisegundos UTC; las columnas `timestamp with time zone` se llenan con `to_timestamp(ms / 1000.0)`.

**Lo que esta primera versión no haría** (a propósito):
- **Borrados:** lo que se borre en el teléfono sigue en la PC (`eliminado_en` queda para cuando se decida cómo manejarlo).
- **PC → teléfono:** editar algo en la PC no vuelve al teléfono.
- **Varias PCs:** cada PC tiene su base local. Juntar todo en un **PostGIS en línea** sería un paso después: la misma subida por
  UUID, de la base local a la central, cuando haya internet.

**Dónde iría el código:** `features/sync/` en la PC (la ruta `/sync` y la transacción) y `network/HistorySync` en el teléfono (arma el
JSON con Room), con el formato documentado en `docs/PROTOCOLO_PC.md` del proyecto del dron, como los demás canales.

## 7. Seguridad

- La contraseña de la base está en la variable `AGROSCAN_DB_URL` de cada PC, nunca en el código ni en git.
- Las contraseñas de las cuentas se guardan como hash PBKDF2-SHA256 con sal (600.000 iteraciones), nunca en texto.
- La cédula es única en la base (`usuario_cedula_key`), no solo en la app.
- Todas las consultas usan `PreparedStatement`.
- Lo que llega del teléfono se valida antes de usarlo (UUID canónicos, tamaños máximos, solo equipos de la Red Local).
