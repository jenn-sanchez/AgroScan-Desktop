import cv2
import numpy as np
from src import calidad, config

rutas = sorted(config.CARPETA_FRAMES_ORIGINALES.rglob("*.jpg"))
print(f"Imágenes: {len(rutas)}\n")

NOMBRES = ("desigualdad", "esquinas_vs_centro", "pct_quemado", "pct_oscuro")

filas = []
for ruta in rutas:
    imagen = cv2.imread(str(ruta))
    medidas = calidad.medir_iluminacion(imagen)
    partes = ruta.stem.split("_")
    fila = {"pasada": partes[1]}
    for nombre in NOMBRES:
        fila[nombre] = medidas[nombre]
    filas.append(fila)

    print(
        f"{partes[1]}_{partes[2]} desigualdad {medidas['desigualdad']:5.1f}% "
        f"esquinas/centro {medidas['esquinas_vs_centro']:6.1f}%  "
        f"quemado {medidas['pct_quemado']:5.2f}%  oscuro {medidas['pct_oscuro']:5.2f}%"
    )

print("\n--- Resumen de toda la misión ---")
for nombre in NOMBRES:
    valores = [fila[nombre] for fila in filas]
    print(
        f"{nombre}: mínimo {min(valores):.2f} | mediana {np.median(valores):.2f} | "
        f"máximo {max(valores):.2f} | variación (desv.) {np.std(valores):.2f}"
    )

print("\n--- Promedio por pasada ---")
for pasada in ("P1", "P2", "P3"):
    texto = f"{pasada}:"
    for nombre in NOMBRES:
        valores = [fila[nombre] for fila in filas if fila["pasada"] == pasada]
        texto += f" {nombre} {np.mean(valores):.2f} |"
    print(texto)