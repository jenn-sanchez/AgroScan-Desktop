import math
import cv2
import numpy as np
from src import config

def estimar_movimiento(frame_a, frame_b):
    gris_a = cv2.cvtColor(frame_a, cv2.COLOR_BGR2GRAY)
    gris_b = cv2.cvtColor(frame_b, cv2.COLOR_BGR2GRAY)

    orb = cv2.ORB_create(nfeatures=1500)
    puntos_a, descriptores_a = orb.detectAndCompute(gris_a, None)
    puntos_b, descriptores_b = orb.detectAndCompute(gris_b, None)

    if descriptores_a is None or descriptores_b is None:
        return None

    comparador = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)
    emparejados = comparador.match(descriptores_a, descriptores_b)

    if len(emparejados) < 10:
        return None

    origen = np.float32([puntos_a[m.queryIdx].pt for m in emparejados])
    destino = np.float32([puntos_b[m.trainIdx].pt for m in emparejados])

    matriz, mascara = cv2.estimateAffinePartial2D(origen, destino, method=cv2.RANSAC)

    if matriz is None:
        return None

    puntos_buenos = int(mascara.sum())
    if puntos_buenos < config.MIN_PUNTOS_BUENOS:
        return None

    alto, ancho = gris_a.shape
    centro_x = ancho / 2
    centro_y = alto / 2
    nuevo_x = matriz[0, 0] + centro_x + matriz[0, 1] * centro_y + matriz[0, 2]
    nuevo_y = matriz[1, 0] * centro_x + matriz[1, 1] * centro_y + matriz[1, 2]

    movimiento = {
        "dx": nuevo_x - centro_x,
        "dy": nuevo_y - centro_y,
        "angulo": math.degrees(math.atan2(matriz[1, 0], matriz[0, 0])),
        "puntos_buenos": puntos_buenos,
    }
    return movimiento

def calcular_solape(movimiento, ancho, alto):
    solape_x = max(0, 1 - abs(movimiento["dx"]) / ancho)
    solape_y = max(0, 1 - abs(movimiento["dy"]) / alto)
    return solape_x * solape_y *100