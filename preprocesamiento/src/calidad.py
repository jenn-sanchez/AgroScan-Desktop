import cv2
import numpy as np
from src import config

def medir_brillo_contraste(imagen):
    gris = cv2.cvtColor(imagen, cv2.COLOR_BGR2GRAY)
    return{
        "brillo": float(gris.mean()),
        "contraste": float(gris.std()),
    }

def medir_nitidez(imagen):
    gris = cv2.cvtColor(imagen, cv2.COLOR_BGR2GRAY)
    lapraciano = cv2.Laplacian(gris, cv2.CV_64F)
    nitidez = lapraciano.var()
    contraste = gris.std()
    if contraste > 0:
        nitidez_norm = nitidez / contraste **2
    else:
        nitidez_norm = 0.0

    gradiente_x = cv2.Sobel(gris, cv2.CV_64F, 1, 0)
    gradiente_y = cv2.Sobel(gris, cv2.CV_64F, 0, 1)
    energia_x = np.abs(gradiente_x).mean()
    energia_y = np.abs(gradiente_y).mean()
    return{
        "nitidez": float(lapraciano.var()),
        "nitidez_norm": float(nitidez_norm),
        "relacion_xy": float(energia_x / energia_y),
    }

def medir_iluminacion(imagen):
    gris = cv2.cvtColor(imagen, cv2.COLOR_BGR2GRAY)
    alto, ancho = gris.shape

    bloques = []
    for fila in range(3):
        for columna in range(3):
            y1 = alto * fila // 3
            y2 = alto * (fila + 1) // 3
            x1 = ancho * columna // 3
            x2 = ancho * (columna + 1) // 3
            bloques.append(gris[y1:y2, x1:x2].mean())

    promedio = np.mean(bloques)
    desigualdad = np.std(bloques) / promedio * 100
    centro = bloques[4]
    esquinas = (bloques[0] + bloques[2] + bloques[6] + bloques[8]) / 4

    return{
        "desigualdad": float(desigualdad),
        "esquinas_vs_centro": float(esquinas / centro * 100),
        "pct_quemado": float((gris >= 250).mean() * 100),
        "pct_oscuro": float((gris <=5).mean() * 100),
    }

def medir_todo(imagen):
    medidas = {}
    medidas.update(medir_brillo_contraste(imagen))
    medidas.update(medir_nitidez(imagen))
    medidas.update(medir_iluminacion(imagen))
    return medidas

def calcular_referencia(lista_medidas):
    referencia = {}
    for nombre in ("brillo", "contraste", "nitidez_norm"):
        valores = [medidas[nombre] for medidas in lista_medidas]
        referencia[nombre] = float(np.median(valores))
    return referencia

def diagnosticar_imagen(medidas, referencia):
    ajustes = []
    advertencias = []

    brillo = medidas["brillo"]
    brillo_ref = referencia["brillo"]
    if brillo < config.BRILLO_MINIMO or brillo < brillo_ref * (1 - config.TOLERANCIA_BRILLO):
        ajustes.append("oscura")
    elif brillo > config.BRILLO_MAXIMO or brillo > brillo_ref * (1 + config.TOLERANCIA_BRILLO):
        ajustes.append("clara")

    contraste = medidas["contraste"]
    contraste_ref = referencia["contraste"]
    if contraste < config.CONTRASTE_MINIMO or contraste < contraste_ref * (1 - config.TOLERANCIA_CONTRASTE):
        ajustes.append("poco_contraste")

    if medidas["nitidez_norm"] < referencia["nitidez_norm"] * config.NITIDEZ_MINIMA_RELATIVA:
        ajustes.append("poca_nitidez")

    if medidas["pct_quemado"] > config.PCT_EXTREMOS_MAXIMO:
        advertencias.append("zonas_quemadas")
    if medidas["pct_oscuro"] > config.PCT_EXTREMOS_MAXIMO:
        advertencias.append("zonas_oscuras")
    if medidas["desigualdad"] > config.DESIGUALDAD_MAXIMA:
        advertencias.append("iluminacion_desigual")
    if medidas["esquinas_vs_centro"] < config.ESQUINAS_MINIMO:
        advertencias.append("esquinas_oscuras")

    return {"ajustes": ajustes, "advertencias": advertencias}

def analizar_imagenes(rutas, al_avanzar=None):
    medidas_por_imagen = []
    for numero, ruta in enumerate(rutas, start=1):
        imagen = cv2.imread(str(ruta))
        medidas_por_imagen.append(medir_todo(imagen))
        if al_avanzar is not None:
            al_avanzar(numero / len(rutas))
    referencia = calcular_referencia(medidas_por_imagen)
    resultados = {}
    for ruta, medidas in zip(rutas, medidas_por_imagen):
        resultados[ruta.name] = {
            "medidas": medidas,
            "diagnostico": diagnosticar_imagen(medidas, referencia),
        }
    return resultados, referencia