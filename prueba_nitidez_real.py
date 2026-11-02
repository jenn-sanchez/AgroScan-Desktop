# prueba_nitidez_real.py
# Script de prueba: muestra las imágenes menos nítidas del video real y su contexto.

from src import calidad, config

carpeta = config.CARPETA_DATOS / "salidas" / "DJI_real" / config.SUBCARPETA_FRAMES
rutas = sorted(carpeta.glob("*.jpg"))
print(f"Analizando {len(rutas)} imágenes...")

resultados, referencia = calidad.analizar_imagenes(rutas)
umbral = referencia["nitidez_norm"] * config.NITIDEZ_MINIMA_RELATIVA
print(f"Mediana de nitidez_norm: {referencia['nitidez_norm']:.3f} | umbral: {umbral:.3f}\n")

filas = []
for nombre, resultado in resultados.items():
    medidas = resultado["medidas"]
    relativa = medidas["nitidez_norm"] / referencia["nitidez_norm"]
    filas.append((relativa, nombre, medidas))
filas.sort()

print("Las 12 imágenes con menor nitidez (en % de la mediana):")
for relativa, nombre, medidas in filas[:12]:
    partes = nombre.replace(".jpg", "").split("_")
    etiqueta = f"{partes[-3]}_{partes[-2]}"
    print(
        f"{etiqueta}  nitidez {relativa * 100:5.1f}% | brillo {medidas['brillo']:5.1f} | "
        f"contraste {medidas['contraste']:5.1f} | desigualdad {medidas['desigualdad']:5.1f}% | "
        f"oscuro {medidas['pct_oscuro']:4.1f}%"
    )

marcadas = [
    nombre for nombre, resultado in resultados.items()
    if "poca_nitidez" in resultado["diagnostico"]["ajustes"]
]
print(f"\nImágenes marcadas con poca_nitidez (umbral actual): {len(marcadas)}")