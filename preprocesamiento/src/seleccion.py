from src import config
from src import video
from src.recorrido import clasificar_paso

def _agregar_frame(elegidos, frame, pasada, sentido, solape):
    for elegido in elegidos:
        if elegido["frame"] == frame:
            return
    elegidos.append({
        "frame": frame,
        "pasada": pasada,
        "sentido": sentido,
        "solape": solape,
    })

def seleccionar_frames(pasos):
    avance_necesario = 100 - config.SOLAPE_FRONTAL_MINIMO_PCT
    paso_frames = config.PASO_MEDICION_FRAMES

    elegidos = []
    acumulado = 0.0
    fase_actual = None
    numero_pasada = 0
    ultimo_dy = 0.0
    frame_ultimo_paso = None

    for paso in pasos:
        clase = clasificar_paso(paso)

        if clase == "no confiable" and fase_actual is not None:
            clase = fase_actual
            dy = ultimo_dy
        elif clase in ("avance", "retroceso"):
            dy = paso["dy"]
        else:
            dy = 0.0

        if clase != fase_actual:
            if fase_actual is not None:
                _agregar_frame(elegidos, frame_ultimo_paso, numero_pasada,
                               fase_actual, 100 - acumulado)

            if clase in ("avance", "retroceso"):
                fase_actual = clase
                numero_pasada += 1
                _agregar_frame(elegidos, paso["frame"] - paso_frames, numero_pasada,
                               fase_actual, None)
            else:
                fase_actual = None
            acumulado = 0.0

        if fase_actual is not None:
            if acumulado + abs(dy) > avance_necesario:
                _agregar_frame(elegidos, paso["frame"] - paso_frames, numero_pasada,
                               fase_actual, 100 - acumulado)
                acumulado = 0.0

            acumulado += abs(dy)
            ultimo_dy = dy
            frame_ultimo_paso = paso["frame"]

    if fase_actual is not None:
        _agregar_frame(elegidos, frame_ultimo_paso, numero_pasada,
                       fase_actual, 100 - acumulado)

    return elegidos

def seleccionar_por_intervalo(total_frames, fps, intervalo_segundos):
    salto = video.calcular_salto_frames(fps, intervalo_segundos)
    elegidos = []
    for frame in range(0, total_frames, salto):
        elegidos.append({
            "frame": frame,
            "pasada": 1,
            "sentido": "intervalo fijo",
            "solape": None,
        })
    return elegidos