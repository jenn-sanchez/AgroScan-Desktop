# AgroScan - Módulo de preprocesamiento de imágenes aéreas

Toma un video MP4 de un dron, extrae los frames necesarios para un ortomosaico,
analiza su calidad, corrige solo lo necesario y guarda las imágenes finales con
sus metadatos dentro (EXIF).

## Instalación

    python -m venv venv
    venv\Scripts\Activate.ps1
    pip install -r requirements.txt

## Uso

Copiar un video MP4 en `data/videos/` y ejecutar:

    python main.py

## Estructura

- `main.py`: punto de entrada.
- `src/`: módulos (video, recorrido, selección de frames, metadatos, calidad, mejora).
- `data/`: videos y resultados (no se sube a Git).

## Estado

Sprints 1 a 6 completados. En desarrollo: interfaz y envío por API REST.

## Uso desde otra aplicación

    python main.py --video "RUTA\DJI_0104.MP4" --salida "RUTA\salida-1" --json

Escribe en la salida estándar una línea JSON por evento:

    {"tipo": "progreso", "porcentaje": 35, "etapa": "Extrayendo imágenes"}
    {"tipo": "error", "mensaje": "No existe el video: ..."}
    {"tipo": "fin", "porcentaje": 100, "resumen": {"imagenes_finales": 73, ...}}

- El log detallado sale por la salida de errores (stderr) y a `data/logs/`.
- Código de salida: 0 si terminó bien, 1 si hubo un error.
- Resultados: `<salida>/frames_originales/` e `<salida>/imagenes_procesadas/`.
  Las imágenes finales llevan sus metadatos dentro (EXIF); no hay archivos aparte.

- La aplicación debe leer o descartar la salida de errores (stderr) del proceso:
  si no se consume, el proceso podría bloquearse cuando ese canal se llene.
- `interfaz_prueba.py` es un cliente de ejemplo que cumple este contrato (lanza
  main.py con --json y lee las líneas), además de mostrar el aspecto deseado.

### Envío al servidor (opcional)

    python main.py --video ... --salida ... --servidor http://HOST:PUERTO --json

Después de generar las imágenes finales, las sube con `POST /api/imagenes`
(multipart/form-data: `archivo` y `mision`) y confirma cada una con su huella
SHA-256. Si no hay conexión, quedan pendientes. El estado de cada imagen se guarda
en `<salida>/estado_sincronizacion.json`. Para reintentar solo lo pendiente:

    python main.py --salida ... --servidor ... --solo-sincronizar --json

El resultado aparece en `resumen.sincronizacion`:
`{"enviadas": N, "pendientes": N, "con_error": N, "sin_conexion": true|false}`.