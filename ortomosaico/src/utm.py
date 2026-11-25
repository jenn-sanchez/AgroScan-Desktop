"""Coordenadas UTM (WGS84) con la serie de Krüger: error de milímetros dentro de la zona, sin depender de pyproj.

El mosaico se arma en metros (UTM) porque ahí un píxel mide lo mismo en todas partes; solo al final se convierte a latitud/longitud.
"""
import math

import numpy as np

_A = 6378137.0
_F = 1 / 298.257223563
_K0 = 0.9996
_N = _F / (2 - _F)
_R = _A / (1 + _N) * (1 + _N**2 / 4 + _N**4 / 64)
_ALFA = (
    _N / 2 - 2 * _N**2 / 3 + 5 * _N**3 / 16,
    13 * _N**2 / 48 - 3 * _N**3 / 5,
    61 * _N**3 / 240,
)
_BETA = (
    _N / 2 - 2 * _N**2 / 3 + 37 * _N**3 / 96,
    _N**2 / 48 + _N**3 / 15,
    17 * _N**3 / 480,
)
_DELTA = (
    2 * _N - 2 * _N**2 / 3 - 2 * _N**3,
    7 * _N**2 / 3 - 8 * _N**3 / 5,
    56 * _N**3 / 15,
)
_FALSO_NORTE_SUR = 10_000_000.0


class Zona:
    """Una zona UTM: la que le toca a un punto, para que todo el mosaico use la misma."""

    def __init__(self, numero: int, sur: bool):
        self.numero = numero
        self.sur = sur

    @classmethod
    def de(cls, lat: float, lon: float) -> "Zona":
        return cls(min(60, max(1, int((lon + 180) // 6) + 1)), lat < 0)

    @property
    def epsg(self) -> int:
        return (32700 if self.sur else 32600) + self.numero

    @property
    def crs(self) -> str:
        return f"EPSG:{self.epsg}"

    @property
    def _lon0(self) -> float:
        return math.radians((self.numero - 1) * 6 - 180 + 3)

    def a_utm(self, lat, lon):
        """(latitud, longitud) en grados → (este, norte) en metros. Admite números o arreglos."""
        phi = np.radians(np.asarray(lat, dtype=float))
        dlam = np.radians(np.asarray(lon, dtype=float)) - self._lon0
        c = 2 * math.sqrt(_N) / (1 + _N)
        t = np.sinh(np.arctanh(np.sin(phi)) - c * np.arctanh(c * np.sin(phi)))
        xi0 = np.arctan2(t, np.cos(dlam))
        eta0 = np.arctanh(np.sin(dlam) / np.sqrt(1 + t * t))
        xi, eta = xi0, eta0
        for j, a in enumerate(_ALFA, start=1):
            xi = xi + a * np.sin(2 * j * xi0) * np.cosh(2 * j * eta0)
            eta = eta + a * np.cos(2 * j * xi0) * np.sinh(2 * j * eta0)
        este = 500_000.0 + _K0 * _R * eta
        norte = _K0 * _R * xi + (_FALSO_NORTE_SUR if self.sur else 0.0)
        return este, norte

    def a_geo(self, este, norte):
        """(este, norte) en metros → (latitud, longitud) en grados."""
        n = np.asarray(norte, dtype=float) - (_FALSO_NORTE_SUR if self.sur else 0.0)
        xi0 = n / (_K0 * _R)
        eta0 = (np.asarray(este, dtype=float) - 500_000.0) / (_K0 * _R)
        xi, eta = xi0, eta0
        for j, b in enumerate(_BETA, start=1):
            xi = xi - b * np.sin(2 * j * xi0) * np.cosh(2 * j * eta0)
            eta = eta - b * np.cos(2 * j * xi0) * np.sinh(2 * j * eta0)
        chi = np.arcsin(np.sin(xi) / np.cosh(eta))
        phi = chi
        for j, d in enumerate(_DELTA, start=1):
            phi = phi + d * np.sin(2 * j * chi)
        lam = self._lon0 + np.arctan2(np.sinh(eta), np.cos(xi))
        return np.degrees(phi), np.degrees(lam)
