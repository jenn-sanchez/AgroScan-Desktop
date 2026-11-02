import cv2
import numpy as np
from src import calidad, config, mejora

referencia = {"brillo": 103.7, "contraste": 53.6, "nitidez_norm": 0.44}
rutas = sorted(config.CARPETA_FRAMES_ORIGINALES.rglob("*.jpg"))
original = cv2.imread(str(rutas[11]))      

casos = {
    "original": original,
    "oscura moderada": cv2.convertScaleAbs(original, alpha=0.7, beta=0),
    "oscura extrema": cv2.convertScaleAbs(original, alpha=0.5, beta=0),
    "clara moderada": cv2.convertScaleAbs(original, alpha=1.0, beta=35),
    "clara fuerte": cv2.convertScaleAbs(original, alpha=1.0, beta=50),
    "poco contraste mod.": cv2.convertScaleAbs(original, alpha=0.65, beta=35),
    "poco contraste extr.": cv2.convertScaleAbs(original, alpha=0.4, beta=60),
}

carpeta_pruebas = config.CARPETA_IMAGENES_PROCESADAS / "pruebas"
carpeta_pruebas.mkdir(exist_ok=True)

for nombre, imagen in casos.items():
    medidas = calidad.medir_todo(imagen)
    diagnostico = calidad.diagnosticar_imagen(medidas, referencia)

    mejorada, aplicados = mejora.corregir_brillo_contraste(imagen, diagnostico, referencia)

    medidas_despues = calidad.medir_todo(mejorada)
    diagnostico_despues = calidad.diagnosticar_imagen(medidas_despues, referencia)

    print(f"{nombre}")
    print(f"   antes : brillo {medidas['brillo']:5.1f} contraste {medidas['contraste']:5.1f} -> {diagnostico['ajustes']}")
    print(f"   se aplicó: {aplicados}")
    print(f"   después: brillo {medidas_despues['brillo']:5.1f} contraste {medidas_despues['contraste']:5.1f} -> {diagnostico_despues['ajustes']}")
    print(f"   imagen sin cambios: {np.array_equal(imagen, mejorada)}")

    nombre_archivo = nombre.replace(" ", "_").replace(".", "")
    cv2.imwrite(str(carpeta_pruebas / f"{nombre_archivo}_antes.jpg"), imagen)
    cv2.imwrite(str(carpeta_pruebas / f"{nombre_archivo}_despues.jpg"), mejorada)

print(f"\nImágenes de comparación guardadas en: {carpeta_pruebas}")