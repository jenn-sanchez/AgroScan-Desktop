import cv2
import numpy as np
from src import calidad, config, mejora

rutas = sorted(config.CARPETA_FRAMES_ORIGINALES.rglob("*.jpg"))
print("Analizando las imágenes reales...")
resultados, referencia = calidad.analizar_imagenes(rutas)
modificadas = 0
for ruta in rutas:
    diagnostico = resultados[ruta.name]["diagnostico"]
    imagen = cv2.imread(str(ruta))
    mejorada, aplicados, diagnostico_final = mejora.mejorar_imagen(imagen, diagnostico, referencia)
    if not np.array_equal(imagen, mejorada):
        modificadas += 1
        print(f"  MODIFICADA: {ruta.name} -> {aplicados}")

print(f"Imágenes reales modificadas: {modificadas} de {len(rutas)}")
print("\n--- Casos combinados ---")
original = cv2.imread(str(rutas[11]))      # P1_I012
oscura_borrosa = cv2.GaussianBlur(cv2.convertScaleAbs(original, alpha=0.7, beta=0), (0, 0), 1.0)
casos = {
    "clara fuerte": cv2.convertScaleAbs(original, alpha=1.0, beta=50),
    "oscura + borrosa": oscura_borrosa,
    "poco contraste + oscura": cv2.convertScaleAbs(original, alpha=0.45, beta=10),
}

for nombre, imagen in casos.items():
    medidas = calidad.medir_todo(imagen)
    diagnostico = calidad.diagnosticar_imagen(medidas, referencia)
    mejorada, aplicados, diagnostico_final = mejora.mejorar_imagen(imagen, diagnostico, referencia)
    print(nombre)
    print(f"   diagnóstico inicial: {diagnostico['ajustes']}")
    print(f"   se aplicó          : {aplicados}")
    print(f"   queda pendiente    : {diagnostico_final['ajustes']}")