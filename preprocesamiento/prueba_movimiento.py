import cv2

from src import config, movimiento

rutas = sorted(config.CARPETA_FRAMES_ORIGINALES.glob("*.jpg"))
print(f"Frames encontrados: {len(rutas)}")

for i in range(10):
    ruta_a = rutas[i]
    ruta_b = rutas[i + 1]
    frame_a = cv2.imread(str(ruta_a))
    frame_b = cv2.imread(str(ruta_b))

    resultado = movimiento.estimar_movimiento(frame_a, frame_b)
    if resultado is None:
        print(f"{ruta_a.name} -> {ruta_b.name}: medición no confiable (pocos puntos)")
    else:
        alto, ancho = frame_a.shape[:2]
        solape = movimiento.calcular_solape(resultado, ancho, alto)
        print(f"{ruta_a.name} -> {ruta_b.name}: "
              f"dx={resultado['dx']:.1f}, dy={resultado['dy']:.1f}, "
              f"giro={resultado['angulo']:.2f}° "
              f"puntos={resultado['puntos_buenos']} "
              f"solape={solape:.0f}%")