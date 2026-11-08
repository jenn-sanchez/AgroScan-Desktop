"""El lienzo del mosaico: una cuadrícula de píxeles en metros (UTM, norte arriba) y la mezcla de las imágenes sobre ella.

Cada imagen se apoya plana sobre el terreno (cámara mirando al suelo): su centro va donde estaba el dron, girada según su rumbo y
escalada según su altura. El peso de cada imagen baja hacia su borde, así donde se superponen manda la parte más central.
"""
import math
from collections import OrderedDict
from dataclasses import dataclass

import cv2
import numpy as np

from src import imagen
from src import config
from src.errores import ErrorOrtomosaico
from src.ubicacion import Foto


@dataclass
class Marco:
    """Dónde cae el lienzo: la esquina noroeste en UTM, el tamaño de cada píxel en metros y cuántos píxeles tiene."""
    este0: float
    norte1: float
    g: float
    ancho: int
    alto: int

    def con_resolucion(self, g: float) -> "Marco":
        return Marco(self.este0, self.norte1, g, max(1, math.ceil(self.ancho * self.g / g)), max(1, math.ceil(self.alto * self.g / g)))

    @property
    def este1(self) -> float:
        return self.este0 + self.ancho * self.g

    @property
    def norte0(self) -> float:
        return self.norte1 - self.alto * self.g

    def a_pixel(self, este, norte):
        return (np.asarray(este) - self.este0) / self.g, (self.norte1 - np.asarray(norte)) / self.g


def matriz(foto: Foto, marco: Marco) -> np.ndarray:
    """Transformación 2×3 de un píxel de la imagen (u derecha, v abajo) a un píxel del lienzo.

    Con rumbo 0 (mirando al norte) la imagen queda como está: arriba es norte, derecha es este. Con rumbo 90° (mirando al este) lo que
    queda arriba en la imagen está al este: la imagen se gira hacia la derecha tanto como giró el dron.
    """
    k = foto.gsd / marco.g
    c, s = math.cos(foto.yaw_rad), math.sin(foto.yaw_rad)
    cu, cv = (foto.ancho - 1) / 2, (foto.alto - 1) / 2
    cx, cy = marco.a_pixel(foto.este, foto.norte)
    return np.array([
        [k * c, -k * s, cx - k * (cu * c - cv * s)],
        [k * s, k * c, cy - k * (cu * s + cv * c)],
    ])


def _esquinas(foto: Foto, marco: Marco) -> np.ndarray:
    m = matriz(foto, marco)
    pts = np.array([[0, 0, 1], [foto.ancho - 1, 0, 1], [foto.ancho - 1, foto.alto - 1, 1], [0, foto.alto - 1, 1]], dtype=float)
    return pts @ m.T


def _esquinas_m(foto: Foto) -> list:
    """Las cuatro esquinas del terreno que cubre la imagen, en metros UTM (este, norte)."""
    mitad_x, mitad_y = foto.gsd * (foto.ancho - 1) / 2, foto.gsd * (foto.alto - 1) / 2
    c, s = math.cos(foto.yaw_rad), math.sin(foto.yaw_rad)
    # (derecha, arriba) en la imagen → este = derecha·cos + arriba·sen ; norte = −derecha·sen + arriba·cos
    return [(foto.este + r * c + u * s, foto.norte - r * s + u * c)
            for r, u in ((-mitad_x, mitad_y), (mitad_x, mitad_y), (mitad_x, -mitad_y), (-mitad_x, -mitad_y))]


def hacer_marco(fotos: list, calidad: str) -> Marco:
    """El lienzo que cubre todas las imágenes, con el tamaño de píxel que pide la [calidad] (y que no pase de MAX_PIXELES)."""
    esquinas = [p for f in fotos for p in _esquinas_m(f)]
    este, norte = [p[0] for p in esquinas], [p[1] for p in esquinas]
    e0, e1 = min(este) - config.MARGEN_M, max(este) + config.MARGEN_M
    n0, n1 = min(norte) - config.MARGEN_M, max(norte) + config.MARGEN_M
    nativo = float(np.median([f.gsd for f in fotos]))
    g = nativo * config.FACTOR_RESOLUCION[calidad]
    g = max(g, math.sqrt((e1 - e0) * (n1 - n0) / config.MAX_PIXELES))
    marco = Marco(e0, n1, g, math.ceil((e1 - e0) / g), math.ceil((n1 - n0) / g))
    if marco.ancho < 2 or marco.alto < 2:
        raise ErrorOrtomosaico("Las imágenes quedaron todas en el mismo punto: no hay terreno que armar. Revisa el registro de vuelo.")
    return marco


class _Cache:
    """Imágenes ya leídas y reducidas a la resolución del lienzo (leer un JPG grande cuesta más que mezclarlo)."""

    def __init__(self, marco: Marco, max_bytes: int = 700_000_000):
        self.marco, self.max_bytes, self.bytes = marco, max_bytes, 0
        self._d: OrderedDict = OrderedDict()
        self._pesos: dict = {}

    def get(self, foto: Foto):
        """(imagen BGR reducida, transformación de esa imagen hacia el lienzo, peso de cada píxel)."""
        if foto.ruta not in self._d:
            img = imagen.leer(foto.ruta, cv2.IMREAD_COLOR)
            if img is None:
                raise ErrorOrtomosaico(f"No se pudo leer la imagen {foto.ruta.name}: está dañada o no es un JPG. Vuelve a preprocesar su video.")
            k = foto.gsd / self.marco.g
            if k < 0.5:     # reducir antes de girar: mezclar una imagen enorme en un lienzo pequeño es lento y sale con dientes de sierra
                r = max(k, 0.05)
                img = cv2.resize(img, (max(2, round(foto.ancho * r)), max(2, round(foto.alto * r))), interpolation=cv2.INTER_AREA)
            self._d[foto.ruta] = img
            self.bytes += img.nbytes
            while self.bytes > self.max_bytes and len(self._d) > 1:
                _, viejo = self._d.popitem(last=False)
                self.bytes -= viejo.nbytes
        self._d.move_to_end(foto.ruta)
        img = self._d[foto.ruta]
        # La posición es de cada foto (no de su archivo): se calcula siempre, es barato
        sx, sy = img.shape[1] / foto.ancho, img.shape[0] / foto.alto
        m = matriz(foto, self.marco) @ np.array([[1 / sx, 0, 0], [0, 1 / sy, 0], [0, 0, 1]])
        return img, m, self._peso(img.shape[:2])

    def _peso(self, tam) -> np.ndarray:
        if tam not in self._pesos:
            h, w = tam
            v, u = np.mgrid[0:h, 0:w].astype(np.float32)
            borde = np.maximum(np.abs(u - (w - 1) / 2) / max((w - 1) / 2, 1), np.abs(v - (h - 1) / 2) / max((h - 1) / 2, 1))
            # 1 en el centro de la imagen, bajando hasta el borde (sin potencia: se eleva al mezclar, ver Compositor.region)
            self._pesos[tam] = np.maximum(1.0 - borde, 0.02)
        return self._pesos[tam]


class Compositor:
    """Arma el mosaico por pedazos: así un lote grande no necesita tener el lienzo entero en memoria."""

    def __init__(self, fotos: list, marco: Marco):
        self.marco = marco
        self.fotos = fotos
        self.cache = _Cache(marco)
        self.cajas = []
        for f in fotos:
            e = _esquinas(f, marco)
            self.cajas.append((math.floor(e[:, 0].min()), math.floor(e[:, 1].min()), math.ceil(e[:, 0].max()) + 1, math.ceil(e[:, 1].max()) + 1))

    def region(self, x0: int, y0: int, ancho: int, alto: int) -> tuple[np.ndarray, np.ndarray]:
        """El rectángulo del lienzo que empieza en el píxel (x0, y0): (BGR uint8, alfa uint8: 255 donde hay imagen).

        Puede salirse del lienzo: lo de afuera queda sin imagen.
        """
        acc = np.zeros((alto, ancho, 3), np.float32)
        suma = np.zeros((alto, ancho), np.float32)
        cubierto = np.zeros((alto, ancho), np.float32)      # el mayor peso base de las imágenes que cubren cada píxel: cuánto hay de imagen allí
        for foto, (bx0, by0, bx1, by1) in zip(self.fotos, self.cajas):
            rx0, rx1 = max(bx0, x0), min(bx1, x0 + ancho)
            ry0, ry1 = max(by0, y0), min(by1, y0 + alto)
            if rx0 >= rx1 or ry0 >= ry1:
                continue
            img, m, peso = self.cache.get(foto)
            local = m.copy()
            local[0, 2] -= rx0
            local[1, 2] -= ry0
            tam = (rx1 - rx0, ry1 - ry0)
            color = cv2.warpAffine(img, local, tam, flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0).astype(np.float32)
            p = cv2.warpAffine(peso, local, tam, flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
            ra, rb, ca, cb = ry0 - y0, ry1 - y0, rx0 - x0, rx1 - x0
            # Con una potencia alta manda casi solo la imagen que ve el punto más de frente. Lo que suma de las demás puede ser tan chico que se pierda
            # por redondeo, así que se les deja un resto mínimo proporcional (1e-30): nunca queda un punto cubierto sin color, y nunca pesa más que una
            # imagen de verdad. La cobertura se mira aparte, con el peso sin elevar.
            pw = p**config.POTENCIA_PESO + 1e-30 * p
            acc[ra:rb, ca:cb] += color * pw[..., None]
            suma[ra:rb, ca:cb] += pw
            np.maximum(cubierto[ra:rb, ca:cb], p, out=cubierto[ra:rb, ca:cb])
        hay = cubierto > 0.01
        bgr = np.zeros_like(acc)
        bgr[hay] = acc[hay] / suma[hay][:, None]
        return np.clip(bgr + 0.5, 0, 255).astype(np.uint8), (hay * 255).astype(np.uint8)

    def banda(self, y0: int, alto: int) -> tuple[np.ndarray, np.ndarray]:
        """Las filas [y0, y0+alto) del lienzo, de ancho completo."""
        return self.region(0, y0, self.marco.ancho, min(alto, self.marco.alto - y0))

    def todo(self) -> tuple[np.ndarray, np.ndarray]:
        return self.banda(0, self.marco.alto)
