import cv2
import numpy as np
from src import calidad, config

rutas = sorted(config.CARPETA_FRAMES_ORIGINALES.rglob("*.jpg"))
print(f"Imágenes: {len(rutas)}\n")

filas = []
for ruta in rutas:
    imagen = cv2.imread(str(ruta))
    medidas = calidad.medir_brillo_contraste(imagen)
    partes = ruta.stem.split("_")
    filas.append({
        "etiqueta": f"{partes[1]}_{partes[2]}",
        "pasada": partes[1],
        "brillo": medidas["brillo"],
        "contraste": medidas["contraste"],
    })
    print(f"{partes[1]}_{partes[2]}  brillo {medidas['brillo']:6.1f}  contraste {medidas['contraste']:5.1f}")

print("\n--- Resumen de toda la misión ---")
for nombre in ("brillo", "contraste"):
    valores = [fila[nombre] for fila in filas]
    print(
        f"{nombre}: mínimo {min(valores):.1f} | mediana {np.median(valores):.1f} | "
        f"máximo {max(valores):.1f} | variación (desv.) {np.std(valores):.1f}"
    )

print("\n--- Promedio por pasada ---")
for pasada in ("P1", "P2", "P3"):
    brillos = [fila["brillo"] for fila in filas if fila["pasada"] == pasada]
    contrastes = [fila["contraste"] for fila in filas if fila["pasada"] == pasada]
    print(f"{pasada}: brillo {np.mean(brillos):.1f} | contraste {np.mean(contrastes):.1f}")
