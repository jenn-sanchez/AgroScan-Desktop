import cv2
import numpy as np
from src import config, calidad

def corregir_brillo_contraste(imagen, diagnostico, referencia):
    ajustes = diagnostico["ajustes"]
    aplicados = []

    if "oscura" not in ajustes and "clara" not in ajustes and "poco_contraste" not in ajustes:
        return imagen, aplicados

    ycrcb = cv2.cvtColor(imagen, cv2.COLOR_BGR2YCrCb)
    y = ycrcb[:, :, 00].astype(np.float32)

    if "oscura" in ajustes or "clara" in ajustes:
        ganancia = referencia["brillo"] / y.mean()
        ganancia = min(max(ganancia, config.GANANCIA_MINIMA), config.GANANCIA_MAXIMA)
        y = y * ganancia
        aplicados.append(f"brillo x{ganancia:.2f}")

    if "poco_contraste" in ajustes or len(aplicados) > 0:
        contraste_actual = y.std()
        limite = max(config.CONTRASTE_MINIMO, referencia["contraste"] * (1 - config.TOLERANCIA_CONTRASTE))
        if contraste_actual < limite:
            factor = referencia["contraste"] / contraste_actual
            factor = min(max(factor, 1.0), config.FACTOR_CONTRASTE_MAXIMO)
            media = y.mean()
            y = (y - media) * factor + media
            aplicados.append(f"contraste x {factor:.2f}")

    ycrcb[:, :, 0] = np.clip(y, 0, 255).astype(np.uint8)
    resultado = cv2.cvtColor(ycrcb, cv2.COLOR_YCrCb2BGR)
    return resultado, aplicados

def realzar_nitidez(imagen, diagnostico):
    if "poca_nitidez" not in diagnostico["ajustes"]:
        return imagen, []

    ycrcb = cv2.cvtColor(imagen, cv2.COLOR_BGR2YCrCb)
    y = ycrcb[:, :, 0].astype(np.float32)
    sigma = config.REALCE_SIGMA * imagen.shape[1] / 3840
    desenfocada = cv2.GaussianBlur(y, (0, 0), sigma)
    realzada = y + config.REALCE_CANTIDAD * (y - desenfocada)
    ycrcb[:, :, 0] = np.clip(realzada, 0, 255).astype(np.uint8)
    resultado = cv2.cvtColor(ycrcb, cv2.COLOR_YCrCb2BGR)
    return resultado, [f"nitidez (cantidad {config.REALCE_CANTIDAD:.1f})"]

def mejorar_imagen(imagen, diagnostico, referencia):
    aplicados = []
    resultado, ajustes_luz = corregir_brillo_contraste(imagen, diagnostico, referencia)
    aplicados.extend(ajustes_luz)
    resultado, ajustes_nitidez = realzar_nitidez(resultado, diagnostico)
    aplicados.extend(ajustes_nitidez)

    if len(aplicados) > 0:
        medidas_finales = calidad.medir_todo(resultado)
        diagnostico_final = calidad.diagnosticar_imagen(medidas_finales, referencia)
    else:
        diagnostico_final = diagnostico
    return resultado, aplicados, diagnostico_final