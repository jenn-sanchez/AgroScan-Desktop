"""Alinear una fecha con la primera del lote: lo que permite comparar el cultivo a lo largo del tiempo.

Cada vuelo tiene su propio error de GPS (unos metros, distinto cada día y que varía despacio dentro del lote), así que el mismo árbol cae en
lugares distintos en dos fechas. Aquí se mide ese corrimiento comparando la vista del mosaico nuevo con la de la fecha de referencia
(correlación de fase sobre los bordes, que cambian poco aunque el cultivo crezca): primero el corrimiento de conjunto y después, por bloques,
cómo cambia de un lugar a otro. La primera fecha del lote es el ancla: no se mueve.
"""
import json
import math
from dataclasses import dataclass

import cv2
import numpy as np

from src import config, imagen
from src.entrada import Referencia
from src.lienzo import Marco


@dataclass
class Alineado:
    aplicado: bool
    advertencia: str | None = None
    este_m: float = 0.0        # cuánto hay que correr el mosaico nuevo en conjunto (más al este si es positivo)
    norte_m: float = 0.0
    respuesta: float = 0.0     # confianza de la correlación de conjunto, 0–1
    campo: object = None       # además del conjunto: (este, norte) → (corrimiento este, corrimiento norte), la deriva local; None si no se pudo medir
    bloques: int = 0           # con cuántos bloques se ajustó el campo


class _Comparacion:
    """Las dos vistas (la de referencia y la nueva) sobre un lienzo común, listas para medir cuánto se corre una respecto de la otra."""

    def __init__(self, pa, pb, solape, g, comun):
        self.pa, self.pb, self.solape, self.g, self.comun = pa, pb, solape, g, comun


def _a_gris_en(img: np.ndarray, valida: np.ndarray, origen_este: float, origen_norte: float, g_origen: float, comun: Marco):
    """Pasa una vista al lienzo común: (gris float32, válida bool)."""
    s = g_origen / comun.g
    m = np.array([[s, 0, (origen_este - comun.este0) / comun.g], [0, s, (comun.norte1 - origen_norte) / comun.g]])
    if s < 1:
        img = cv2.resize(img, (max(2, round(img.shape[1] * s)), max(2, round(img.shape[0] * s))), interpolation=cv2.INTER_AREA)
        valida = cv2.resize(valida.astype(np.uint8), (img.shape[1], img.shape[0]), interpolation=cv2.INTER_NEAREST)
        m = np.array([[1, 0, m[0, 2]], [0, 1, m[1, 2]]])
    gris = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32)
    out = cv2.warpAffine(gris, m, (comun.ancho, comun.alto), flags=cv2.INTER_LINEAR, borderValue=0)
    ok = cv2.warpAffine(valida.astype(np.uint8), m, (comun.ancho, comun.alto), flags=cv2.INTER_NEAREST, borderValue=0) > 0
    return out, ok


def _leer_referencia(ref: Referencia, crs: str):
    """(vista BGR, válida, georef) de la referencia, o el motivo por el que no se puede usar."""
    georef_json, vista_jpg = ref.carpeta / "georef.json", ref.carpeta / "vista.jpg"
    if not georef_json.is_file() or not vista_jpg.is_file():
        return "La fecha de referencia no tiene su georreferencia guardada (se armó con una versión anterior); no se pudo alinear con ella."
    try:
        georef = json.loads(georef_json.read_text(encoding="utf-8"))
        vista = imagen.leer(vista_jpg, cv2.IMREAD_COLOR)
        if vista is None or georef["vista"]["gsd_m"] <= 0:
            raise ValueError("vista ilegible")
    except (OSError, ValueError, KeyError, TypeError) as e:
        return f"No se pudo leer la fecha de referencia ({e}); no se pudo alinear con ella."
    if georef.get("crs") != crs:
        return "La fecha de referencia está en otra zona UTM; no se pudo alinear con ella."
    fondo = np.array(config.FONDO_VISTA[::-1], np.int16)
    valida = np.abs(vista.astype(np.int16) - fondo).max(axis=2) > 10
    valida = cv2.morphologyEx(valida.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8)) > 0
    return vista, valida, georef


def _comparar(bgr, alfa, marco_vista: Marco, ref: Referencia, crs: str):
    """La `_Comparacion` de la nueva contra la referencia, o el motivo (texto) por el que no se puede comparar."""
    leida = _leer_referencia(ref, crs)
    if isinstance(leida, str):
        return leida
    vista_ref, valida_ref, georef = leida
    g_ref = float(georef["vista"]["gsd_m"])
    ref_e0, ref_n1 = float(georef["este0"]), float(georef["norte1"])

    g = max(g_ref, marco_vista.g, config.ALINEADO_GSD_MIN_M)
    este0, norte1 = min(ref_e0, marco_vista.este0), max(ref_n1, marco_vista.norte1)
    este1 = max(ref_e0 + vista_ref.shape[1] * g_ref, marco_vista.este1)
    norte0 = min(ref_n1 - vista_ref.shape[0] * g_ref, marco_vista.norte0)
    while (este1 - este0) / g * (norte1 - norte0) / g > 16_000_000:
        g *= 1.25
    comun = Marco(este0, norte1, g, math.ceil((este1 - este0) / g), math.ceil((norte1 - norte0) / g))

    a, ok_a = _a_gris_en(vista_ref, valida_ref, ref_e0, ref_n1, g_ref, comun)
    b, ok_b = _a_gris_en(bgr, alfa > 0, marco_vista.este0, marco_vista.norte1, marco_vista.g, comun)
    solape = ok_a & ok_b
    menor = min(int(ok_a.sum()), int(ok_b.sum()))
    if menor == 0 or solape.sum() < config.ALINEADO_MIN_SOLAPE_PCT / 100 * menor:
        return "La fecha de referencia y esta casi no se superponen; no se pudo alinear con ella."

    # Solo cuenta lo que las dos ven; se queda con los bordes (se quita lo que cambia despacio: el brillo y el color general)
    def preparar(x):
        alto = x * solape - cv2.GaussianBlur(x * solape, (0, 0), config.ALINEADO_SIGMA_PX)
        alto *= solape
        return (alto / (float(alto[solape].std()) + 1e-6)).astype(np.float32)

    return _Comparacion(preparar(a), preparar(b), solape, g, comun)


def _desplazamiento(pa, pb):
    """((dx, dy) en píxeles de cuánto está corrido b respecto de a, respuesta 0–1)."""
    ventana = cv2.createHanningWindow((pa.shape[1], pa.shape[0]), cv2.CV_32F)
    return cv2.phaseCorrelate(pa, pb, ventana)


def _bloques(c: _Comparacion, pb: np.ndarray | None = None, min_respuesta: float | None = None) -> np.ndarray:
    """El corrimiento medido por separado en bloques del terreno: una fila (este del centro, norte del centro, corrimiento este, corrimiento
    norte, respuesta) por cada bloque con superposición y parecido suficientes. El corrimiento es el de la vista nueva (o de [pb], si se da)
    respecto de la de referencia (positivo = más al este / más al norte)."""
    pb = c.pb if pb is None else pb
    minimo = config.ALINEADO_BLOQUE_MIN_RESPUESTA if min_respuesta is None else min_respuesta
    n = max(32, round(config.ALINEADO_BLOQUE_M / c.g))
    filas = []
    for y in range(0, c.pa.shape[0] - n + 1, n // 2):
        for x in range(0, c.pa.shape[1] - n + 1, n // 2):
            if c.solape[y:y + n, x:x + n].mean() < config.ALINEADO_BLOQUE_MIN_SOLAPE:
                continue
            (dx, dy), respuesta = _desplazamiento(c.pa[y:y + n, x:x + n], pb[y:y + n, x:x + n])
            if respuesta < minimo:
                continue
            filas.append((c.comun.este0 + (x + n / 2) * c.g, c.comun.norte1 - (y + n / 2) * c.g, dx * c.g, -dy * c.g, respuesta))
    return np.array(filas).reshape(-1, 5)


def _ajustar_campo(restos: np.ndarray):
    """El corrimiento que falta aplicar, como función suave de la posición, a partir de los bloques medidos. `restos` trae (este, norte, corrimiento
    este, corrimiento norte, respuesta) de lo que queda por corregir tras el corrimiento de conjunto. Devuelve (función, bloques usados) o None.

    Es una regresión por kernel gaussiano (promedio de los bloques cercanos, los más parecidos pesan más): sigue variaciones de unos metros en
    unas decenas de metros, que un polinomio no puede; y lejos de todo bloque la corrección se desvanece a cero (queda solo el conjunto) en vez
    de extrapolarse."""
    # Lo que queda tras el corrimiento de conjunto es pequeño: un bloque que pide mucho más es una coincidencia falsa, no deriva del GPS
    b = restos[np.hypot(restos[:, 2], restos[:, 3]) <= config.ALINEADO_BLOQUE_DESVIO_M]
    if len(b) < config.ALINEADO_MIN_BLOQUES:
        return None
    pos, val, resp = b[:, :2], b[:, 2:4], b[:, 4]
    h2 = 2 * config.ALINEADO_ANCHO_CAMPO_M**2
    d2_bloques = ((pos[:, None, :] - pos[None, :, :]) ** 2).sum(-1)

    def evaluar(p: np.ndarray, usar: np.ndarray, quitar_propio: bool = False) -> np.ndarray:
        d2 = d2_bloques if quitar_propio else ((p[:, None, :] - pos[None, :, :]) ** 2).sum(-1)
        w = resp[None, :] * np.exp(-d2 / h2) * usar[None, :]
        if quitar_propio:
            np.fill_diagonal(w, 0.0)
        total = w.sum(axis=1)
        medio = (w @ val) / np.maximum(total, 1e-12)[:, None]
        return medio * np.clip(total / config.ALINEADO_PESO_MIN_CAMPO, 0.0, 1.0)[:, None]

    usar = np.ones(len(b), bool)
    for _ in range(3):      # los bloques que no concuerdan con sus vecinos (calculado sin contarse a sí mismos) se quitan y se vuelve a ajustar
        resto = np.hypot(*(val - evaluar(pos, usar, quitar_propio=True)).T)
        nuevo = resto <= max(config.ALINEADO_BLOQUE_RESTO_MIN_M, 3 * 1.4826 * float(np.median(resto[usar])))
        if nuevo.sum() < config.ALINEADO_MIN_BLOQUES or (nuevo == usar).all():
            break
        usar = nuevo

    def campo(este, norte):
        d = evaluar(np.column_stack([np.atleast_1d(np.asarray(este, float)), np.atleast_1d(np.asarray(norte, float))]), usar)
        return d[:, 0], d[:, 1]

    return campo, int(usar.sum())


def alinear(bgr: np.ndarray, alfa: np.ndarray, marco_vista: Marco, ref: Referencia, crs: str) -> Alineado:
    """Cómo hay que correr el mosaico nuevo (su vista [bgr]/[alfa] sobre [marco_vista]) para que coincida con la referencia.

    Primero un corrimiento de conjunto (lo que más pesa y lo más fiable); luego, si hay bloques suficientes, un campo suave que corrige
    además la deriva del GPS entre un vuelo y otro, que no es la misma en todo el lote.
    """
    c = _comparar(bgr, alfa, marco_vista, ref, crs)
    if isinstance(c, str):
        return Alineado(False, c)
    ys, xs = np.where(c.solape)
    (dx, dy), respuesta = _desplazamiento(c.pa[ys.min():ys.max() + 1, xs.min():xs.max() + 1], c.pb[ys.min():ys.max() + 1, xs.min():xs.max() + 1])
    if respuesta < config.ALINEADO_MIN_RESPUESTA:
        # Si el corrimiento cambia de una zona a otra, la correlación de todo junto da un pico emborronado y se rinde; pero los bloques sueltos
        # pueden ser claros. Se parte entonces de lo que dice la mayoría de los bloques más parecidos.
        fuertes = _bloques(c, min_respuesta=config.ALINEADO_BLOQUE_FUERTE)
        if len(fuertes) < config.ALINEADO_MIN_BLOQUES_FUERTES:
            return Alineado(False, "No se encontró un parecido claro con la fecha de referencia (¿cambió mucho el cultivo?); no se alineó con ella.", respuesta=float(respuesta))
        dx, dy = float(np.median(fuertes[:, 2])) / c.g, -float(np.median(fuertes[:, 3])) / c.g
        respuesta = float(np.median(fuertes[:, 4]))
    # (dx, dy): cuánto está corrido la nueva respecto de la referencia, en píxeles; para alinear hay que correrla al revés. El norte crece hacia arriba.
    este_m, norte_m = -dx * c.g, dy * c.g
    if math.hypot(este_m, norte_m) > config.ALINEADO_MAX_M:
        return Alineado(False, f"La fecha de referencia pedía correr el mosaico {math.hypot(este_m, norte_m):.0f} m, más de lo razonable para un error de GPS; no se alineó con ella.", respuesta=float(respuesta))

    # Con la vista ya corrida por el conjunto, lo que se mide en cada bloque es lo que falta. Si un bloque falla (y la correlación devuelve
    # «cero»), eso significa «el conjunto estaba bien», que es inofensivo; medir los bloques sin esto los dejaba arrastrar el ajuste.
    corrida = cv2.warpAffine(c.pb, np.float32([[1, 0, -dx], [0, 1, -dy]]), (c.pb.shape[1], c.pb.shape[0]), flags=cv2.INTER_LINEAR, borderValue=0)
    campo, usados = None, 0
    restos = _bloques(c, corrida)
    if len(restos):
        a_aplicar = restos.copy()                        # lo que hay que aplicar es lo opuesto a lo medido
        a_aplicar[:, 2], a_aplicar[:, 3] = -restos[:, 2], -restos[:, 3]
        ajustado = _ajustar_campo(a_aplicar)
        if ajustado:
            base, usados = ajustado
            # El campo corrige lo que falta; el corrimiento de conjunto va aparte
            def campo(este, norte, _base=base):
                de, dn = _base(este, norte)
                tope = config.ALINEADO_CAMPO_MAX_M
                return np.clip(de, -tope, tope) + este_m, np.clip(dn, -tope, tope) + norte_m
    return Alineado(True, None, float(este_m), float(norte_m), float(respuesta), campo, usados)


def residuo(bgr: np.ndarray, alfa: np.ndarray, marco_vista: Marco, ref: Referencia, crs: str) -> tuple[float, int] | None:
    """Cuánto sigue corrido el mosaico nuevo respecto de la referencia: (desacuerdo cuadrático medio en metros, bloques medidos), o None."""
    c = _comparar(bgr, alfa, marco_vista, ref, crs)
    if isinstance(c, str):
        return None
    # Solo bloques con un parecido claro: uno que falla devuelve «cero» y haría parecer que todo coincide
    b = _bloques(c, min_respuesta=config.ALINEADO_RESIDUO_MIN_RESPUESTA)
    if len(b) < config.ALINEADO_MIN_BLOQUES:
        return None
    return float(np.sqrt(np.mean(b[:, 2] ** 2 + b[:, 3] ** 2))), len(b)
