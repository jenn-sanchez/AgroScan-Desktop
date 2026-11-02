import cv2
from src import movimiento, video

PASO = 16
INICIO = 80
FIN = 352

ruta_video = video.buscar_videos()[0]

def reducir(frame):
    return cv2.resize(frame, None, fx=0.25, fy=0.25)

frame_anterior = reducir(video.leer_frame(ruta_video, INICIO))
acumulado = 0.0
no_confiables = 0

for numero in range(INICIO + PASO, FIN + 1, PASO):
    frame_actual = reducir(video.leer_frame(ruta_video, numero))
    resultado = movimiento.estimar_movimiento(frame_anterior, frame_actual)

    if resultado is None:
        no_confiables += 1
        print(f"frame {numero}: medición no confiable")
    else:
        alto, ancho = frame_actual.shape[:2]
        dy = resultado["dy"] / alto * 100
        acumulado += dy
        print(
            f"frame {numero}: dy={dy:.1f}% dx={resultado['dx'] / ancho *100:.1f}% "
            f"giro={resultado['angulo']:.2f}° puntos={resultado['puntos_buenos']}  "
            f"acumulado={acumulado:.1f}%"
        )
        frame_anterior = frame_actual

print(f"\nTotal acumulado: {acumulado:.1f}% | pasos no confiables: {no_confiables}")