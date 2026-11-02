import cv2
import numpy as np
from src import calidad, config, mejora

referencia = {"brillo": 103.7, "contraste": 53.6, "nitidez_norm": 0.44}
rutas = sorted(config.CARPETA_FRAMES_ORIGINALES.rglob("*.jpg"))
original = cv2.imread(str(rutas[11]))     
casos = {
    "original": original,
    "desenfoque ligero": cv2.GaussianBlur(original, (0, 0), 1.0),
    "desenfoque moderado": cv2.GaussianBlur(original, (0, 0), 2.0),
    "desenfoque fuerte": cv2.GaussianBlur(original, (0, 0), 4.0),
}

carpeta_pruebas = config.CARPETA_IMAGENES_PROCESADAS / "pruebas"
carpeta_pruebas.mkdir(exist_ok=True)
print(f"Umbral de nitidez_norm: {referencia['nitidez_norm'] * config.NITIDEZ_MINIMA_RELATIVA:.3f}\n")

for nombre, imagen in casos.items():
    medidas = calidad.medir_todo(imagen)
    diagnostico = calidad.diagnosticar_imagen(medidas, referencia)
    mejorada, aplicados = mejora.realzar_nitidez(imagen, diagnostico)
    medidas_despues = calidad.medir_todo(mejorada)
    diagnostico_despues = calidad.diagnosticar_imagen(medidas_despues, referencia)
    print(f"{nombre}")
    print(f"   antes  : nitidez_norm {medidas['nitidez_norm']:.3f} -> {diagnostico['ajustes']}")
    print(f"   se aplicó: {aplicados}")
    print(f"   después: nitidez_norm {medidas_despues['nitidez_norm']:.3f} -> {diagnostico_despues['ajustes']}")
    print(f"   imagen sin cambios: {np.array_equal(imagen, mejorada)}")
    nombre_archivo = nombre.replace(" ", "_")
    cv2.imwrite(str(carpeta_pruebas / f"{nombre_archivo}_antes.jpg"), imagen)
    cv2.imwrite(str(carpeta_pruebas / f"{nombre_archivo}_despues.jpg"), mejorada)

print(f"\nImágenes de comparación guardadas en: {carpeta_pruebas}")