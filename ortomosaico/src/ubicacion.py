"""Ubicar cada imagen sobre el terreno: cuándo se tomó, dónde estaba el dron entonces y qué pedazo de suelo cubre.

El preprocesamiento deja en cada imagen (EXIF) el segundo del video de donde salió; con el inicio del video (ver `registro`) eso da el
instante, y el registro de vuelo da posición, altura y rumbo en ese instante.
"""
import math
import re
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from src import config
from src.entrada import Entrada, SalidaEntrada, VideoEntrada
from src.errores import ErrorOrtomosaico
from src.registro import Registro, inicio_del_video_ms, leer_marcas, leer_registro
from src.utm import Zona

_XP_COMENTARIO = 0x9C9C
_SEGUNDO = re.compile(r"Segundo del video:\s*([0-9]+(?:[.,][0-9]+)?)\s*s")
_FRAME_EN_NOMBRE = re.compile(r"_F(\d{6})\.jpe?g$", re.IGNORECASE)


@dataclass
class Foto:
    ruta: Path
    ancho: int
    alto: int
    lat: float
    lon: float
    yaw_rad: float        # rumbo: 0 = norte, crece hacia el este
    altura_m: float       # sobre lo que se ve (copa de los árboles), no sobre el punto de despegue
    t_ms: float = 0.0     # instante en que se tomó (epoch UTC)
    este: float = 0.0
    norte: float = 0.0
    gsd: float = 0.0      # metros de terreno por píxel de la imagen


@dataclass
class Ubicadas:
    fotos: list
    zona: Zona
    total: int
    descartadas: dict      # motivo → cuántas
    origen_tiempo: str     # de dónde salió el inicio del video: «marcas» o «inicio-telefono»


def segundo_del_video(ruta: Path, fps_de_respaldo) -> tuple[float, tuple[int, int]]:
    """El segundo del video de donde salió la imagen y su tamaño (ancho, alto)."""
    with Image.open(ruta) as im:
        tam = im.size
        crudo = im.getexif().get(_XP_COMENTARIO)
    if isinstance(crudo, bytes):
        coincide = _SEGUNDO.search(crudo.decode("utf-16-le", errors="ignore").rstrip("\x00"))
        if coincide:
            return float(coincide.group(1).replace(",", ".")), tam
    frame = _FRAME_EN_NOMBRE.search(ruta.name)
    fps = fps_de_respaldo()
    if frame and fps:
        return int(frame.group(1)) / fps, tam
    raise ErrorOrtomosaico(
        f"La imagen {ruta.name} no dice de qué momento del video salió y no se puede deducir (falta el video para leer sus cuadros por "
        "segundo). Vuelve a preprocesar el video para regenerar las imágenes."
    )


def _listar(carpeta: Path) -> list:
    return sorted(p for p in carpeta.glob("*.jpg")) if carpeta.is_dir() else []


def _duracion_video(video: VideoEntrada, segundos_imagenes: list) -> float:
    """Cuánto dura el video: se lee del archivo si sigue ahí; si no, se estima por la última imagen."""
    if video.archivo and video.archivo.is_file():
        cap = cv2.VideoCapture(str(video.archivo))
        try:
            fps, cuadros = cap.get(cv2.CAP_PROP_FPS), cap.get(cv2.CAP_PROP_FRAME_COUNT)
        finally:
            cap.release()
        if fps > 0 and cuadros > 0:
            return cuadros / fps
    return (max(segundos_imagenes) if segundos_imagenes else 0.0) + 1.0


def _fps(video: VideoEntrada):
    def leer():
        if not (video.archivo and video.archivo.is_file()):
            return None
        cap = cv2.VideoCapture(str(video.archivo))
        try:
            return cap.get(cv2.CAP_PROP_FPS) or None
        finally:
            cap.release()
    return leer


def _fotos_de_salida(salida: SalidaEntrada, entrada: Entrada, descartadas: dict, al_avanzar) -> tuple[list, int, str]:
    registro = leer_registro(salida.registro)
    inicio_ms, origen = inicio_del_video_ms(leer_marcas(salida.eventos), salida.video_inicio_ms)

    fotos, total, desplazamiento = [], 0, 0.0
    for video in salida.videos:
        rutas = _listar(video.imagenes)
        total += len(rutas)
        datos = [(ruta, *segundo_del_video(ruta, _fps(video))) for ruta in rutas]
        for ruta, segundo, (ancho, alto) in datos:
            foto = _foto(ruta, ancho, alto, inicio_ms + (desplazamiento + segundo) * 1000.0, registro, entrada, descartadas)
            if foto:
                fotos.append(foto)
            al_avanzar()
        # Si el vuelo partió la grabación en varios archivos, el siguiente sigue donde terminó este
        desplazamiento += _duracion_video(video, [d[1] for d in datos])
    return fotos, total, origen


def _foto(ruta, ancho, alto, t_ms, registro: Registro, entrada: Entrada, descartadas: dict):
    def descartar(motivo):
        descartadas[motivo] = descartadas.get(motivo, 0) + 1
        return None

    if not registro.cubre(t_ms):
        return descartar("fuera del registro de vuelo")
    p = registro.en(t_ms)
    if p["tramo"] != config.TRAMO_UTIL:
        return descartar("tomadas fuera del barrido (ascenso, tránsito o giro)")
    if abs(p["gimbal_deg"] - config.GIMBAL_NADIR_DEG) > config.GIMBAL_TOLERANCIA_DEG:
        return descartar("con la cámara inclinada")
    altura = p["alt_m"] - entrada.altura_copa_m
    if altura < config.ALTURA_MINIMA_M:
        return descartar("a muy poca altura")
    return Foto(ruta, ancho, alto, p["lat"], p["lon"], p["yaw_rad"], altura, t_ms=t_ms)


def ubicar(entrada: Entrada, al_avanzar=None) -> Ubicadas:
    """Todas las imágenes del vuelo con su sitio sobre el terreno. [al_avanzar] recibe la fracción hecha (0–1)."""
    total_imagenes = sum(len(_listar(v.imagenes)) for s in entrada.salidas for v in s.videos) or 1
    hechas = [0]

    def paso():
        hechas[0] += 1
        if al_avanzar and hechas[0] % 25 == 0:
            al_avanzar(min(1.0, hechas[0] / total_imagenes))

    fotos, total, descartadas, origen = [], 0, {}, "marcas"
    for salida in entrada.salidas:
        f, n, o = _fotos_de_salida(salida, entrada, descartadas, paso)
        fotos += f
        total += n
        if o != "marcas":
            origen = o
    if total == 0:
        raise ErrorOrtomosaico("No hay imágenes preprocesadas para este vuelo. Preprocesa sus videos en Procesamiento y vuelve a intentarlo.")
    if len(fotos) < 2:
        detalle = "; ".join(f"{n} {m}" for m, n in descartadas.items()) or "ninguna usable"
        raise ErrorOrtomosaico(
            f"Casi no quedaron imágenes para armar el ortomosaico ({len(fotos)} de {total}; descartadas: {detalle}). "
            "Revisa que el video y el registro sean de la misma salida."
        )

    fotos.sort(key=lambda f: f.t_ms)    # en el orden en que se tomaron: el ajuste usa que las vecinas en el tiempo se parecen en sus errores
    # La zona UTM sale del lote cuando se conoce (así es la misma en todas las fechas aunque el lote quede cerca del borde entre dos zonas)
    if entrada.lote:
        zona = Zona.de(float(np.mean([p[0] for p in entrada.lote])), float(np.mean([p[1] for p in entrada.lote])))
    else:
        zona = Zona.de(float(np.median([f.lat for f in fotos])), float(np.median([f.lon for f in fotos])))
    este, norte = zona.a_utm(np.array([f.lat for f in fotos]), np.array([f.lon for f in fotos]))
    mitad_v = math.tan(math.radians(entrada.fov_vertical_deg) / 2)
    for foto, e, n in zip(fotos, este, norte):
        foto.este, foto.norte = float(e), float(n)
        foto.gsd = 2 * foto.altura_m * mitad_v / foto.alto
    return Ubicadas(fotos, zona, total, descartadas, origen)
