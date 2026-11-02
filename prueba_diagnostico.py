import cv2
from src import calidad, config

rutas = sorted(config.CARPETA_FRAMES_ORIGINALES.rglob("*.jpg"))
print(f"Midiendo {len(rutas)} imágenes (puede tardar un par de minutos)...")
medidas_por_imagen = []
for ruta in rutas:
    imagen = cv2.imread(str(ruta))
    medidas_por_imagen.append(calidad.medir_todo(imagen))

referencia = calidad.calcular_referencia(medidas_por_imagen)
print("Referencia de la misión (medianas):")
for nombre, valor in referencia.items():
    print(f"  {nombre}: {valor:.1f}")

print("\n--- Diagnóstico de las imágenes reales ---")
con_ajustes = 0
con_advertencias = 0
for ruta, medidas in zip(rutas, medidas_por_imagen):
    diagnostico = calidad.diagnosticar_imagen(medidas, referencia)
    partes = ruta.stem.split("_")
    etiqueta = f"{partes[1]}_{partes[2]}"

    if diagnostico["ajustes"]:
        con_ajustes += 1
        print(f"  {etiqueta}: AJUSTES {diagnostico['ajustes']}")
    if diagnostico["advertencias"]:
        con_advertencias += 1
        print(f"  {etiqueta}: advertencias {diagnostico['advertencias']}")

print(f"\nImágenes que necesitarían ajustes: {con_ajustes} de {len(rutas)}")
print(f"Imágenes con alguna advertencia: {con_advertencias} de {len(rutas)}")

print("\n--- Prueba con una imagen estropeada a propósito ---")
original = cv2.imread(str(rutas[11]))    
casos = {
    "original": original,
    "más oscura": cv2.convertScaleAbs(original, alpha=0.5, beta=0),
    "más clara": cv2.convertScaleAbs(original, alpha=1.0, beta=100),
    "poco contraste": cv2.convertScaleAbs(original, alpha=0.4, beta=60),
    "borrosa": cv2.GaussianBlur(original, (0, 0), 4),
}

for nombre, imagen in casos.items():
    medidas = calidad.medir_todo(imagen)
    diagnostico = calidad.diagnosticar_imagen(medidas, referencia)
    print(
        f"{nombre:15s} brillo {medidas['brillo']:5.1f} | "
        f"contraste {medidas['contraste']:5.1f} | nitidez_norm {medidas['nitidez_norm']:.4f}"
    )
    print(f"{'':15s} -> ajustes {diagnostico['ajustes']} | advertencias {diagnostico['advertencias']}")