# Módulo de ortomosaicos de AgroScan

Arma el ortomosaico de un vuelo a partir de las imágenes que dejó el módulo de [preprocesamiento](../preprocesamiento) y del registro de
vuelo del teléfono. La app de escritorio lo ejecuta por detrás; **todo en esta PC, sin internet ni servidor**. Lo que entrega para el módulo de IA
está en [`docs/IA_ORTOMOSAICOS.md`](../docs/IA_ORTOMOSAICOS.md).

```
python main.py --entrada entrada.json --salida carpeta [--json]
```

## Qué hace

1. **Lee la orden** (`entrada.json`, formato en [`src/entrada.py`](./src/entrada.py)).
2. **Ubica cada imagen** ([`src/ubicacion.py`](./src/ubicacion.py)). El preprocesamiento deja en el EXIF de cada imagen el segundo del video de
   donde salió; las **marcas del video** de `eventos.jsonl` (instante del registro ↔ segundo del video) dan el inicio de la grabación (la
   mediana de todas, para que una marca rara no mueva nada), y con eso `registro.csv` da posición, altura y rumbo de ese instante. Se descartan
   las imágenes tomadas fuera del barrido (ascenso, tránsito, giro), con la cámara inclinada más de 10° o a menos de 3 m de altura. La zona UTM sale
   del lote (la misma en todas las fechas).
3. **Ajusta las imágenes entre sí** ([`src/ajuste.py`](./src/ajuste.py)). Es el paso que más importa para que el mosaico salga **nítido y sin partes
   repetidas**. Cada imagen se coloca donde dice el GPS y con el tamaño que da la altura del registro, y eso falla por tres motivos:
   - **El relieve (el más grande).** La altura del registro es **sobre el punto de despegue, no sobre el suelo que hay debajo de cada imagen**. En un vuelo
     hecho desde una loma, o en un lote en ladera o con quebradas, la altura real sobre el suelo puede ser mucho mayor o menor y cambia de una zona a otra.
     Con la altura equivocada cada imagen cubre un tamaño de terreno equivocado, y al mezclarlas salen borrosas y dobles. En un vuelo real desde una loma el
     registro decía 33 m sobre la copa y por las imágenes eran 46–59 m (imágenes 1,4–1,7 veces más grandes de lo calculado); con ese error el desacuerdo
     entre imágenes vecinas era de 4,4 m, y tras corregirlo, de 0,2 m.
   - El GPS se equivoca unos metros y la brújula unos grados.
   Cada imagen se rectifica a «norte arriba» (así las pasadas de ida y de vuelta se parecen), se buscan puntos del terreno que aparecen en dos vecinas
   (SIFT + RANSAC) y se estima, para **cada imagen, su corrimiento, su giro y su escala** (la altura real sobre el suelo) por mínimos cuadrados no lineales.
   La escala se puede deducir porque la distancia entre los GPS de dos imágenes cercanas es una regla conocida: cuánto se desplazan los mismos puntos entre
   ellas, comparado con esa distancia, da el tamaño real del píxel. Detalles que importan:
   - Se busca primero un factor de escala **de todo el vuelo** a lo ancho (×0,3 a ×5) para arrancar cerca, y luego se ajusta todo a la vez; cada imagen puede
     apartarse de ese factor solo ±15 % (el relieve cambia despacio).
   - El GPS es el **ancla** (sin puntos en común una imagen se queda donde estaba) y el error de GPS, de brújula y de escala se suponen **suaves en el tiempo**
     (dos imágenes seguidas se equivocan casi igual). Sin esto el ajuste explica un error de rumbo moviendo imágenes y deforma el mosaico.
   - Si la escala encontrada se aparta más de 15 % de la del registro, **se avisa al usuario**: «el registro dice 33 m pero por las imágenes estuvo entre 46 y
     59 m (¿se despegó desde una loma?)».
   - **No toca nada** si ya coinciden, y **se descarta con una advertencia** si no mejora, si pide mover o cambiar de tamaño las imágenes de forma
     irrazonable o si son pocas las que tienen puntos en común (terreno muy uniforme, imágenes borrosas): el mosaico queda «directo» por GPS (`motor = directo-gps`).
   - Mejora la **coherencia entre imágenes**. **No corrige la deriva lenta del GPS de todo el bloque**: eso solo lo corrige otra fecha (paso 5).
4. **Arma el lienzo** ([`src/lienzo.py`](./src/lienzo.py)): una cuadrícula en metros (UTM, norte arriba). Cada imagen se apoya plana sobre el terreno:
   centro donde estaba el dron, girada según su rumbo y escalada según su altura sobre la copa (`altura − altura_copa_m`) y el campo de visión de la
   cámara (por defecto DJI Mini 3 en video: 45.8° vertical), **corregida por el ajuste del paso 3**. Donde se superponen, manda casi solo la imagen que
   ve ese punto más de frente (peso `(1 − distancia al borde)^40`, es decir, la más cercana a su centro): mezclar vistas distintas del mismo punto con relieve y
   árboles altos lo vuelve borroso y doble.
5. **Alinea con la primera fecha del lote** ([`src/referencia.py`](./src/referencia.py)), si la orden trae `referencia`. Cada vuelo tiene su propio error
   de GPS y el mismo árbol cae en lugares distintos en dos fechas. Se compara la vista nueva con la de la primera fecha (correlación de fase sobre los
   bordes): primero el corrimiento de conjunto y después, por bloques de 16 m, cómo cambia de un lugar a otro (un campo suave, por regresión de kernel
   sobre los bloques medidos: sigue variaciones de unos metros en unas decenas de metros y lejos de todo bloque se desvanece a cero), en hasta 3 vueltas.
   Si la correlación de todo junto no es clara (el corrimiento cambia de una zona a otra y el pico sale emborronado) pero hay bloques claros suficientes, se
   parte de la mediana de esos bloques. Después se **mide lo que queda** de desacuerdo, y eso es el `error_estimado_m` de la ficha (en vez de una constante). Si no se puede
   (referencia sin `georef.json`, casi sin superposición, sin parecido claro) no se mueve nada y se avisa. **La primera fecha es el ancla y no se mueve.**
6. **Escribe** ([`src/salida.py`](./src/salida.py), [`src/cuadricula.py`](./src/cuadricula.py)): `ortomosaico.tif` (GeoTIFF RGBA en teselas internas y
   comprimido, escrito por bandas para que un lote grande no necesite el lienzo entero en memoria), `vista.jpg`, `miniatura.jpg`, `georef.json`,
   `cobertura.geojson` y, si se conoce el contorno del lote, las **teselas para la IA** (`teselas/` y `teselas.csv`): una cuadrícula fija anclada al
   polígono del lote (512 × 512 px a 4 cm, 20,48 m), la misma en todas las fechas.

**Resolución** según la calidad: `rapida` = 4× el tamaño de píxel de las imágenes, `estandar` = 2×, `alta` = 1×. Un lote muy grande baja de
resolución para no pasar de 150 millones de píxeles (con una advertencia). Las teselas siempre salen a 4 cm.

### Lo que no hace

- **Corrige el tamaño de cada imagen por el relieve, pero no el relieve en sí**: es una ortofoto aproximada, no un modelo del terreno. Cada imagen se supone
  plana a la altura que estimó; dentro de ella, los árboles altos y los desniveles se ven inclinados hacia el borde (paralaje). Por eso en el mosaico manda la
  imagen más de frente, y aun así pueden verse costuras donde cambia la imagen que manda.
- **No iguala el brillo ni el color** entre imágenes ni entre fechas.
- **La precisión absoluta es la del GPS** (unos 3 m): el mosaico entero puede estar corrido respecto del mapa real. Lo que se corrige es la coherencia
  entre imágenes y entre fechas, no la posición contra el mapa.

## Contrato con la app (`--json`)

Una línea JSON por evento en la salida estándar; el registro detallado va a la salida de errores.

```
{"tipo": "progreso", "porcentaje": 42, "etapa": "Ajustando las imágenes entre sí"}
{"tipo": "fin", "resumen": {imagenes_usadas, imagenes_total, cobertura_pct, area_ha, gsd_cm_px, crs, limites{oeste,sur,este,norte},
                            error_estimado_m, motor, ancho_px, alto_px, descartadas{motivo: n},
                            ajuste{aplicado, pares, fotos_con_pares, rms_antes_m, rms_despues_m, desplazamiento_mediano_m, giro_maximo_grados},
                            alineado_con{vuelo, este_m, norte_m, respuesta, bloques, desacuerdo_m} | null,
                            teselas{escritas, sin_imagen, fuera_del_lote} | null, advertencias[]}}
{"tipo": "error", "mensaje": "…"}     ← para el usuario: qué pasó y qué hacer
```

Código de salida: 0 bien, 1 problema del usuario (falta el registro, video de otra salida…), 2 fallo inesperado del módulo. La app lee esto
en [`OrtomosaicoModule.kt`](../desktopApp/src/main/kotlin/org/agroscan/project/features/ortomosaico/OrtomosaicoModule.kt) y escribe ella la
ficha `ortomosaico.json`; el módulo solo deja sus archivos en `--salida`. Todas las imágenes se leen y escriben por bytes
([`src/imagen.py`](./src/imagen.py)): `cv2.imread` no abre rutas con tildes o ñ en Windows (`C:/Users/José/…`).

## Programando

Una vez en cada PC (necesita [Python 3.12](https://www.python.org/downloads/)), desde la raíz del repositorio:

```
./gradlew prepararOrtomosaico
```

Crea `ortomosaico/venv` con las librerías de `requirements.txt` y PyInstaller. Con `./gradlew :desktopApp:run` la app usa este código con ese
Python. Para las pruebas (`pyproj` compara las coordenadas contra una librería de referencia y `pytest`):

```
ortomosaico\venv\Scripts\python -m pip install -r ortomosaico\requirements-dev.txt
ortomosaico\venv\Scripts\python -m pytest ortomosaico
```

Las pruebas inventan vuelos con la verdad conocida ([`tests/vuelo_sintetico.py`](./tests/vuelo_sintetico.py)): un terreno con marcas asimétricas, un dron
que lo recorre en pasadas con su registro y sus marcas, y errores de GPS y de brújula **correlacionados en el tiempo** como los reales. Con eso se
comprueba que el mosaico se parece al terreno (y no a su espejo) y queda donde está; que el ajuste baja el error entre imágenes vecinas (de 1,5–2,5 m a
0,3–0,7 m en las pruebas); que una fecha con un sesgo de 5 m se alinea con la primera y que las teselas son las mismas celdas en las dos.

En el instalador, `empaquetarOrtomosaico` (que ejecutan `packageMsi` y `createDistributable`) lo convierte en `agroscan-ortomosaico.exe` con su
propio Python: quien instala AgroScan no necesita Python.

## Medido

Con **vuelos reales** (tres vuelos de un dron DJI Mini 3 sobre fincas con relieve; los dos de Finca Ponce 2 son dos fechas del mismo lote):

| Vuelo | Lo que pasaba | Resultado |
|---|---|---|
| Prueba casa (48 imágenes, 0,97 ha) | El registro decía 33 m sobre la copa; las imágenes indican 47–59 m (volado desde una loma). Con el tamaño que daba el registro, el desacuerdo entre vecinas era de 4,4 m y el cauce salía **duplicado** | Desacuerdo 0,23 m; cauce único y nítido; se avisa al usuario de la diferencia de altura |
| Finca Ponce 2, fecha 1 (291 imágenes, 13,6 ha) | La altura real sobre el suelo iba de ~40 a ~85 m dentro del mismo vuelo (el registro decía 33–66 m); desacuerdo 6,0 m | Copas, curvas de siega y caminos nítidos y continuos |
| Finca Ponce 2, fecha 2 (89 imágenes, vuelo a 70 m), alineada con la fecha 1 | Alturas reales de 46 a 89 m. Antes la alineación se rendía («sin parecido claro») porque el corrimiento entre fechas cambia de una zona a otra (de 0 a ~3 m) y la correlación de todo junto da un pico emborronado | Se alinea (394 bloques, 3 vueltas); queda un desacuerdo **medido de 1,9 m**, que es el `error_estimado_m` y se avisa al usuario. Es el límite de un mosaico sin modelo de terreno: dosel de 20 m visto desde 60 y 70 m |

Con **vuelos sintéticos** (verdad conocida):

| | |
|---|---|
| 836 imágenes de 2688 × 1512, ajuste entre sí | 46 s, 0,23 GB de memoria |
| 1104 imágenes, GeoTIFF de 150 millones de píxeles | 62 s, 257 MB, 1,4 GB de memoria |
| Error entre imágenes vecinas, con GPS de ±2–3 m y rumbo 2–4° mal | de 1,5–2,5 m a 0,3–0,7 m |
| Una fecha con un sesgo de GPS de 5 m, tras alinear con la primera | desacuerdo medido 0,2–0,6 m; correlación del mosaico con la primera 0,97–0,99 |

## Pendiente de validar

Lo del relieve y la nitidez se comprobó con tres vuelos reales (arriba). **Lo demás se midió solo con vuelos inventados**, y falta confirmar con vuelos reales:

- Que, cuando el dron **parte el video en varios archivos**, el segundo siga donde terminó el primero (se asume; los videos se encadenan por su duración).
- Que el EXIF de las imágenes traiga «Segundo del video» (lo escribe el módulo de preprocesamiento; se comprobó con su código, no con un vuelo).
- El campo de visión (45.8° vertical, el de la app del teléfono) y el retraso entre el video y el registro: si el mosaico sale corrido en la dirección
  del vuelo, es el retraso; si sale demasiado grande o chico, es el campo de visión.
- **El ajuste en un platanal de hileras muy repetidas**: en los vuelos reales probados (pasto, monte, banano disperso) encontró puntos en común; un cultivo muy
  regular puede dar coincidencias falsas. El ajuste es robusto (les baja el peso) y se descarta con aviso si no mejora, pero conviene probarlo.
- **Que la alineación entre fechas aguante el cambio del cultivo** (crecimiento, poda, cosecha): la correlación se hace sobre bordes, que cambian poco, pero
  no se probó con un cultivo que cambie de verdad. Si no encuentra un parecido claro no alinea y lo dice.
