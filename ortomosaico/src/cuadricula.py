"""La cuadrícula común de un lote y las teselas que se cortan de ella (lo que usa el módulo de IA).

La cuadrícula se ancla al **polígono del lote**, no al vuelo: su origen sale del contorno (que no cambia entre fechas) y el **lado de celda es fijo en
metros**. Así la celda «fila 3, columna 5» es el mismo pedazo de terreno en todas las fechas, y las teselas de dos fechas se pueden comparar una a una
sin volver a ubicar nada. Lo que sí puede cambiar entre fechas es cuántos píxeles tiene la tesela (depende de la altura a la que se voló).

**Cada tesela sale, siempre que se puede, de UNA sola imagen** (la que ve esa celda más de frente y más nítida) y a la resolución real de esa imagen:
un único remuestreo, sin mezclar. Mezclar varias imágenes (como hace el mosaico) promedia sus pequeños desacuerdos y empasta los detalles, que es justo
lo que el diagnóstico necesita ver. Solo si ninguna imagen cubre la celda entera se mezcla (y el índice lo dice en la columna `fuente`).
"""
import csv
import math
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

from src import config, imagen
from src.lienzo import Compositor, Marco, matriz
from src.utm import Zona


@dataclass
class Cuadricula:
    zona: Zona
    este0: float          # borde oeste de la columna 0 (metros UTM, entero)
    norte1: float         # borde norte de la fila 0 (metros UTM, entero)
    lado_m: float         # lado de cada celda, en metros (fijo: igual en todas las fechas)
    lado_px: int          # píxeles por lado de la tesela, según la resolución de este vuelo
    columnas: int
    filas: int
    lote: np.ndarray      # contorno del lote, (n, 2) en (este, norte)

    @property
    def gsd(self) -> float:
        """Metros por píxel de las teselas de este vuelo."""
        return self.lado_m / self.lado_px

    def marco(self) -> Marco:
        """El lienzo que cubre todas las celdas, a la resolución de las teselas."""
        return Marco(self.este0, self.norte1, self.gsd, self.columnas * self.lado_px, self.filas * self.lado_px)

    def esquina(self, fila: int, columna: int) -> tuple[float, float]:
        """(este oeste, norte norte) de una celda."""
        return self.este0 + columna * self.lado_m, self.norte1 - fila * self.lado_m

    def a_json(self) -> dict:
        return {
            "crs": self.zona.crs, "este0": self.este0, "norte1": self.norte1, "lado_m": self.lado_m, "columnas": self.columnas, "filas": self.filas,
            "lado_px": self.lado_px, "gsd_m": round(self.gsd, 5),
        }


def hacer_cuadricula(lote_utm: np.ndarray | None, zona: Zona, gsd_m: float) -> Cuadricula | None:
    """La cuadrícula del lote con teselas a [gsd_m] metros por píxel (la resolución real de las imágenes de este vuelo); None si no se conoce el
    contorno del lote (sin él no hay dónde anclarla)."""
    if lote_utm is None or len(lote_utm) < 3:
        return None
    lado_m = config.TESELA_LADO_M
    este0 = math.floor(float(lote_utm[:, 0].min()))
    norte1 = math.ceil(float(lote_utm[:, 1].max()))
    columnas = max(1, math.ceil((float(lote_utm[:, 0].max()) - este0) / lado_m))
    filas = max(1, math.ceil((norte1 - float(lote_utm[:, 1].min())) / lado_m))
    lado_px = max(config.TESELA_LADO_MIN_PX, round(lado_m / max(gsd_m, config.TESELA_GSD_MIN_M)))
    return Cuadricula(zona, float(este0), float(norte1), lado_m, lado_px, columnas, filas, lote_utm)


def _mascara_del_lote(lote_utm: np.ndarray, este_oeste: float, norte_norte: float, g: float, ancho: int, alto: int) -> np.ndarray:
    """1 donde el píxel está dentro del lote, para un rectángulo cuya esquina noroeste es (este_oeste, norte_norte)."""
    x = (lote_utm[:, 0] - este_oeste) / g
    y = (norte_norte - lote_utm[:, 1]) / g
    m = np.zeros((alto, ancho), np.uint8)
    cv2.fillPoly(m, [np.round(np.column_stack([x, y]) * 16).astype(np.int32)], 1, lineType=cv2.LINE_8, shift=4)
    return m


COLUMNAS_CSV = (
    "id", "fila", "columna", "archivo", "en_lote_pct", "cobertura_pct",
    "este_min", "norte_min", "este_max", "norte_max", "oeste", "sur", "este", "norte", "lat_centro", "lon_centro",
    "gsd_cm_px", "fuente", "imagen",
)


class _Imagenes:
    """Candidatas a ser la imagen de cada tesela, con lo necesario para saber, de forma vectorizada, cuáles contienen una celda entera y qué tan
    de frente la ven."""

    def __init__(self, fotos: list):
        self.fotos = fotos
        self.este = np.array([f.este for f in fotos])
        self.norte = np.array([f.norte for f in fotos])
        self.cu = np.array([(f.ancho - 1) / 2 for f in fotos])
        self.cv = np.array([(f.alto - 1) / 2 for f in fotos])
        # Del terreno (x = este − este_i, y = norte_i − norte) a los píxeles de cada imagen
        self.inv = np.array([cv2.invertAffineTransform(matriz(f, Marco(f.este, f.norte, 1.0, 1, 1))) for f in fotos])
        self._leidas: OrderedDict = OrderedDict()

    def candidatas(self, este: np.ndarray, norte: np.ndarray) -> list:
        """Índices de las imágenes que contienen enteros los puntos dados (las esquinas de una celda), de la más central a la menos."""
        dx = este[None, :] - self.este[:, None]
        dy = self.norte[:, None] - norte[None, :]
        u = self.inv[:, 0, 0, None] * dx + self.inv[:, 0, 1, None] * dy + self.inv[:, 0, 2, None]
        v = self.inv[:, 1, 0, None] * dx + self.inv[:, 1, 1, None] * dy + self.inv[:, 1, 2, None]
        borde = np.maximum(np.abs(u - self.cu[:, None]) / self.cu[:, None], np.abs(v - self.cv[:, None]) / self.cv[:, None]).max(axis=1)
        buenas = np.flatnonzero(borde <= 1.0 - config.TESELA_MARGEN_BORDE)
        return list(buenas[np.argsort(borde[buenas])][: config.TESELA_CANDIDATAS])

    def leer(self, i: int) -> np.ndarray:
        """La imagen completa, a su resolución original (se guardan las últimas: las celdas vecinas suelen salir de la misma)."""
        f = self.fotos[i]
        if f.ruta not in self._leidas:
            self._leidas[f.ruta] = imagen.leer(f.ruta, cv2.IMREAD_COLOR)
            while len(self._leidas) > 6:
                self._leidas.popitem(last=False)
        self._leidas.move_to_end(f.ruta)
        return self._leidas[f.ruta]


def _recortar(img: np.ndarray, foto, oeste_e: float, norte_n: float, g: float, n: int) -> np.ndarray:
    """La celda cortada de una imagen: un único remuestreo, a [g] metros por píxel."""
    m = matriz(foto, Marco(oeste_e, norte_n, g, n, n))
    s = foto.gsd / g
    if s < 0.66:       # la tesela es bastante más gruesa que la imagen: se reduce primero (sin dientes de sierra)
        img = cv2.resize(img, (max(2, round(img.shape[1] * s)), max(2, round(img.shape[0] * s))), interpolation=cv2.INTER_AREA)
        sx, sy = img.shape[1] / foto.ancho, img.shape[0] / foto.alto
        m = m @ np.array([[1 / sx, 0, 0], [0, 1 / sy, 0], [0, 0, 1]])
    return cv2.warpAffine(img, m, (n, n), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def _nitidez(tesela: np.ndarray) -> float:
    """Qué tan enfocada está: varianza del laplaciano del gris. Solo sirve para comparar entre imágenes del mismo lugar, no como medida absoluta."""
    return float(cv2.Laplacian(cv2.cvtColor(tesela, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var())


def escribir_teselas(carpeta: Path, fotos: list, cuad: Cuadricula, al_avanzar=None) -> dict:
    """Corta las teselas por la cuadrícula del lote y escribe `teselas/` y `teselas.csv`.

    Solo salen las celdas que tocan el lote y donde las imágenes cubren lo suficiente de lo que cae dentro del lote. Una tesela es un JPG de
    `lado_px` × `lado_px` (casi sin pérdida: calidad alta y sin submuestrear el color); donde no hay imagen es negro (`cobertura_pct` dice cuánto del
    lote está cubierto en esa celda).
    """
    destino = carpeta / "teselas"
    destino.mkdir(parents=True, exist_ok=True)
    imagenes = _Imagenes(fotos)
    mezcla = None                       # el compositor solo se arma si alguna celda no cabe en una sola imagen
    n, g = cuad.lado_px, cuad.gsd
    filas_csv, sin_lote, sin_imagen, de_una, mezcladas = [], 0, 0, 0, 0
    total = cuad.filas * cuad.columnas
    for fila in range(cuad.filas):
        for columna in range(cuad.columnas):
            oeste_e, norte_n = cuad.esquina(fila, columna)
            en_lote = _mascara_del_lote(cuad.lote, oeste_e, norte_n, g, n, n)
            pct_lote = float(en_lote.mean()) * 100
            if pct_lote < config.TESELA_MIN_EN_LOTE_PCT:
                sin_lote += 1
                continue
            e1, n0 = oeste_e + cuad.lado_m, norte_n - cuad.lado_m
            candidatas = imagenes.candidatas(np.array([oeste_e, e1, e1, oeste_e]), np.array([norte_n, norte_n, n0, n0]))
            if candidatas:
                # Entre las más centrales se queda la más nítida (un cuadro movido o desenfocado puede ser el más central)
                cortes = [(_recortar(imagenes.leer(i), fotos[i], oeste_e, norte_n, g, n), i) for i in candidatas]
                bgr, elegida = max(cortes, key=lambda c: _nitidez(c[0]))
                cobertura, fuente, origen, gsd_origen = 100.0, "imagen", fotos[elegida].ruta.name, fotos[elegida].gsd
            else:
                if mezcla is None:
                    mezcla = Compositor(fotos, cuad.marco())
                bgr, alfa = mezcla.region(columna * n, fila * n, n, n)
                dentro = int(en_lote.sum())
                cobertura = float(((alfa > 0) & (en_lote > 0)).sum()) / dentro * 100
                if cobertura < config.TESELA_MIN_COBERTURA_PCT:
                    sin_imagen += 1
                    continue
                fuente, origen = "mezcla", ""
            idt = f"f{fila:03d}_c{columna:03d}"
            imagen.escribir_jpg(destino / f"{idt}.jpg", bgr, config.TESELA_CALIDAD_JPEG, sin_submuestreo_de_color=True)
            de_una += fuente == "imagen"
            mezcladas += fuente == "mezcla"
            lat, lon = cuad.zona.a_geo([oeste_e, e1, e1, oeste_e], [n0, n0, norte_n, norte_n])
            lat_c, lon_c = cuad.zona.a_geo((oeste_e + e1) / 2, (n0 + norte_n) / 2)
            filas_csv.append((
                idt, fila, columna, f"teselas/{idt}.jpg", f"{pct_lote:.1f}", f"{cobertura:.1f}",
                f"{oeste_e:.3f}", f"{n0:.3f}", f"{e1:.3f}", f"{norte_n:.3f}",
                f"{float(np.min(lon)):.8f}", f"{float(np.min(lat)):.8f}", f"{float(np.max(lon)):.8f}", f"{float(np.max(lat)):.8f}",
                f"{float(lat_c):.8f}", f"{float(lon_c):.8f}", f"{g * 100:.3f}", fuente, origen,
            ))
        if al_avanzar:
            al_avanzar((fila + 1) * cuad.columnas / total)
    tmp = carpeta / "teselas.csv.part"
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(COLUMNAS_CSV)
        w.writerows(filas_csv)
    tmp.replace(carpeta / "teselas.csv")
    return {"escritas": len(filas_csv), "de_una_imagen": de_una, "mezcladas": mezcladas, "sin_imagen": sin_imagen, "fuera_del_lote": sin_lote}
