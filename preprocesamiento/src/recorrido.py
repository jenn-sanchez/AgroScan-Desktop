import cv2
import json
from src import config, movimiento

def reducir(frame):
    return cv2.resize(frame, None, fx=config.ESCALA_MEDICION, fy=config.ESCALA_MEDICION)

def medir_recorrido(ruta_video, paso_frames, al_avanzar=None):
    captura = cv2.VideoCapture(str(ruta_video))
    total = int(captura.get(cv2.CAP_PROP_FRAME_COUNT))
    pasos = []
    frame_previo = None
    indice = 0
    ultimo_porcentaje = -1

    while True:
        hay_frame, frame = captura.read()
        if not hay_frame:
            break

        if al_avanzar is not None and total > 0:
            porcentaje = int(100 * indice / total)
            if porcentaje != ultimo_porcentaje:
                ultimo_porcentaje = porcentaje
                al_avanzar(min(indice / total, 1.0))

        if indice % paso_frames == 0:
            frame_reducido = reducir(frame)

            if frame_previo is not None:
                resultado = movimiento.estimar_movimiento(frame_previo, frame_reducido)
                paso = {"frame": indice, "confiable": resultado is not None}

                if resultado is not None:
                    alto, ancho = frame_reducido.shape[:2]
                    paso["dx"] = resultado["dx"] / ancho * 100
                    paso["dy"] = resultado["dy"] / alto * 100
                    paso["angulo"] = resultado["angulo"]
                    paso["puntos"] = resultado["puntos_buenos"]

                pasos.append(paso)
            frame_previo = frame_reducido
        indice += 1

    captura.release()
    return pasos

def clasificar_paso(paso):
    if not paso["confiable"]:
        return "no confiable"
    if paso["dy"] >= config.UMBRAL_VERTICAL:
        return "avance"
    if paso["dy"] <= -config.UMBRAL_VERTICAL:
        return "retroceso"
    if abs(paso["dx"]) >= config.UMBRAL_LATERAL:
        return "lateral"
    return "quieto"

def guardar_pasos(pasos, ruta_archivo):
    with open(ruta_archivo, "w", encoding="utf-8") as archivo:
        json.dump(pasos, archivo, indent=2)

def cargar_pasos(ruta_archivo):
    with open(ruta_archivo, "r", encoding="utf-8") as archivo:
        return json.load(archivo)
