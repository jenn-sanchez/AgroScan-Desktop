"""Leer y escribir imágenes por bytes: `cv2.imread` y `cv2.imwrite` no abren rutas con tildes o ñ en Windows, y la carpeta de datos de la
app cuelga de la carpeta del usuario (por ejemplo «C:/Users/José/Documents»)."""
from pathlib import Path

import cv2
import numpy as np


def leer(ruta: Path, flags: int = cv2.IMREAD_COLOR):
    """La imagen, o None si no existe o no es una imagen válida."""
    try:
        datos = np.fromfile(str(ruta), dtype=np.uint8)
    except OSError:
        return None
    if datos.size == 0:
        return None
    return cv2.imdecode(datos, flags)


def escribir_jpg(ruta: Path, img: np.ndarray, calidad: int, sin_submuestreo_de_color: bool = False) -> None:
    """JPG por defecto submuestrea el color (4:2:0), que no se nota a simple vista pero le quita detalle a un síntoma fino en una hoja; con
    [sin_submuestreo_de_color] se guarda el color a resolución completa (4:4:4)."""
    parametros = [cv2.IMWRITE_JPEG_QUALITY, calidad]
    if sin_submuestreo_de_color:
        parametros += [cv2.IMWRITE_JPEG_SAMPLING_FACTOR, cv2.IMWRITE_JPEG_SAMPLING_FACTOR_444]
    ok, codificada = cv2.imencode(".jpg", img, parametros)
    if not ok:
        raise OSError(f"No se pudo codificar {ruta.name}")
    codificada.tofile(str(ruta))
