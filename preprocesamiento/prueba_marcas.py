import cv2
from src import movimiento, video
ruta_video = video.buscar_videos()[0]

pares = [
    (80, 147), (147, 214), (214,285), (285, 352), #pasada 1
    (1388, 1452), (1452, 1522), (1522, 1590), #pasada 3 reversa
    (1122, 1302), #fin pasada 1 -> inicio pasada 3
]

for cuadro_a, cuadro_b in pares:
    frame_a = video.leer_frame(ruta_video, cuadro_a)
    frame_b = video.leer_frame(ruta_video, cuadro_b)

    frame_a = cv2.resize(frame_a, None, fx=0.25, fy=0.25)
    frame_b = cv2.resize(frame_b, None, fx=0.25, fy=0.25)
    resultado = movimiento.estimar_movimiento(frame_a, frame_b)
    
    if resultado is None:
        print(f"{cuadro_a} -> {cuadro_b}: medición no confiable (pocos puntos)")
    else:
        alto, ancho = frame_a.shape[:2]    
        print(
            f"dx={resultado['dx'] / ancho * 100:.1f}% del ancho ",
            f"dy={resultado['dy'] / alto * 100:.1f}% del alto ",
            f"giro={resultado['angulo']:.2f}° ",
            f"puntos={resultado['puntos_buenos']}"
        )