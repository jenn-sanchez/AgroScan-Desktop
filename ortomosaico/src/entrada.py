"""La orden que manda la app (`entrada.json`): qué vuelo armar, con qué imágenes y con qué ajustes.

Formato (versión 1), todo lo que no es obligatorio puede faltar o ser null:
```
{"v": 1, "vuelo": "<uuid>", "calidad": "rapida|estandar|alta", "origen": "originales|corregidas",
 "altura_copa_m": 4.0, "camara": {"fov_vertical_deg": 45.8}, "lote": [[lat, lon], …],
 "ajustar": true, "referencia": {"carpeta": "…/ortomosaicos/Finca_1a2b3c4d/2026-10-26_9f8e7d6c", "vuelo": "<uuid>"},
 "salidas": [{"indice": 0, "registro": "…/registro.csv", "eventos": "…/eventos.jsonl", "video_inicio_ms": 1793195900084,
              "videos": [{"nombre": "DJI_0001", "archivo": "…/DJI_0001.mp4", "imagenes": "…/imagenes_procesadas"}]}]}
```
`ajustar` (por defecto true) activa el ajuste de las imágenes entre sí; `referencia` es la carpeta de otro ortomosaico del mismo lote con el
que alinear este (necesita su `georef.json` y su `vista.jpg`). Las rutas son absolutas. El video puede ya no estar (se borran para liberar espacio): solo se usa para saber cuánto dura si el vuelo
partió la grabación en varios archivos.
"""
import json
from dataclasses import dataclass, field
from pathlib import Path

from src import config
from src.errores import ErrorOrtomosaico


@dataclass
class VideoEntrada:
    nombre: str
    archivo: Path | None
    imagenes: Path


@dataclass
class SalidaEntrada:
    indice: int
    registro: Path
    eventos: Path
    video_inicio_ms: float | None
    videos: list[VideoEntrada]


@dataclass
class Referencia:
    """Otro ortomosaico del mismo lote (el de la primera fecha) con el que alinear este."""
    carpeta: Path
    vuelo: str


@dataclass
class Entrada:
    vuelo: str
    calidad: str
    origen: str
    altura_copa_m: float
    fov_vertical_deg: float
    lote: list[tuple[float, float]] = field(default_factory=list)
    salidas: list[SalidaEntrada] = field(default_factory=list)
    ajustar: bool = True
    referencia: Referencia | None = None


def leer_entrada(archivo: Path) -> Entrada:
    archivo = Path(archivo)
    try:
        j = json.loads(archivo.read_text(encoding="utf-8"))
        if j.get("v") != 1:
            raise ValueError("versión de formato desconocida")
        calidad = j.get("calidad", "estandar")
        if calidad not in config.FACTOR_RESOLUCION:
            raise ValueError(f"calidad desconocida: {calidad}")
        camara = j.get("camara") or {}
        fov = float(camara.get("fov_vertical_deg") or config.FOV_VERTICAL_DEG)
        if not 10 < fov < 120:
            raise ValueError(f"campo de visión fuera de rango: {fov}")
        salidas = [
            SalidaEntrada(
                indice=int(s["indice"]), registro=Path(s["registro"]), eventos=Path(s["eventos"]),
                video_inicio_ms=float(s["video_inicio_ms"]) if s.get("video_inicio_ms") else None,
                videos=[
                    VideoEntrada(v["nombre"], Path(v["archivo"]) if v.get("archivo") else None, Path(v["imagenes"]))
                    for v in s["videos"]
                ],
            )
            for s in j["salidas"]
        ]
        if not salidas:
            raise ValueError("no hay salidas")
        return Entrada(
            vuelo=str(j["vuelo"]), calidad=calidad, origen=str(j.get("origen", "")),
            altura_copa_m=max(0.0, float(j.get("altura_copa_m") or 0.0)), fov_vertical_deg=fov,
            lote=[(float(p[0]), float(p[1])) for p in j.get("lote") or []], salidas=salidas,
            ajustar=bool(j.get("ajustar", True)),
            referencia=Referencia(Path(j["referencia"]["carpeta"]), str(j["referencia"].get("vuelo", ""))) if j.get("referencia") else None,
        )
    except (OSError, ValueError, KeyError, TypeError) as e:
        raise ErrorOrtomosaico(f"La orden de armado no se pudo leer ({archivo.name}: {e}). Vuelve a pedir el armado desde la app.") from e
