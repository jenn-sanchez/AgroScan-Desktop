import cv2
import exifread
import numpy as np
from PIL import Image
from src import config

MISION = "AgroScan-mision-2026-10-26"
carpeta_originales = config.CARPETA_FRAMES_ORIGINALES / MISION
carpeta_finales = config.CARPETA_IMAGENES_PROCESADAS / MISION

CAMPOS_REQUERIDOS = ["XPTitle", "XPComment", "XPSubject", "Software", "DateTimeOriginal"]

def leer_comentarios(ruta):
    exif = Image.open(ruta).getexif()
    valor = exif.get(0x9C9C)
    if isinstance(valor, tuple):
        valor = bytes(valor)
    if isinstance(valor, bytes):
        return valor.decode("utf-16-le").rstrip("\x00")
    return ""
rutas = sorted(carpeta_finales.glob("*.jpg"))
print(f"Imágenes finales: {len(rutas)}")
campos_faltantes = 0
sin_calidad = 0
pixeles_distintos = 0

for ruta in rutas:
    with open(ruta, "rb") as archivo:
        etiquetas = exifread.process_file(archivo, details=False)
    nombres = " ".join(etiquetas.keys())
    faltan = [campo for campo in CAMPOS_REQUERIDOS if campo not in nombres]
    if len(faltan) > 0:
        campos_faltantes += 1
        print(f"  {ruta.name}: faltan {faltan}")

    comentarios = leer_comentarios(ruta)
    if "Ajustes aplicados" not in comentarios or "Advertencias" not in comentarios:
        sin_calidad += 1
        print(f"  {ruta.name}: sin información de calidad")

    original = cv2.imread(str(carpeta_originales / ruta.name))
    final = cv2.imread(str(ruta))
    if not np.array_equal(original, final):
        pixeles_distintos += 1
        print(f"  {ruta.name}: píxeles distintos a la original")

print(f"\nCon campos faltantes: {campos_faltantes}")
print(f"Sin información de calidad: {sin_calidad}")
print(f"Con píxeles distintos a la original: {pixeles_distintos}")

print("\n--- Ejemplos de comentarios ---")
for ruta in rutas:
    if "_P1_I001_" in ruta.name or "_P3_I004_" in ruta.name:
        print(f"\n{ruta.name}")
        print(leer_comentarios(ruta))