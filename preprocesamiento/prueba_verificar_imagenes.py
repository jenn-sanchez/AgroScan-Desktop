import exifread
from PIL import Image
from src import config

campos_requeridos = ["XPTitle", "XPComment", "XPSubject", "Software", "DateTimeOriginal"]
rutas = sorted(config.CARPETA_FRAMES_ORIGINALES.rglob("*.jpg"))
print(f"Imágenes encontradas: {len(rutas)}")
con_problemas = 0
for ruta in rutas:
    with open(ruta, "rb") as archivo:
        etiquetas = exifread.process_file(archivo, details=False)
    nombres = " ".join(etiquetas.keys())
    faltantes = []
    for campo in campos_requeridos:
        if campo not in nombres:
            faltantes.append(campo)

    if len(faltantes) > 0:
        con_problemas += 1
        print(f"  {ruta.name}: faltan {faltantes}")

print(f"Imágenes con todos los campos: {len(rutas) - con_problemas}")
print(f"Imágenes con campos faltantes: {con_problemas}")

primera = Image.open(rutas[0])
print(f"\nTamaño de la primera imagen: {primera.size[0]}x{primera.size[1]}")
print(f"Peso total de la carpeta: {sum(r.stat().st_size for r in rutas) / 1_000_000:.0f} MB")