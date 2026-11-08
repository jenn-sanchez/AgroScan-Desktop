# Ortomosaicos para el módulo de IA

Para quien haga el análisis con IA (conteo de plantas, sanidad, cambios en el tiempo…): qué entrega el módulo de ortomosaicos, en qué
formato, dónde está, qué precisión tiene y qué **no** se puede suponer. Todo corre en la PC del usuario, sin servidor.

## Lo que se recibe

Un **ortomosaico por vuelo**, y cada lote (polígono) tiene su historial de vuelos a lo largo del tiempo. Del ortomosaico lo que le sirve a la IA son
las **teselas**: recortes de 512 × 512 píxeles a 4 cm por píxel (20,48 m de lado) cortados por una **cuadrícula fija por lote**, la misma en todas las
fechas. La celda `f002_c005` es el mismo pedazo de terreno hoy y dentro de tres meses.

```
Documentos\AgroScan\ortomosaicos\<lote>_<8 primeros del id del lote>\<AAAA-MM-DD>_<8 primeros del id del vuelo>\
  ortomosaico.json      la ficha: estado, lote, vuelo, fecha, cobertura, resolución, error estimado…   ← empezar por aquí
  teselas.csv           índice de las teselas (una fila por tesela)
  teselas\f000_c001.jpg …   las teselas
  georef.json           dónde está todo sobre el terreno (UTM exacto) y la cuadrícula del lote
  cobertura.geojson     contorno de lo que cubre el mosaico y contorno del lote (lat/lon)
  ortomosaico.tif       el mosaico completo, GeoTIFF RGBA (alfa 0 = sin imagen); pesa cientos de MB
  vista.jpg, miniatura.jpg   para mostrar en pantalla; no son para analizar
```

**No hay una ruta guardada en la base de datos**: la carpeta se encuentra por los identificadores (`*_<id lote[:8]>\*_<id vuelo[:8]>`) o con el código de la app
(`OrtomosaicoStore.listar()` / `porVuelo(vueloUuid)` en `features/ortomosaico/`). La base solo indexa (tabla `ortomosaico`, ver `docs/BASE_DE_DATOS.md`); si la base
y la carpeta discrepan, **manda la carpeta** (`ortomosaico.json`).

Solo usar los ortomosaicos con `"estado": "LISTO"` (los otros estados son `PROCESANDO`, `ERROR`, `CANCELADO`, `INTERRUMPIDO`).

```sql
SELECT o.vuelo_id, l.nombre AS lote, v.inicio AS fecha_del_vuelo, o.cobertura_pct, o.gsd_cm_px, o.error_estimado_m, o.crs
FROM ortomosaico o JOIN lote l ON l.id = o.lote_id JOIN vuelo v ON v.id = o.vuelo_id
WHERE o.estado = 'LISTO' AND o.lote_id = :lote ORDER BY v.inicio;      -- el historial de un lote, de la fecha más antigua a la más reciente
```

## `teselas.csv`

UTF-8, separador coma, una fila por tesela, con encabezado.

| Columna | Qué es |
|---|---|
| `id` | `f<fila>_c<columna>` (3 dígitos), igual al nombre del archivo sin `.jpg`. **Es la clave para emparejar fechas.** |
| `fila`, `columna` | Posición en la cuadrícula del lote; la fila 0 es la del norte y la columna 0 la del oeste |
| `archivo` | Ruta relativa a la carpeta del ortomosaico: `teselas/f000_c001.jpg` |
| `en_lote_pct` | % de la tesela que cae dentro del polígono del lote (solo salen las de 5 % o más) |
| `cobertura_pct` | % de **lo que cae dentro del lote** que tiene imagen (solo salen las de 30 % o más) |
| `este_min`, `norte_min`, `este_max`, `norte_max` | Esquinas de la tesela en metros UTM (zona de `georef.json → crs`) |
| `oeste`, `sur`, `este`, `norte` | Rectángulo que la contiene, en grados (WGS84) |
| `lat_centro`, `lon_centro` | Centro de la tesela, en grados |

Cada tesela es un JPG de 512 × 512 en color. **Donde no hay imagen es negro puro** (`0,0,0`); `cobertura_pct` dice cuánto del lote falta. Los píxeles
**fuera del lote** sí traen imagen (son lo que el dron vio alrededor): si el análisis es solo del lote hay que enmascararlos con el polígono (`cobertura.geojson`,
feature `tipo = "lote"`, o la tabla `vertice`).

## `georef.json`

```json
{"v": 1, "crs": "EPSG:32717", "este0": 663123.4, "norte1": 9966541.8, "gsd_m": 0.0293, "ancho_px": 8421, "alto_px": 6112,
 "vista": {"gsd_m": 0.0817, "ancho_px": 3072, "alto_px": 2230},
 "cuadricula": {"crs": "EPSG:32717", "este0": 663100, "norte1": 9966600, "gsd_m": 0.04, "lado_px": 512, "lado_m": 20.48, "columnas": 6, "filas": 5}}
```

- `este0`, `norte1` son la esquina **noroeste** (borde de arriba a la izquierda) del `ortomosaico.tif`, en metros UTM; los píxeles son cuadrados de `gsd_m` y el norte
  queda arriba. Es la misma información que lleva el GeoTIFF; este archivo evita tener que abrirlo.
- `cuadricula` es la rejilla de teselas. La celda `(fila, columna)` cubre este = `este0 + columna·lado_m … + lado_m` y norte = `norte1 − fila·lado_m … − lado_m`.
  Es `null` si no se conocía el contorno del lote (no hay teselas).
- **Antes de emparejar teselas de dos fechas comprueba que la `cuadricula` de las dos sea idéntica.** Lo es mientras no se edite el polígono del lote; si se
  edita, la rejilla cambia y las celdas dejan de corresponder.
- La zona UTM sale del lote (no del vuelo): es la misma en todas las fechas.

## Cómo leerlo

```python
import csv, json
from pathlib import Path
from PIL import Image

carpeta = Path(r"C:\Users\...\Documents\AgroScan\ortomosaicos\Finca-Sanchez_1a2b3c4d\2026-10-26_9f8e7d6c")
ficha = json.loads((carpeta / "ortomosaico.json").read_text(encoding="utf-8"))
assert ficha["estado"] == "LISTO"
for fila in csv.DictReader(open(carpeta / "teselas.csv", encoding="utf-8")):
    if float(fila["cobertura_pct"]) < 80:       # criterio de la IA: aquí se descarta lo poco cubierto
        continue
    img = Image.open(carpeta / fila["archivo"])  # 512×512 RGB
    ...                                          # inferencia; el resultado se guarda con fila["id"] como clave
```

## Lo que NO se puede suponer (precisión)

- **Precisión absoluta: la del GPS, unos 3 m.** El mosaico está bien armado por dentro pero puede estar corrido unos metros respecto del mapa real. No sirve para
  decir «este árbol está exactamente en tal coordenada»; sí para «esta tesela / esta zona».
- **Entre fechas:** desde la segunda fecha de un lote, cada ortomosaico se alinea con la **primera** (el ancla, que no se mueve). `error_estimado_m` de la ficha es
  entonces el **desacuerdo medido** con esa primera fecha (típicamente decímetros; puede ser mayor y la app lo avisa). En la primera fecha, o si no se pudo alinear,
  vale 3,0 (el del GPS). En dos vuelos reales de un mismo lote con árboles altos (a 60 y 70 m) el desacuerdo medido fue de **1,9 m**: es lo que se puede esperar con dosel alto
  y sin modelo del terreno. **Si `error_estimado_m` es mayor que un par de metros, no compares planta por planta entre fechas.** Comparar teselas completas (clasificar,
  contar, medir un índice por tesela) sí es razonable aunque haya un desacuerdo de decímetros.
- **Es la copa, no el suelo.** Es una ortofoto aproximada, no un modelo del terreno. Se corrige el **tamaño** de cada imagen por el relieve (la altura del registro es sobre el
  punto de despegue; en una loma el suelo puede estar decenas de metros más abajo y el módulo lo estima y lo avisa), pero no el relieve en sí: los árboles altos y los desniveles
  se ven inclinados hacia el borde de cada imagen, y donde cambia la imagen que manda puede haber costuras. Mejora si el lote es plano y el vuelo tiene mucho traslape.
- **Costuras y brillo:** las imágenes se mezclan pero **no se iguala su brillo ni su color**: puede haber cambios de tono donde se unen, y entre fechas el color cambia
  con la luz del día. Si el modelo es sensible a eso, normalizar en la IA.
- **Se probó con tres vuelos reales y con vuelos sintéticos.** Los reales validan la nitidez y la corrección por relieve; el alineado entre fechas y las cifras de precisión entre
  fechas se midieron sobre todo con datos inventados. `ortomosaico/README.md` lista lo que falta validar.
- **Solo cultivo visible desde arriba, en RGB:** no hay infrarrojo ni otras bandas. Las imágenes son cuadros de **video** (resolución y compresión de video), no fotos fijas.

## Dónde guardar los resultados de la IA (propuesta, a acordar)

La app no tiene todavía dónde guardar lo que la IA produzca. Propuesta para no mezclarlo con los datos del módulo de ortomosaicos y poder borrarlo sin tocar el mosaico:

- Una carpeta `ia\<modelo>_<versión>\` **dentro de la carpeta del ortomosaico**, con un `resultados.csv` (o `.json`) cuya clave sea el `id` de la tesela y una ficha con el
  modelo, su versión y la fecha. Así «eliminar un ortomosaico» también elimina su análisis, y un cambio de modelo no pisa el anterior.
- Para la comparación en el tiempo basta unir por `id` los `resultados.csv` de las fechas del mismo lote (con la misma `cuadricula`).
- Si se quiere consultar en la pantalla de IA sin abrir archivos, añadir una tabla a la base (`ia_resultado`: `ortomosaico (vuelo_id)`, `tesela_id`, `modelo`, `version`,
  `valor`/`json`) como índice, igual que `ortomosaico`: **los archivos son la fuente de verdad y la base solo indexa**.

## Preguntas que conviene resolver con quien hace los ortomosaicos

1. ¿Qué formato de modelo se va a ejecutar en la PC (ONNX, PyTorch, otro) y se empaqueta como `.exe` aparte, como los otros dos módulos (`preprocesamiento/`, `ortomosaico/`)?
2. ¿La IA necesita **máscara del lote** dentro de cada tesela o le basta con `en_lote_pct`? (Se puede añadir una máscara por tesela si hace falta.)
3. ¿Resolución de 4 cm por píxel y 512 px es lo que espera el modelo? Cambia `TESELA_GSD_M` / `TESELA_LADO_PX` en `ortomosaico/src/config.py`; las teselas ya hechas no se
   regeneran solas (habría que volver a armar los ortomosaicos).
