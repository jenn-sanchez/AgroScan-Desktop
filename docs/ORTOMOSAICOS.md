# Ortomosaicos en AgroScan (app de escritorio)

Qué hace el menú **Ortomosaicos**, cómo se guardan los ortomosaicos, de dónde sale la orden de armarlos y cómo se aplicaron las
heurísticas de usabilidad de Nielsen. El código está en
[`features/ortomosaico/`](../desktopApp/src/main/kotlin/org/agroscan/project/features/ortomosaico) (lógica, sin Compose) y
[`ui/Ortomosaicos*.kt`](../desktopApp/src/main/kotlin/org/agroscan/project/ui) (pantallas).

## Estado

| Parte | Estado |
|---|---|
| Menú, pestañas **Creados** y **Crear**, redirección desde Procesamiento | Hecho |
| Almacenamiento en carpetas, ficha `ortomosaico.json`, índice en la base | Hecho |
| Historial por lote, ver (zoom, contorno del lote), comparar (cortina / transparencia), abrir carpeta, eliminar | Hecho |
| Revisión previa a armar (salidas, espacio, procesos en marcha, vuelo ya armado) | Hecho |
| **Armar** el ortomosaico: orquestador (`Armado`) y módulo de Python `ortomosaico/`, con progreso, cancelar y publicación segura | Hecho |
| Ajuste de las imágenes entre sí por puntos en común (SIFT) | Hecho (parte 3) |
| Alinear cada fecha con la primera del lote; error estimado medido | Hecho (parte 3) |
| Cuadrícula fija por lote y **teselas para la IA** (`teselas/`, `teselas.csv`, `georef.json`, `cobertura.geojson`) | Hecho (parte 3); contrato en [`IA_ORTOMOSAICOS.md`](./IA_ORTOMOSAICOS.md) |
| Pantalla de IA | Pendiente (parte 4) |
| Validar con un vuelo real | **Pendiente**: todo se midió con vuelos sintéticos (ver `ortomosaico/README.md`) |

## Flujo

1. El teléfono manda el vuelo; en **Procesamiento** se importan los videos y se **preprocesa** cada salida.
2. Al terminar, aparece **Crear ortomosaico →** (en el cuadro del preprocesamiento y en cada vuelo con imágenes). Lleva a la pestaña
   **Crear** con ese vuelo elegido (`Navegacion.crearOrtomosaico`).
3. En **Crear** se marcan las salidas, se eligen calidad e imágenes de partida (se recuerdan) y la **revisión previa** (`Preparacion.evaluar`)
   dice si se puede armar. Cada punto cuenta qué pasa y qué hacer; un bloqueo apaga el botón y se repite junto a él.
4. La orden de armar sale **siempre de esta pestaña** (`onArmar` → `Armado.iniciar`). Procesamiento solo lleva hasta ahí: así hay un único
   lugar desde donde se pide, con sus ajustes y su revisión.
5. Mientras se arma, una franja con la etapa, el porcentaje y **Cancelar** (pide confirmar) se ve en las dos pestañas; seguir en otra pantalla
   de la app no lo corta. Al terminar, otra franja dice cómo salió (con las advertencias del módulo) y, si quedó listo, lleva a verlo.
6. El resultado queda en **Creados**, en el historial de su lote.

### Cómo se arma por detrás

`Armado.iniciar` revisa lo inmediato (que no haya otro armado, que exista el módulo, que el disco alcance) y sigue en segundo plano:

1. Lee de la base el lote, la misión, la altura de los árboles y el contorno del vuelo (`OrtomosaicoDb.contextoDeVuelo`). Si el vuelo aún no está
   en la base, lo dice y pide que el teléfono envíe su historial.
2. Escribe `entrada.json` ([`EntradaArmado`](../desktopApp/src/main/kotlin/org/agroscan/project/features/ortomosaico/EntradaArmado.kt)) en una carpeta de
   trabajo `.tmp_…` junto al destino y ejecuta el módulo ([`OrtomosaicoModule`](../desktopApp/src/main/kotlin/org/agroscan/project/features/ortomosaico/OrtomosaicoModule.kt)),
   leyendo su avance. El detalle del módulo y su contrato están en [`ortomosaico/README.md`](../ortomosaico/README.md).
3. Al terminar bien escribe la ficha, **publica** (el anterior se aparta y solo se borra cuando el nuevo ya está en su sitio) y lo anota en la base.
   Un fallo de la base no impide guardarlo: se avisa.
4. Un armado que **falla o se cancela** nunca estropea el ortomosaico que el vuelo ya tenía. Si no tenía ninguno, queda su ficha *con error* o
   *cancelado* con el motivo, para que el historial lo muestre y se pueda volver a armar o eliminar.

Esto vive en `Armador` (probado con un módulo de mentira) y en `Armado` (el estado de toda la app).

La unidad es el **vuelo** (todas sus salidas juntas): hay **un ortomosaico por vuelo** (`vuelo_id` único). Volver a armar lo reemplaza, y
solo cuando el nuevo termina bien (el anterior se aparta, el nuevo se publica de una vez y, si algo falla, el anterior se restaura).

## Dónde se guarda

```
Documentos\AgroScan\ortomosaicos\
  <lote>_<8 primeros del UUID del lote>\
    <AAAA-MM-DD>_<8 primeros del UUID del vuelo>\
      ortomosaico.json     la ficha (versión 1): estado, vuelo, lote, métricas, límites…  ← fuente de verdad
      ortomosaico.tif      el mosaico georreferenciado
      vista.jpg            lo que muestra el visor (un GeoTIFF no se puede mostrar tal cual)
      miniatura.jpg        la tarjeta del historial
      entrada.json         la orden con la que se armó
      piramide\, piramide.json  el mosaico a resolución completa en teselas con niveles de zoom: es lo que el visor dibuja al acercar
      georef.json          dónde está el mosaico sobre el terreno (UTM) y la cuadrícula del lote
      cobertura.geojson    contorno de lo cubierto y del lote
      teselas\, teselas.csv  las teselas para la IA, cortadas por una cuadrícula fija del lote
```

- Es una carpeta **aparte de `vuelos\`** a propósito: borrar videos para liberar espacio no destruye el historial del cultivo.
- La carpeta del lote se **encuentra por el UUID**, no por el nombre: si el lote se renombra, los ortomosaicos nuevos siguen en la misma.
- `entrada.json` (la orden con la que se armó) se guarda junto al ortomosaico. Sin el contorno del lote en la base no se cortan teselas (se avisa).
- Las carpetas ocultas `.tmp_`, `.previo_` y `.borrando_` son de trabajo; al abrir la biblioteca, lo que quedó en `PROCESANDO` de una
  ejecución anterior se marca `INTERRUMPIDO` (`OrtomosaicoStore.mantenimiento`).
- **Base de datos:** la tabla `ortomosaico` solo indexa (ver [`BASE_DE_DATOS.md`](./BASE_DE_DATOS.md)). Si la base falla al eliminar, los
  archivos se borran igual y se avisa; si no responde, la biblioteca se ve igual desde los archivos.
- **Eliminar** borra los archivos del ortomosaico y su fila; **no** toca videos ni registros del vuelo. No se puede eliminar uno que
  se está armando.

## Comparar fechas

Se eligen dos fechas del mismo lote (la más antigua va siempre como «antes»). El visor dibuja cada imagen en su sitio real del terreno. Antes de fiarse,
se avisa si tienen **resoluciones distintas**, si **solo se solapan en parte** o si la **ubicación puede variar** más de 5 m (`Comparacion.avisos`).

Cada fecha nueva de un lote se **alinea con la primera** (el ancla, que nunca se mueve): `Armado` pasa su carpeta como `referencia` (`EntradaArmado.referencia`:
la primera fecha lista, con `georef.json` y vista) y el módulo mide y corrige el corrimiento de conjunto y por bloques. Lo que queda de desacuerdo se mide y
**es el `error_estimado_m` de la ficha** (no una constante): 3,0 m (el del GPS) en la primera fecha o si no se pudo alinear. Reglas que conviene saber:

- Si se **vuelve a armar la primera fecha**, las demás conservan el alineado anterior (quedan alineadas con la versión vieja de la primera).
- Un ortomosaico armado antes de existir `georef.json` (parte 2) no sirve de referencia: la fecha siguiente se arma sin alinear y lo dice.
- Si el cultivo cambió tanto que no se encuentra un parecido claro, no se alinea y se avisa; no se inventa un corrimiento.

## Heurísticas de Nielsen aplicadas

| Heurística | Dónde se ve |
|---|---|
| 1. Visibilidad del estado | Conteos en las pestañas («Creados (3)», «Crear (2 listos)»); estado de cada vuelo y ortomosaico en insignias; «Cargando…»; espacio libre en la revisión; barra mientras se arma; aviso al terminar de eliminar con lo liberado |
| 2. Lenguaje del usuario | «Vuelo», «lote», «salida», «preprocesar»; sin términos como GSD, tesela o CRS en pantalla (la ficha los guarda, la pantalla dice «resolución», «cobertura del lote») |
| 3. Control y libertad | «← Historial» siempre; confirmar antes de eliminar con Cancelar; cada punto de la revisión lleva a donde se arregla; la selección de salidas es libre |
| 4. Consistencia | Mismas tarjetas, títulos, chips y colores que Procesamiento; las pestañas y la lista a la izquierda como el resto de la app |
| 5. Prevención de errores | Solo se pueden marcar salidas listas; el botón de armar se apaga si hay bloqueos; no se puede eliminar lo que se está armando; la revisión se hace *antes*, no después de fallar |
| 6. Reconocer antes que recordar | Miniaturas y fechas en el historial; ajustes con su explicación a la vista; los últimos ajustes se recuerdan |
| 7. Eficiencia | «Crear ortomosaico →» llega con el vuelo elegido; zoom con la rueda y arrastre; las salidas listas vienen marcadas |
| 8. Diseño minimalista | Cada pantalla muestra lo que se necesita para decidir; el detalle (abrir carpeta, tamaño) queda a un clic |
| 9. Ayudar con los errores | Mensajes con **causa y qué hacer** («Faltan las imágenes preprocesadas… Preprocesa su video…»); si algo no se puede mostrar, lo dice y ofrece abrir la carpeta |
| 10. Ayuda | Explicación bajo cada ajuste; textos en vacío que dicen cómo llenar la pantalla; información al pasar el cursor por los botones de solo ícono |

Los estados vacíos (sin ortomosaicos, sin vuelos, lote sin historial) siempre dicen qué es ese espacio y qué hacer para llenarlo.
