"""La mezcla de imágenes superpuestas: con una potencia alta manda la imagen más central; la cobertura no depende de la potencia."""
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src import config, imagen
from src.lienzo import Compositor, hacer_marco
from src.ubicacion import Foto


def _dos_fotos(tmp_path):
    """Dos imágenes lisas (50 y 200 de gris) que se superponen: la de 50 centrada en x=1000, la de 200 en x=1006."""
    a, b = tmp_path / "a.jpg", tmp_path / "b.jpg"
    imagen.escribir_jpg(a, np.full((60, 100, 3), 50, np.uint8), 95)
    imagen.escribir_jpg(b, np.full((60, 100, 3), 200, np.uint8), 95)
    return [Foto(a, 100, 60, 0, 0, 0.0, 10.0, este=1000.0, norte=2000.0, gsd=0.2), Foto(b, 100, 60, 0, 0, 0.0, 10.0, este=1006.0, norte=2000.0, gsd=0.2)]


@pytest.mark.parametrize("potencia", [1.0, 3.0, 40.0, 200.0])
def test_la_cobertura_no_depende_de_la_potencia(tmp_path, monkeypatch, potencia):
    fotos = _dos_fotos(tmp_path)
    monkeypatch.setattr(config, "POTENCIA_PESO", 1.0)
    marco = hacer_marco(fotos, "alta")
    _, referencia = Compositor(fotos, marco).todo()
    monkeypatch.setattr(config, "POTENCIA_PESO", potencia)
    _, alfa = Compositor(fotos, marco).todo()
    assert (alfa > 0).sum() == (referencia > 0).sum() and (alfa > 0).sum() > 0


def test_con_potencia_alta_manda_la_imagen_mas_central_y_con_baja_se_promedian(tmp_path, monkeypatch):
    fotos = _dos_fotos(tmp_path)
    marco = hacer_marco(fotos, "alta")
    # Un punto de la zona de superposición, muy cerca del centro de la primera imagen (x = 1001) y lejos del de la segunda
    x, y = int(round((1001 - marco.este0) / marco.g)), int(round((marco.norte1 - 2000.0) / marco.g))
    monkeypatch.setattr(config, "POTENCIA_PESO", 40.0)
    bgr, _ = Compositor(fotos, marco).todo()
    assert abs(int(bgr[y, x, 0]) - 50) <= 6                      # casi solo la primera: no un promedio de las dos
    monkeypatch.setattr(config, "POTENCIA_PESO", 1.0)
    bgr, _ = Compositor(fotos, marco).todo()
    assert 70 < int(bgr[y, x, 0]) < 150                           # con potencia baja sí se mezclan (el defecto que había en los vuelos reales)
