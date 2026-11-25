"""El registro de vuelo de una salida (`registro.csv`, una fila cada ~200 ms) y las marcas del video (`eventos.jsonl`).

Con eso se sabe dónde estaba el dron, a qué altura y hacia dónde miraba en cada instante, y se sincroniza el reloj del video con el del
registro (el video guarda cuadros; solo el registro sabe de dónde se tomaron).
"""
import csv
import json
import math
import statistics
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from src import config
from src.errores import ErrorOrtomosaico

_COLUMNAS = ("t_ms", "lat", "lon", "alt_m", "yaw_deg", "gimbal_pitch_deg")


@dataclass
class Registro:
    t_ms: np.ndarray
    lat: np.ndarray
    lon: np.ndarray
    alt_m: np.ndarray
    yaw_rad: np.ndarray        # desenrollado: sin saltos de 360°, para poder interpolarlo
    gimbal_deg: np.ndarray
    tramo: list
    grabando: np.ndarray

    def __len__(self) -> int:
        return len(self.t_ms)

    def cubre(self, t_ms: float) -> bool:
        return bool(self.t_ms[0] <= t_ms <= self.t_ms[-1])

    def en(self, t_ms: float) -> dict:
        """Posición, altura, rumbo y gimbal interpolados en el instante [t_ms] (epoch UTC en ms); `tramo` es el de la fila más cercana."""
        i = min(max(int(np.searchsorted(self.t_ms, t_ms)), 1), len(self.t_ms) - 1)
        cercano = i if abs(self.t_ms[i] - t_ms) < abs(self.t_ms[i - 1] - t_ms) else i - 1
        return {
            "lat": float(np.interp(t_ms, self.t_ms, self.lat)),
            "lon": float(np.interp(t_ms, self.t_ms, self.lon)),
            "alt_m": float(np.interp(t_ms, self.t_ms, self.alt_m)),
            "yaw_rad": float(np.interp(t_ms, self.t_ms, self.yaw_rad)),
            "gimbal_deg": float(np.interp(t_ms, self.t_ms, self.gimbal_deg)),
            "tramo": self.tramo[cercano],
            "grabando": bool(self.grabando[cercano]),
        }


def leer_registro(archivo: Path) -> Registro:
    archivo = Path(archivo)
    if not archivo.is_file():
        raise ErrorOrtomosaico(
            f"Falta el registro de vuelo ({archivo.name}). Llega solo del teléfono al terminar la salida; "
            "si volaste sin la PC cerca, en el teléfono pulsa «Enviar vuelos pendientes»."
        )
    filas = []
    with open(archivo, "r", encoding="utf-8", newline="") as f:
        lector = csv.DictReader(f)
        faltan = [c for c in _COLUMNAS if c not in (lector.fieldnames or [])]
        if faltan:
            raise ErrorOrtomosaico(f"El registro de vuelo ({archivo.name}) no tiene las columnas {', '.join(faltan)}: no es un registro válido.")
        for fila in lector:
            try:
                numeros = [float(fila[c]) for c in _COLUMNAS]
            except (TypeError, ValueError):
                continue    # una fila a medias (corte de energía, mala transmisión) no invalida el resto
            if not all(math.isfinite(x) for x in numeros) or (numeros[1] == 0 and numeros[2] == 0):
                continue    # sin posición GPS todavía
            filas.append((numeros, (fila.get("tramo") or "").strip(), (fila.get("grabando") or "0").strip() == "1"))
    filas.sort(key=lambda x: x[0][0])
    if len(filas) < 2:
        raise ErrorOrtomosaico(f"El registro de vuelo ({archivo.name}) casi no tiene posiciones: no alcanza para ubicar las imágenes.")
    a = np.array([f[0] for f in filas])
    t, indices = np.unique(a[:, 0], return_index=True)     # tiempos repetidos romperían la interpolación
    a = a[indices]
    return Registro(
        t_ms=t, lat=a[:, 1], lon=a[:, 2], alt_m=a[:, 3],
        yaw_rad=np.unwrap(np.radians(a[:, 4])), gimbal_deg=a[:, 5],
        tramo=[filas[i][1] for i in indices], grabando=np.array([filas[i][2] for i in indices]),
    )


def leer_marcas(archivo: Path) -> list:
    """Las marcas del video: (instante del registro en ms, segundo del video) de cada una."""
    marcas = []
    archivo = Path(archivo)
    if not archivo.is_file():
        return marcas
    with open(archivo, "r", encoding="utf-8") as f:
        for linea in f:
            if '"marca"' not in linea:
                continue
            try:
                e = json.loads(linea)
                marcas.append((float(e["t_ms"]), float(e["t_video_s"])))
            except (ValueError, KeyError, TypeError):
                continue
    return marcas


def inicio_del_video_ms(marcas: list, respaldo_ms: float | None) -> tuple[float, str]:
    """El instante (epoch ms) en que empezó a grabar el video, y de dónde sale.

    Cada marca dice «en este instante del registro el video iba por el segundo X», así que su diferencia da el inicio; se toma la
    mediana de todas para que una marca rara no mueva el resultado. Sin marcas se usa el inicio que anotó el teléfono.
    """
    if len(marcas) >= config.MARCAS_MINIMAS:
        inicios = [t - s * 1000.0 for t, s in marcas]
        mediana = statistics.median(inicios)
        desvio = statistics.pstdev(inicios) / 1000.0
        if desvio > config.DESVIO_MARCAS_MAX_S:
            raise ErrorOrtomosaico(
                f"Las marcas del video no concuerdan entre sí (varían {desvio:.1f} s): el video y el registro no parecen ser de la misma "
                "salida. Revisa que el video importado sea el de esta salida."
            )
        return mediana, "marcas"
    if respaldo_ms is not None:
        return float(respaldo_ms), "inicio-telefono"
    raise ErrorOrtomosaico(
        "No se puede saber en qué momento empezó el video: la salida no tiene marcas del video ni hora de inicio de la grabación. "
        "Sin eso no se sabe dónde se tomó cada imagen."
    )
