"""Ajuste de las imágenes entre sí: corregir lo que el GPS, la brújula y la altura del registro no aciertan.

Cada imagen se coloca donde el GPS dice, con el tamaño que da la altura del registro. Pero:
  - el GPS se equivoca unos metros y la brújula unos grados;
  - sobre todo, **la altura del registro es sobre el punto de despegue, no sobre el suelo que hay debajo de cada imagen**. Con relieve (un vuelo desde
    una loma, un lote en ladera o con quebradas) la altura real sobre el suelo puede ser el doble o la mitad, y eso cambia el tamaño de cada imagen sobre
    el terreno: mezcladas con el tamaño equivocado salen borrosas y con partes repetidas.

Aquí se buscan puntos del terreno que aparecen en dos imágenes vecinas (SIFT) y se estima, para cada imagen, cuánto hay que corrimiento, giro y
**escala** (la altura real sobre el suelo) para que esos puntos coincidan, por mínimos cuadrados. La escala se puede deducir porque la distancia entre las
posiciones del GPS de dos imágenes cercanas es una regla conocida: cuánto se desplazan los mismos puntos entre ellas, comparado con esa distancia, da el
tamaño real del píxel. El GPS sigue siendo el ancla: sin puntos en común una imagen se queda como estaba, y el conjunto no se desplaza como un todo.

Para comparar se rectifica cada imagen a «norte arriba» a baja resolución: así dos imágenes de pasadas opuestas (el dron vuelve en sentido
contrario) quedan con la misma orientación y los puntos se reconocen sin ayuda de rotación.
"""
import math
from dataclasses import dataclass

import cv2
import numpy as np

from src import config, imagen
from src.lienzo import Marco, _esquinas_m, matriz
from src.ubicacion import Foto


@dataclass
class Ajuste:
    aplicado: bool
    advertencia: str | None = None     # por qué no se pudo ajustar, si era de esperar que se pudiera
    pares: int = 0
    fotos_con_pares: int = 0
    rms_antes_m: float = 0.0           # cuánto no coincidían los puntos en común antes de ajustar
    rms_despues_m: float = 0.0
    desplazamiento_mediano_m: float = 0.0
    giro_maximo_grados: float = 0.0
    escala_mediana: float = 1.0        # cuánto hubo que agrandar (>1) o achicar (<1) las imágenes: la altura real sobre el suelo / la del registro
    altura_min_m: float = 0.0          # altura estimada sobre lo que se ve (copa), tras el ajuste
    altura_max_m: float = 0.0
    altura_registro_m: float = 0.0     # la que decía el registro (sobre la copa), para compararla
    atipicas: int = 0                  # imágenes cuya corrección salió absurda (pocas coincidencias) y se sustituyó por la de sus vecinas


@dataclass
class _Rasgos:
    puntos: np.ndarray     # (k, 2) metros UTM (este, norte), donde el GPS dice que están
    descriptores: np.ndarray


def _rectificada(foto: Foto):
    """La imagen en gris, a la resolución de trabajo y con norte arriba, más lo necesario para pasar de sus píxeles a metros."""
    g = config.AJUSTE_GSD_M
    esquinas = _esquinas_m(foto)
    e0, n1 = min(p[0] for p in esquinas), max(p[1] for p in esquinas)
    ancho = max(2, math.ceil((max(p[0] for p in esquinas) - e0) / g))
    alto = max(2, math.ceil((n1 - min(p[1] for p in esquinas)) / g))
    local = Marco(e0, n1, g, ancho, alto)

    k = foto.gsd / g
    reduccion = next((f for f in (8, 4, 2) if 1 / f >= k), 1)
    img = imagen.leer(foto.ruta, {1: cv2.IMREAD_GRAYSCALE, 2: cv2.IMREAD_REDUCED_GRAYSCALE_2, 4: cv2.IMREAD_REDUCED_GRAYSCALE_4, 8: cv2.IMREAD_REDUCED_GRAYSCALE_8}[reduccion])
    if img is None:
        return None
    m = matriz(foto, local)
    if k < 1:
        img = cv2.resize(img, (max(2, round(foto.ancho * k)), max(2, round(foto.alto * k))), interpolation=cv2.INTER_AREA)
    sx, sy = img.shape[1] / foto.ancho, img.shape[0] / foto.alto
    m = m @ np.array([[1 / sx, 0, 0], [0, 1 / sy, 0], [0, 0, 1]])
    plana = cv2.warpAffine(img, m, (ancho, alto), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    valida = cv2.warpAffine(np.full(img.shape, 255, np.uint8), m, (ancho, alto), flags=cv2.INTER_NEAREST, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    valida = cv2.erode(valida, np.ones((15, 15), np.uint8))      # sin rasgos en el borde, donde la imagen se corta
    return plana, valida, e0, n1


def _rasgos(foto: Foto, detector) -> _Rasgos | None:
    r = _rectificada(foto)
    if r is None:
        return None
    plana, valida, e0, n1 = r
    puntos, desc = detector.detectAndCompute(plana, valida)
    if desc is None or len(puntos) < config.AJUSTE_MIN_COINCIDENCIAS:
        return None
    g = config.AJUSTE_GSD_M
    xy = np.array([p.pt for p in puntos], dtype=np.float64)
    return _Rasgos(np.column_stack([e0 + xy[:, 0] * g, n1 - xy[:, 1] * g]), np.clip(desc, 0, 255).astype(np.uint8))


def _vecinas(fotos: list) -> list:
    """Pares de imágenes que se superponen lo bastante: las `AJUSTE_VECINAS` más cercanas de cada una, sin repetir."""
    c = np.array([(f.este, f.norte) for f in fotos])
    radio = 0.6 * float(np.median([min(f.gsd * f.ancho, f.gsd * f.alto) for f in fotos]))
    celdas: dict = {}
    for i, (e, n) in enumerate(c):
        celdas.setdefault((int(e // radio), int(n // radio)), []).append(i)
    pares = set()
    for i, (e, n) in enumerate(c):
        ce, cn = int(e // radio), int(n // radio)
        candidatas = [j for de in (-1, 0, 1) for dn in (-1, 0, 1) for j in celdas.get((ce + de, cn + dn), []) if j != i]
        if not candidatas:
            continue
        d = np.hypot(c[candidatas, 0] - e, c[candidatas, 1] - n)
        for k in np.argsort(d)[: config.AJUSTE_VECINAS]:
            if d[k] <= radio:
                pares.add((min(i, candidatas[k]), max(i, candidatas[k])))
    return sorted(pares)


def _coincidencias(a: _Rasgos, b: _Rasgos, distancia_m: float, matcher):
    """Puntos del terreno que aparecen en las dos imágenes, como parejas (en a, en b) en metros; None si no son confiables.

    [distancia_m] es lo que distan los centros de las dos imágenes según el GPS. Si el tamaño de las imágenes está mal, los mismos puntos caen
    corridos entre sí una fracción de esa distancia, así que es el límite razonable para el corrimiento que se acepta.
    """
    pares = matcher.knnMatch(a.descriptores.astype(np.float32), b.descriptores.astype(np.float32), k=2)
    buenas = [m for m, n in (p for p in pares if len(p) == 2) if m.distance < config.AJUSTE_RAZON * n.distance]
    if len(buenas) < config.AJUSTE_MIN_COINCIDENCIAS:
        return None
    pa = a.puntos[[m.queryIdx for m in buenas]]
    pb = b.puntos[[m.trainIdx for m in buenas]]
    centro = pa.mean(axis=0)
    transformacion, inliers = cv2.estimateAffinePartial2D(
        pa - centro, pb - centro, method=cv2.RANSAC, ransacReprojThreshold=config.AJUSTE_UMBRAL_M, maxIters=2000, confidence=0.99
    )
    if transformacion is None or inliers is None or int(inliers.sum()) < config.AJUSTE_MIN_INLIERS:
        return None
    escala = math.hypot(transformacion[0, 0], transformacion[1, 0])
    giro = math.degrees(math.atan2(transformacion[1, 0], transformacion[0, 0]))
    corrimiento = math.hypot(*transformacion[:, 2])
    if not (1 / config.AJUSTE_ESCALA_PAR_MAX <= escala <= config.AJUSTE_ESCALA_PAR_MAX) or abs(giro) > config.AJUSTE_GIRO_PAR_MAX_GRADOS \
            or corrimiento > config.AJUSTE_MAX_DESPLAZAMIENTO_M + 1.2 * distancia_m:
        return None       # lo que dice no se parece a un error de tamaño o de GPS: probablemente son coincidencias falsas (hileras repetidas)
    ok = inliers.ravel().astype(bool)
    pa, pb = pa[ok], pb[ok]
    if len(pa) > config.AJUSTE_PUNTOS_POR_PAR:
        paso = np.linspace(0, len(pa) - 1, config.AJUSTE_PUNTOS_POR_PAR).astype(int)
        pa, pb = pa[paso], pb[paso]
    return pa, pb


def _resolver(filas, columnas, valores, rhs, n, prior):
    """Mínimos cuadrados con ancla: resuelve (AᵀA + P)x = Aᵀb por gradiente conjugado, con A dispersa dada por (filas, columnas, valores)."""
    def av(u):
        return np.bincount(filas, weights=valores * u[columnas], minlength=len(rhs))

    def atv(r):
        return np.bincount(columnas, weights=valores * r[filas], minlength=n)

    diag = np.bincount(columnas, weights=valores**2, minlength=n) + prior
    b = atv(rhs)
    u = np.zeros(n)
    r = b.copy()
    z = r / diag
    p = z.copy()
    rz = float(r @ z)
    for _ in range(800):
        q = atv(av(p)) + prior * p
        alfa = rz / float(p @ q)
        u += alfa * p
        r -= alfa * q
        if float(np.linalg.norm(r)) < 1e-9 * max(float(np.linalg.norm(b)), 1e-12):
            break
        z = r / diag
        rz_nuevo = float(r @ z)
        p = z + (rz_nuevo / rz) * p
        rz = rz_nuevo
    return u


class _Puntos:
    """Los puntos en común de todos los pares, en coordenadas locales de cada imagen (metros respecto del centro de la imagen, norte arriba).

    Con ellos, dónde cae un punto de la imagen i es `c_i + d_i + escala_i · Giro(θ_i) · q`: así se puede probar cualquier corrimiento, giro o
    escala sin volver a buscar coincidencias.
    """

    def __init__(self, usadas: list, centros: np.ndarray):
        self.i = np.concatenate([np.full(len(a), i) for i, j, a, b in usadas])
        self.j = np.concatenate([np.full(len(a), j) for i, j, a, b in usadas])
        self.qa = np.concatenate([a - centros[i] for i, j, a, b in usadas])
        self.qb = np.concatenate([b - centros[j] for i, j, a, b in usadas])
        self.dc = np.concatenate([np.tile(centros[i] - centros[j], (len(a), 1)) for i, j, a, b in usadas])    # c_i − c_j
        self.n = len(self.i)

    def residuos(self, u: np.ndarray, log_escala: float = 0.0) -> np.ndarray:
        """(n, 2): cuánto se separan, según los parámetros [u] (n_fotos × 4: corrimiento este, norte, giro, desvío del log de escala respecto del
        de todo el vuelo) y el log de escala de todo el vuelo [log_escala], los dos puntos de cada pareja."""
        xa = self._donde(u, self.i, self.qa, log_escala)
        xb = self._donde(u, self.j, self.qb, log_escala)
        return self.dc + (u[self.i, :2] - u[self.j, :2]) + xa - xb

    @staticmethod
    def _donde(u, idx, q, log_escala):
        s, c = np.sin(u[idx, 2]), np.cos(u[idx, 2])
        e = np.exp(log_escala + u[idx, 3])
        return np.column_stack([e * (c * q[:, 0] - s * q[:, 1]), e * (s * q[:, 0] + c * q[:, 1])])

    def jacobiano(self, u: np.ndarray, w, log_escala: float, columna_global: int):
        """(filas, columnas, valores) de las derivadas de los residuos respecto de los parámetros, con peso [w] (uno solo, o uno por punto). El log
        de escala de todo el vuelo va en la columna [columna_global]."""
        filas, columnas, valores = [], [], []
        t = np.arange(self.n)
        for idx, q, signo in ((self.i, self.qa, 1.0), (self.j, self.qb, -1.0)):
            s, c = np.sin(u[idx, 2]), np.cos(u[idx, 2])
            e = np.exp(log_escala + u[idx, 3])
            girado = np.column_stack([e * (c * q[:, 0] - s * q[:, 1]), e * (s * q[:, 0] + c * q[:, 1])])      # e·Giro·q (derivada respecto del log de escala)
            perp = np.column_stack([-girado[:, 1], girado[:, 0]])                                               # derivada respecto del giro
            for comp in (0, 1):
                fila = 2 * t + comp
                filas += [fila, fila, fila, fila]
                columnas += [4 * idx + comp, 4 * idx + 2, 4 * idx + 3, np.full(self.n, columna_global)]
                valores += [signo * w * np.ones(self.n), signo * w * perp[:, comp], signo * w * girado[:, comp], signo * w * girado[:, comp]]
        return np.concatenate(filas), np.concatenate(columnas), np.concatenate(valores)


def _escala_global(pts: _Puntos, n: int) -> float:
    """Una primera idea del factor por el que hay que multiplicar el tamaño de todas las imágenes (con todo lo demás sin tocar).

    El error de tamaño por relieve puede ser de ×2 o más: es demasiado para una corrección pequeña, así que se busca primero a lo ancho. Esta
    cifra solo sirve para arrancar; el valor bueno sale del ajuste conjunto, que sí deja mover las imágenes (con GPS ruidoso, esta búsqueda sola
    se equivoca de un 5 % porque usa la escala para compensar el ruido)."""
    u0 = np.zeros((n, 4))
    candidatos = np.geomspace(config.AJUSTE_ESCALA_MIN, config.AJUSTE_ESCALA_MAX, 60)
    costos = [float(np.mean(np.hypot(*pts.residuos(u0, math.log(g)).T) ** 2)) for g in candidatos]
    return float(candidatos[int(np.argmin(costos))])


def _calmar_atipicas(d: np.ndarray) -> int:
    """Una imagen con muy pocas coincidencias puede salir con un corrimiento o un giro absurdos (otras cercanas en el tiempo no). Como el error cambia
    despacio de una imagen a la siguiente, a esa se le da el promedio de las dos vecinas más cercanas que no tienen el problema. Devuelve cuántas."""
    raras = (np.hypot(d[:, 0], d[:, 1]) > config.AJUSTE_DESPLAZAMIENTO_FOTO_MAX_M) | (np.abs(d[:, 2]) > math.radians(config.AJUSTE_GIRO_FOTO_MAX_GRADOS))
    if not raras.any() or raras.all():
        return 0
    buenas = np.flatnonzero(~raras)
    for i in np.flatnonzero(raras):
        antes = buenas[buenas < i]
        despues = buenas[buenas > i]
        vecinas = ([antes[-1]] if len(antes) else []) + ([despues[0]] if len(despues) else [])
        d[i] = d[vecinas].mean(axis=0)
    return int(raras.sum())


def ajustar(fotos: list, al_avanzar=None) -> Ajuste:
    """Corrige posición, rumbo y tamaño de [fotos] (se modifican en el sitio). Si no se puede de forma confiable, no toca nada y dice por qué."""
    n = len(fotos)
    if n < config.AJUSTE_MIN_FOTOS:
        return Ajuste(False, f"Hay muy pocas imágenes ({n}) para ajustarlas entre sí; se usó la posición del GPS.")
    if n > config.AJUSTE_MAX_FOTOS:
        return Ajuste(False, f"Son demasiadas imágenes ({n}) para ajustarlas entre sí en esta versión; se usó la posición del GPS.")

    detector = cv2.SIFT_create(nfeatures=config.AJUSTE_RASGOS)
    rasgos = []
    for i, f in enumerate(fotos):
        rasgos.append(_rasgos(f, detector))
        if al_avanzar and i % 20 == 0:
            al_avanzar(0.5 * i / n)
    matcher = cv2.BFMatcher(cv2.NORM_L2)
    centros = np.array([(f.este, f.norte) for f in fotos])
    pares = _vecinas(fotos)
    usadas = []     # (i, j, a, b)
    for k, (i, j) in enumerate(pares):
        if rasgos[i] is not None and rasgos[j] is not None:
            c = _coincidencias(rasgos[i], rasgos[j], float(np.hypot(*(centros[i] - centros[j]))), matcher)
            if c is not None:
                usadas.append((i, j, *c))
        if al_avanzar and k % 50 == 0:
            al_avanzar(0.5 + 0.4 * k / max(len(pares), 1))
    del rasgos

    con_pares = {i for u in usadas for i in u[:2]}
    if len(con_pares) < config.AJUSTE_MIN_FOTOS_CON_PARES_PCT / 100 * n:
        return Ajuste(
            False, f"Solo {len(con_pares)} de {n} imágenes tienen puntos en común con sus vecinas (terreno muy uniforme o imágenes borrosas); "
                   "se usó la posición del GPS.", pares=len(usadas), fotos_con_pares=len(con_pares)
        )

    pts = _Puntos(usadas, centros)
    w = 1.0 / config.AJUSTE_SIGMA_PUNTO_M
    u0 = np.zeros((n, 4))
    rms_antes = float(np.sqrt(np.mean(np.sum(pts.residuos(u0) ** 2, axis=1))))
    altura_registro = float(np.median([f.altura_m for f in fotos]))

    # 1) el factor de escala de todo el vuelo; 2) lo que cada imagen se aparta de él, junto con su corrimiento y su giro
    log_g0 = math.log(_escala_global(pts, n))
    sigma_posicion = config.AJUSTE_SIGMA_GPS_M
    col_g = 4 * n          # el log de escala de todo el vuelo es la última incógnita; sin ancla (es lo que se quiere averiguar)
    prior = np.concatenate([
        np.tile([1 / sigma_posicion**2, 1 / sigma_posicion**2, 1 / math.radians(config.AJUSTE_SIGMA_GIRO_GRADOS) ** 2, 1 / config.AJUSTE_SIGMA_ESCALA**2], n), [1e-9]
    ])

    # Filas lineales: los errores cambian despacio de una imagen a la siguiente (se suma el tiempo entre ellas)
    t = np.array([f.t_ms for f in fotos])
    ante = np.array([i for i in range(n - 1) if t[i + 1] - t[i] <= config.AJUSTE_SUAVE_MAX_S * 1000], dtype=int)
    post = ante + 1
    sigmas_suaves = (config.AJUSTE_SUAVE_M, config.AJUSTE_SUAVE_M, math.radians(config.AJUSTE_SUAVE_GIRO_GRADOS), config.AJUSTE_SUAVE_ESCALA)
    f_s, c_s, v_s = [], [], []
    fila_base = 2 * pts.n
    for comp, sigma in enumerate(sigmas_suaves):
        r = fila_base + comp * len(ante) + np.arange(len(ante))
        f_s += [r, r]
        c_s += [4 * ante + comp, 4 * post + comp]
        v_s += [np.full(len(ante), 1 / sigma), np.full(len(ante), -1 / sigma)]
    f_s, c_s, v_s = np.concatenate(f_s), np.concatenate(c_s), np.concatenate(v_s)
    filas_totales = fila_base + 4 * len(ante)

    x = np.concatenate([u0.ravel(), [log_g0]])
    for vuelta in range(config.AJUSTE_ITERACIONES):
        u, lg = x[:col_g].reshape(n, 4), float(x[col_g])
        r_m = pts.residuos(u, lg)
        peso = np.full(pts.n, w)
        if vuelta >= config.AJUSTE_VUELTAS_SIN_ROBUSTEZ:
            # Las coincidencias falsas (hileras repetidas, hojas movidas por el viento) dejan residuos grandes y arrastran el ajuste: se les baja el
            # peso (Huber), con un corte que sale de lo que tiene la mayoría (no un valor fijo, que depende de cuánto desajuste hubiera al empezar)
            modulo = np.hypot(r_m[:, 0], r_m[:, 1])
            corte = max(config.AJUSTE_ROBUSTO_VECES * 1.4826 * float(np.median(modulo)), config.AJUSTE_ROBUSTO_MIN_M)
            peso = w * np.minimum(1.0, corte / np.maximum(modulo, 1e-9))
        r0 = r_m * peso[:, None]
        f_j, c_j, v_j = pts.jacobiano(u, peso, lg, col_g)
        filas = np.concatenate([f_j, f_s]).astype(int)
        columnas = np.concatenate([c_j, c_s]).astype(int)
        valores = np.concatenate([v_j, v_s])
        # Gauss-Newton: se resuelve para el valor total x = x0 + δ con J δ + r0 = 0  →  J x = J x0 − r0
        jx = np.bincount(f_j, weights=v_j * x[c_j], minlength=2 * pts.n)
        rhs = np.zeros(filas_totales)           # las filas de suavidad son «J x = 0»; las de los puntos, «J x = J x0 − r0»
        rhs[: 2 * pts.n] = jx - r0.ravel()
        nuevo = _resolver(filas, columnas, valores, rhs, col_g + 1, prior)
        paso = float(np.abs(nuevo - x).max())
        x = nuevo
        if paso < 1e-4:
            break
    u, lg = x[:col_g].reshape(n, 4), float(x[col_g])

    d = u.copy()
    atipicas = _calmar_atipicas(d)
    escala_foto = np.exp(lg + d[:, 3])
    rms_despues = float(np.sqrt(np.mean(np.sum(pts.residuos(d, lg) ** 2, axis=1))))
    desplazamientos = np.hypot(d[:, 0], d[:, 1])
    giro_max = float(np.degrees(np.abs(d[:, 2]).max()))
    alturas = np.array([f.altura_m for f in fotos]) * escala_foto
    base = Ajuste(False, None, len(usadas), len(con_pares), rms_antes, rms_despues, float(np.median(desplazamientos)), giro_max,
                  float(np.median(escala_foto)), float(alturas.min()), float(alturas.max()), altura_registro, atipicas)

    if rms_antes < config.AJUSTE_YA_COINCIDEN_M:
        return base          # ya coincidían: no hay nada que corregir y no se toca (tampoco es un problema)
    if rms_despues > config.AJUSTE_MEJORA_MINIMA * rms_antes:
        base.advertencia = "Los puntos en común entre imágenes no se pudieron hacer coincidir mejor que con el GPS; se usó la posición del GPS."
        return base
    # Con cientos de imágenes siempre hay alguna con pocas coincidencias que sale rara: se juzga por lo que le pasa a la gran mayoría
    if float(np.percentile(desplazamientos, 95)) > config.AJUSTE_MAX_DESPLAZAMIENTO_M or float(np.percentile(np.abs(np.degrees(d[:, 2])), 95)) > config.AJUSTE_MAX_GIRO_GRADOS \
            or not (config.AJUSTE_ESCALA_MIN * 1.05 < float(escala_foto.min()) and float(escala_foto.max()) < config.AJUSTE_ESCALA_MAX / 1.05):
        base.advertencia = "El ajuste pedía mover o cambiar de tamaño las imágenes más de lo razonable; se descartó y se usó la posición del GPS."
        return base

    for f, (de, dn, theta, _), k in zip(fotos, d, escala_foto):
        f.este += float(de)
        f.norte += float(dn)
        f.yaw_rad -= float(theta)      # θ gira en sentido contrario a las agujas del reloj; el rumbo crece en el sentido de las agujas
        f.gsd *= float(k)              # el tamaño real del píxel: la altura real sobre lo que se ve es la del registro por este mismo factor
        f.altura_m *= float(k)
    base.aplicado = True
    return base
