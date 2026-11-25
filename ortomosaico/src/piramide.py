"""La pirámide de teselas del mosaico, para verlo en pantalla a su resolución real.

`vista.jpg` es el mosaico entero en pequeño (3072 px de lado como máximo): sirve para verlo completo, pero un lote grande queda a 10–20 cm por píxel y
al acercarse se ve borroso aunque el mosaico en sí esté nítido. La pirámide guarda el mosaico en teselas JPG de 512 px a varios niveles, como un mapa: el
visor pide solo las del nivel y la zona que se están mirando y dibuja encima de la vista, así se ve el detalle real al acercar.

    piramide/<nivel>/<fila>_<columna>.jpg      nivel 0 = resolución completa; cada nivel siguiente tiene la mitad de píxeles por lado
    piramide.json                               {"lado", "ancho_px", "alto_px", "gsd_m", "niveles"}  (tamaño y resolución del nivel 0)

Solo se guardan los niveles más finos que la vista (los demás los cubre ella): un lote pequeño, que la vista ya muestra a resolución completa, no
genera pirámide. Los bordes se guardan recortados (sin relleno) y el fondo donde no hay imagen es el mismo gris del visor.
"""
import json
import math
from pathlib import Path

import numpy as np

from src import config, imagen


class EscritorPiramide:

    def __init__(self, carpeta: Path, ancho_px: int, alto_px: int, gsd_m: float, gsd_vista_m: float):
        self.carpeta, self.ancho, self.alto, self.gsd = carpeta, ancho_px, alto_px, gsd_m
        self.lado = config.PIRAMIDE_LADO_PX
        # Niveles que aportan algo: los de menos metros por píxel que la vista (con un 10 % de margen)
        self.niveles = 0
        while self.niveles < config.PIRAMIDE_MAX_NIVELES and gsd_m * 2**self.niveles < gsd_vista_m / 1.1:
            self.niveles += 1
        self._fondo = np.array(config.FONDO_VISTA[::-1], np.uint8)

    @property
    def necesaria(self) -> bool:
        return self.niveles > 0

    def _guardar(self, nivel: int, fila: int, col: int, bgr: np.ndarray) -> None:
        carpeta = self.carpeta / "piramide" / str(nivel)
        carpeta.mkdir(parents=True, exist_ok=True)
        imagen.escribir_jpg(carpeta / f"{fila}_{col}.jpg", bgr, config.PIRAMIDE_CALIDAD_JPEG)

    def nivel0(self, fila: int, col: int, bgr: np.ndarray, alfa: np.ndarray) -> None:
        """Guarda una tesela del nivel completo (tal como sale del mosaico, sin relleno de borde)."""
        self._guardar(0, fila, col, np.where(alfa[..., None] > 0, bgr, self._fondo))

    def _dims(self, nivel: int) -> tuple[int, int]:
        f = 2**nivel
        return math.ceil(self.ancho / f), math.ceil(self.alto / f)

    def terminar(self) -> dict:
        """Arma los niveles de menos resolución juntando de a cuatro teselas del nivel anterior, y escribe `piramide.json`."""
        n = self.lado
        for nivel in range(1, self.niveles):
            w_prev, h_prev = self._dims(nivel - 1)
            w, h = self._dims(nivel)
            for fila in range(math.ceil(h / n)):
                for col in range(math.ceil(w / n)):
                    lienzo = np.empty((2 * n, 2 * n, 3), np.uint8)
                    lienzo[:] = self._fondo
                    for dy in (0, 1):
                        for dx in (0, 1):
                            hijo = self.carpeta / "piramide" / str(nivel - 1) / f"{2 * fila + dy}_{2 * col + dx}.jpg"
                            img = imagen.leer(hijo) if hijo.is_file() else None
                            if img is not None:
                                lienzo[dy * n:dy * n + img.shape[0], dx * n:dx * n + img.shape[1]] = img
                    ancho_real, alto_real = min(2 * n, w_prev - 2 * col * n), min(2 * n, h_prev - 2 * fila * n)
                    util = lienzo[:alto_real, :ancho_real]
                    reducida = _a_la_mitad(util)
                    self._guardar(nivel, fila, col, reducida)
        meta = {"v": 1, "lado": n, "ancho_px": self.ancho, "alto_px": self.alto, "gsd_m": self.gsd, "niveles": self.niveles}
        tmp = self.carpeta / "piramide.json.part"
        tmp.write_text(json.dumps(meta), encoding="utf-8")
        tmp.replace(self.carpeta / "piramide.json")
        return meta


def _a_la_mitad(img: np.ndarray) -> np.ndarray:
    import cv2
    return cv2.resize(img, (max(1, math.ceil(img.shape[1] / 2)), max(1, math.ceil(img.shape[0] / 2))), interpolation=cv2.INTER_AREA)
