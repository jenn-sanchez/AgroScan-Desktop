"""Un vuelo inventado con la verdad conocida: un terreno (imagen) y un dron que lo recorre en pasadas, con sus imágenes, su registro
de vuelo y sus marcas. Si el módulo arma bien, el mosaico se parece al terreno y queda donde el terreno está.

Las imágenes se generan con la definición física (arriba en la imagen = hacia donde mira el dron), no con las fórmulas del módulo,
para que un error de signo o de giro en el módulo no se compense a sí mismo.
"""
import json
import math
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from src.utm import Zona

LAT0, LON0 = -0.30, -79.488          # Ecuador: zona 17 sur
GSD_TERRENO = 0.05                    # m por píxel del terreno de verdad
ANCHO_M, ALTO_M = 80.0, 60.0          # este-oeste y norte-sur del terreno
IMG_W, IMG_H = 640, 360
FOV_V = 45.8
ALTURA = 25.0
COPA = 0.0
T0_MS = 1_793_195_900_000             # inicio del registro
VIDEO_OFFSET_S = 7.0                  # el video empieza 7 s después del registro


@dataclass
class Vuelo:
    entrada: Path
    zona: Zona
    este0: float          # esquina noroeste del terreno
    norte1: float
    terreno: np.ndarray   # BGR
    lote: list            # (lat, lon) del contorno del terreno
    n_imagenes: int
    verdad: dict          # nombre de archivo → (este, norte, rumbo en radianes, metros por píxel reales) de donde estaba el dron al tomarla


def _terreno(liso: bool = False) -> np.ndarray:
    h, w = round(ALTO_M / GSD_TERRENO), round(ANCHO_M / GSD_TERRENO)
    if liso:      # sin ningún detalle: no hay puntos que reconocer entre imágenes
        return np.full((h, w, 3), 128, np.uint8)
    rng = np.random.default_rng(7)
    base = cv2.GaussianBlur(rng.random((h, w, 3)).astype(np.float32), (0, 0), 6)
    base = (base - base.min()) / (base.max() - base.min())
    img = (60 + 160 * base).astype(np.uint8)
    # Marcas que no son simétricas: si el mosaico saliera girado o espejado, no se parecería al terreno
    cv2.rectangle(img, (0, 0), (w // 3, h // 3), (0, 0, 255), -1)                       # noroeste: rojo
    cv2.rectangle(img, (w - w // 4, h - h // 3), (w - 1, h - 1), (255, 80, 0), -1)      # sureste: azul
    for x in range(0, w, round(10 / GSD_TERRENO)):
        cv2.line(img, (x, 0), (x, h - 1), (255, 255, 255), 2)
    for y in range(0, h, round(10 / GSD_TERRENO)):
        cv2.line(img, (0, y), (w - 1, y), (0, 255, 255), 2)
    return img


def _imagen_desde(terreno, zona, este0, norte1, e, n, rumbo_rad, gsd) -> np.ndarray:
    """Lo que ve el dron en (e, n) mirando hacia [rumbo_rad]: arriba es hacia donde mira, derecha es su derecha."""
    adelante = np.array([math.sin(rumbo_rad), math.cos(rumbo_rad)])     # (este, norte)
    derecha = np.array([math.cos(rumbo_rad), -math.sin(rumbo_rad)])
    u, v = np.meshgrid(np.arange(IMG_W), np.arange(IMG_H))
    d = (u - (IMG_W - 1) / 2) * gsd
    f = ((IMG_H - 1) / 2 - v) * gsd
    este = e + d * derecha[0] + f * adelante[0]
    norte = n + d * derecha[1] + f * adelante[1]
    mapa_x = ((este - este0) / GSD_TERRENO).astype(np.float32)
    mapa_y = ((norte1 - norte) / GSD_TERRENO).astype(np.float32)
    return cv2.remap(terreno, mapa_x, mapa_y, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))


def _ruido_correlacionado(rng, n: int, sigma: float) -> np.ndarray:
    """Un error de GPS que cambia despacio (como el real: no salta de una lectura a otra), con desvío [sigma] metros."""
    r = np.zeros((n, 2))
    for k in range(1, n):
        r[k] = 0.98 * r[k - 1] + math.sqrt(1 - 0.98**2) * sigma * rng.standard_normal(2)
    return r


def crear(
    carpeta: Path, con_marcas: bool = True, gimbal_inclinado: bool = False, gps_sesgo: tuple = (0.0, 0.0), gps_ruido_m: float = 0.0,
    yaw_error_grados: float = 0.0, semilla: int = 1, ajustar: bool = True, referencia: Path | None = None,
    liso: bool = False, con_lote: bool = True, suelo_m: float = 0.0, pendiente: float = 0.0,
) -> Vuelo:
    carpeta.mkdir(parents=True, exist_ok=True)
    zona = Zona.de(LAT0, LON0)
    e_c, n_c = zona.a_utm(LAT0, LON0)
    este0, norte1 = float(e_c), float(n_c)
    terreno = _terreno(liso)
    gsd = 2 * (ALTURA - COPA) * math.tan(math.radians(FOV_V) / 2) / IMG_H
    ancho_foto, alto_foto = gsd * IMG_W, gsd * IMG_H

    def altura_real(e: float) -> float:
        """Altura sobre el suelo en el este [e]: el registro dice siempre ALTURA sobre el despegue, pero el suelo puede estar más abajo
        ([suelo_m] negativo: el dron despegó desde una loma) y subir o bajar hacia el este ([pendiente], metros por metro)."""
        return ALTURA - COPA - (suelo_m + pendiente * (e - este0))

    # Pasadas este-oeste (el rumbo alterna 90° y 270°); «arriba» en la imagen es a lo largo de la pasada
    paso_pasadas = ancho_foto * 0.7
    norte_filas = np.arange(norte1 - ancho_foto / 2 + 3, norte1 - ALTO_M + ancho_foto / 2 - 3, -paso_pasadas)
    norte_filas = np.append(norte_filas, norte1 - ALTO_M + 2) if norte_filas[-1] > norte1 - ALTO_M + ancho_foto / 2 else norte_filas
    avance = alto_foto * 0.25            # entre una imagen y la siguiente
    velocidad = 4.0
    margen = alto_foto / 2

    rng = np.random.default_rng(semilla)
    verdad = {}
    ruido = _ruido_correlacionado(rng, 200_000, gps_ruido_m) if gps_ruido_m else np.zeros((200_000, 2))
    paso_ruido = 0
    filas_csv, marcas, imagenes_dir = [], [], carpeta / "imagenes_procesadas"
    imagenes_dir.mkdir(exist_ok=True)
    t_s, n_img = 0.0, 0
    distancia_marca = 0.0
    for i, n in enumerate(norte_filas):
        este_a, este_b = este0 - margen, este0 + ANCHO_M + margen
        sentido = 1 if i % 2 == 0 else -1
        rumbo = 90.0 if sentido == 1 else 270.0
        largo = este_b - este_a
        pasos = int(largo / (velocidad * 0.2))
        ultimo_frame_m = -1e9
        for k in range(pasos + 1):
            recorrido = k * velocidad * 0.2
            e = (este_a + recorrido) if sentido == 1 else (este_b - recorrido)
            lat, lon = zona.a_geo(e + gps_sesgo[0] + ruido[paso_ruido, 0], n + gps_sesgo[1] + ruido[paso_ruido, 1])
            paso_ruido += 1
            filas_csv.append(f"{T0_MS + int(t_s * 1000)},{float(lat):.8f},{float(lon):.8f},{ALTURA + COPA},{rumbo + yaw_error_grados},"
                             f"{-30 if gimbal_inclinado else -90},{velocidad},{i},barrido,FLYING,1")
            if recorrido - ultimo_frame_m >= avance:
                ultimo_frame_m = recorrido
                segundo_video = t_s - VIDEO_OFFSET_S
                if segundo_video >= 0:
                    gsd_real = 2 * altura_real(e) * math.tan(math.radians(FOV_V) / 2) / IMG_H
                    img = _imagen_desde(terreno, zona, este0, norte1, e, n, math.radians(rumbo), gsd_real)
                    exif = Image.Exif()
                    exif[0x9C9C] = f"Video: V | Frame: {n_img} | Segundo del video: {segundo_video:.1f} s | Pasada: {i}".encode("utf-16-le") + b"\x00\x00"
                    nombre = f"V_P{i}_I{k:03d}_F{n_img:06d}.jpg"
                    Image.fromarray(img[..., ::-1]).save(imagenes_dir / nombre, quality=95, exif=exif)
                    verdad[nombre] = (float(e), float(n), math.radians(rumbo), gsd_real)
                    n_img += 1
                    marcas.append(f'{{"t_ms":{T0_MS + int(t_s * 1000)},"evento":"marca","n":{n_img},"t_video_s":{segundo_video:.2f}}}')
            t_s += 0.2
        # Un giro entre pasadas: tramo «giro», sin imágenes útiles
        for _ in range(5):
            filas_csv.append(f"{T0_MS + int(t_s * 1000)},{float(lat):.8f},{float(lon):.8f},{ALTURA},{rumbo},-90,1.0,{i},giro,FLYING,1")
            t_s += 0.2

    registro = carpeta / "registro.csv"
    registro.write_text("t_ms,lat,lon,alt_m,yaw_deg,gimbal_pitch_deg,speed_ms,wp,tramo,fase,grabando\n" + "\n".join(filas_csv) + "\n", encoding="utf-8")
    eventos = carpeta / "eventos.jsonl"
    eventos.write_text("\n".join(['{"t_ms":%d,"evento":"inicio"}' % T0_MS] + (marcas if con_marcas else [])) + "\n", encoding="utf-8")

    esquinas = [(LAT0, LON0)] + [tuple(float(x) for x in zona.a_geo(este0 + dx, norte1 - dy)) for dx, dy in ((ANCHO_M, 0), (ANCHO_M, ALTO_M), (0, ALTO_M))]
    lote = [esquinas[0], esquinas[1], esquinas[2], esquinas[3]]
    entrada = carpeta / "entrada.json"
    entrada.write_text(json.dumps({
        "v": 1, "vuelo": "9f8e7d6c-3333-4333-8333-333333333333", "calidad": "alta", "origen": "originales",
        "altura_copa_m": COPA, "camara": {"fov_vertical_deg": FOV_V}, "lote": lote if con_lote else [], "ajustar": ajustar,
        **({"referencia": {"carpeta": str(referencia), "vuelo": "0a1b2c3d-4444-4444-8444-444444444444"}} if referencia else {}),
        "salidas": [{
            "indice": 0, "registro": str(registro), "eventos": str(eventos), "video_inicio_ms": T0_MS + int(VIDEO_OFFSET_S * 1000) + 300,
            "videos": [{"nombre": "V", "archivo": None, "imagenes": str(imagenes_dir)}],
        }],
    }), encoding="utf-8")
    return Vuelo(entrada, zona, este0, norte1, terreno, lote, n_img, verdad)


def calidad(vuelo: Vuelo, geotiff: Path, quitar_desplazamiento_global: bool = False) -> tuple[float, float]:
    """(correlación, error medio en niveles de gris) del mosaico contra el terreno verdadero, donde hay imagen y terreno.

    Con [quitar_desplazamiento_global] primero se corre el terreno lo que haga falta para que coincida con el mosaico en conjunto: mide qué
    tan coherente es el mosaico por dentro (sin fantasmas ni costuras) aunque todo el bloque esté corrido por el error común del GPS.
    """
    import tifffile
    with tifffile.TiffFile(geotiff) as tf:
        rgba = tf.pages[0].asarray()
        punto = tf.pages[0].tags[33922].value
        g = tf.pages[0].tags[33550].value[0]
    bgr = rgba[..., 2::-1].copy()
    ys, xs = np.mgrid[0:rgba.shape[0], 0:rgba.shape[1]]
    tx = ((punto[3] + (xs + 0.5) * g - vuelo.este0) / GSD_TERRENO).astype(np.float32)
    ty = ((vuelo.norte1 - (punto[4] - (ys + 0.5) * g)) / GSD_TERRENO).astype(np.float32)
    gris = lambda a: cv2.GaussianBlur(cv2.cvtColor(a, cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 2)
    hay = rgba[..., 3] > 0
    if quitar_desplazamiento_global:
        # El mosaico respecto del terreno: se mide cuánto está corrido y se muestrea el terreno corrido lo mismo
        prueba = cv2.remap(vuelo.terreno, tx, ty, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
        (dx, dy), _ = cv2.phaseCorrelate(gris(bgr) * hay, gris(prueba) * hay)
        tx, ty = tx + np.float32(dx * g / GSD_TERRENO), ty + np.float32(dy * g / GSD_TERRENO)
    verdad = cv2.remap(vuelo.terreno, tx, ty, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    dentro = hay & (tx > 5) & (ty > 5) & (tx < vuelo.terreno.shape[1] - 5) & (ty < vuelo.terreno.shape[0] - 5)
    a, b = gris(bgr)[dentro], gris(verdad)[dentro]
    return float(np.corrcoef(a, b)[0, 1]), float(np.abs(a - b).mean())
