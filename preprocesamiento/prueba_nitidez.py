import cv2
import numpy as np
from src import calidad, config

rutas = sorted(config.CARPETA_FRAMES_ORIGINALES.rglob("*.jpg"))
print(f"Imágenes: {len(rutas)}\n")

filas = []
for ruta in rutas:
    imagen = cv2.imread(str(ruta))
    medidas = calidad.medir_nitidez(imagen)
    partes = ruta.stem.split("_")
    filas.append({
        "pasada": partes[1],
        "nitidez": medidas["nitidez"],
        "relacion_xy": medidas["relacion_xy"],
    })
    print(f"{partes[1]}_{partes[2]}  nitidez {medidas['nitidez']:8.1f}  relación x/y {medidas['relacion_xy']:.3f}")

print("\n--- Resumen de toda la misión ---")
for nombre in ("nitidez", "relacion_xy"):
    valores = [fila[nombre] for fila in filas]
    print(
        f"{nombre}: mínimo {min(valores):.3f} | mediana {np.median(valores):.3f} | "
        f"máximo {max(valores):.3f} | variación (desv.) {np.std(valores):.3f}"
    )

print("\n--- Promedio por pasada ---")
for pasada in ("P1", "P2", "P3"):
    nitidez = [fila["nitidez"] for fila in filas if fila["pasada"] == pasada]
    relacion = [fila["relacion_xy"] for fila in filas if fila["pasada"] == pasada]
    print(f"{pasada}: nitidez {np.mean(nitidez):.1f} | relación x/y {np.mean(relacion):.3f}")