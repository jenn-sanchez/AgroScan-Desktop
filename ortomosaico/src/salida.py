"""Los archivos que deja el módulo: el GeoTIFF georreferenciado, la vista para el visor y la miniatura."""
import json
from pathlib import Path

import cv2
import numpy as np
import tifffile

from src import imagen
from src import config
from src.lienzo import Compositor, Marco
from src.utm import Zona

_TAG_ESCALA = 33550          # ModelPixelScaleTag
_TAG_PUNTO = 33922           # ModelTiepointTag
_TAG_CLAVES = 34735          # GeoKeyDirectoryTag


def _etiquetas_geotiff(marco: Marco, zona: Zona) -> list:
    """Las etiquetas GeoTIFF mínimas: proyectado, píxel = área, UTM de [zona], metros; la esquina noroeste en (0,0)."""
    claves = (1, 1, 0, 3,
              1024, 0, 1, 1,                 # GTModelType = proyectado
              1025, 0, 1, 1,                 # GTRasterType = PixelIsArea
              3072, 0, 1, zona.epsg)         # ProjectedCSType = EPSG de la zona UTM
    return [
        (_TAG_ESCALA, "d", 3, (marco.g, marco.g, 0.0), True),
        (_TAG_PUNTO, "d", 6, (0.0, 0.0, 0.0, marco.este0, marco.norte1, 0.0), True),
        (_TAG_CLAVES, "H", len(claves), claves, True),
    ]


def escribir_geotiff(ruta: Path, compositor: Compositor, zona: Zona, al_avanzar=None) -> None:
    """Escribe el mosaico como GeoTIFF RGBA (el alfa marca dónde no hay imagen), en teselas y comprimido, por bandas de filas."""
    marco, t = compositor.marco, config.TESELA_PX
    filas_banda = max(t, (16_000_000 // marco.ancho) // t * t)

    def teselas():
        for y0 in range(0, marco.alto, filas_banda):
            bgr, alfa = compositor.banda(y0, filas_banda)
            rgba = np.dstack([bgr[..., ::-1], alfa])
            for ty in range(0, rgba.shape[0], t):
                for tx in range(0, marco.ancho, t):
                    tesela = rgba[ty:ty + t, tx:tx + t]
                    if tesela.shape[0] != t or tesela.shape[1] != t:      # las del borde se completan: el formato las pide enteras
                        tesela = np.pad(tesela, ((0, t - tesela.shape[0]), (0, t - tesela.shape[1]), (0, 0)))
                    yield np.ascontiguousarray(tesela)
            if al_avanzar:
                al_avanzar(min(1.0, (y0 + filas_banda) / marco.alto))

    tmp = ruta.with_name(ruta.name + ".part")
    with tifffile.TiffWriter(tmp, bigtiff=True) as tf:
        tf.write(
            teselas(), shape=(marco.alto, marco.ancho, 4), dtype=np.uint8, tile=(t, t), photometric="rgb",
            extrasamples=(2,), compression="adobe_deflate", extratags=_etiquetas_geotiff(marco, zona), metadata=None,
        )
    tmp.replace(ruta)


def escribir_vista(ruta: Path, miniatura: Path, bgr: np.ndarray, alfa: np.ndarray) -> None:
    """vista.jpg (lo que muestra el visor; un GeoTIFF no se puede mostrar tal cual) y la miniatura del historial."""
    fondo = np.array(config.FONDO_VISTA[::-1], np.uint8)       # BGR
    sobre = np.where(alfa[..., None] > 0, bgr, fondo)
    imagen.escribir_jpg(ruta, sobre, config.CALIDAD_JPEG)
    lado = max(sobre.shape[:2])
    f = min(1.0, config.LADO_MINIATURA_PX / lado)
    chica = cv2.resize(sobre, (max(1, round(sobre.shape[1] * f)), max(1, round(sobre.shape[0] * f))), interpolation=cv2.INTER_AREA)
    imagen.escribir_jpg(miniatura, chica, config.CALIDAD_JPEG)


def escribir_json(ruta: Path, datos: dict) -> None:
    """Escribe un JSON de una vez: un corte a mitad nunca deja un archivo a medias con el nombre final."""
    tmp = ruta.with_name(ruta.name + ".part")
    tmp.write_text(json.dumps(datos, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(ruta)


def escribir_cobertura(ruta: Path, alfa: np.ndarray, marco: Marco, zona: Zona, lote_utm) -> None:
    """`cobertura.geojson`: el contorno de lo que cubre el mosaico y el del lote, en latitud/longitud (para recortar o enmascarar)."""
    def anillo(px: np.ndarray) -> list:
        este = marco.este0 + (px[:, 0] + 0.5) * marco.g
        norte = marco.norte1 - (px[:, 1] + 0.5) * marco.g
        lat, lon = zona.a_geo(este, norte)
        pts = [[float(lo), float(la)] for la, lo in zip(lat, lon)]
        return pts + [pts[0]]

    contornos, _ = cv2.findContours((alfa > 0).astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    poligonos = []
    for c in contornos:
        if cv2.contourArea(c) < 20:
            continue
        simple = cv2.approxPolyDP(c, 1.5, True).reshape(-1, 2).astype(float)
        if len(simple) >= 3:
            poligonos.append([anillo(simple)])
    features = [{"type": "Feature", "properties": {"tipo": "cobertura"}, "geometry": {"type": "MultiPolygon", "coordinates": poligonos}}]
    if lote_utm is not None:
        lat, lon = zona.a_geo(lote_utm[:, 0], lote_utm[:, 1])
        pts = [[float(lo), float(la)] for la, lo in zip(lat, lon)]
        features.append({"type": "Feature", "properties": {"tipo": "lote"}, "geometry": {"type": "Polygon", "coordinates": [pts + [pts[0]]]}})
    escribir_json(ruta, {"type": "FeatureCollection", "features": features})
