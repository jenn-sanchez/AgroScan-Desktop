"""El registro da la altura sobre el punto de despegue, no sobre el suelo: con relieve (un vuelo desde una loma, un lote en ladera) la altura real
sobre cada imagen es otra, y eso cambia el tamaño que cada imagen tiene sobre el terreno. Estas pruebas simulan eso (lo que pasó con un vuelo real
desde una loma: mosaico borroso y con partes repetidas)."""
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src import config
from src.ajuste import ajustar
from src.entrada import leer_entrada
from src.proceso import GEOTIFF, armar
from src.ubicacion import ubicar
from tests import vuelo_sintetico as vs


def _estimado_contra_real(fotos, vuelo):
    """Para cada imagen, el tamaño de píxel estimado dividido por el real."""
    return np.array([f.gsd / vuelo.verdad[f.ruta.name][3] for f in fotos])


@pytest.mark.parametrize("suelo_m, esperado", [(-20.0, 1.8), (-10.0, 1.4), (8.0, 0.68)])
def test_el_ajuste_encuentra_la_altura_real_sobre_el_suelo(tmp_path, suelo_m, esperado):
    v = vs.crear(tmp_path / "v", suelo_m=suelo_m, gps_ruido_m=0.5, semilla=3)
    u = ubicar(leer_entrada(v.entrada))
    nominal = _estimado_contra_real(u.fotos, v)
    assert np.median(nominal) == pytest.approx(1 / esperado, rel=0.02)      # sin ajustar, las imágenes tienen el tamaño equivocado
    aj = ajustar(u.fotos)
    assert aj.aplicado and aj.advertencia is None
    assert aj.escala_mediana == pytest.approx(esperado, rel=0.08)
    # Y la altura que dice haber encontrado es la real (25 m del registro × la escala)
    assert aj.altura_registro_m == pytest.approx(vs.ALTURA)
    assert aj.altura_min_m == pytest.approx(vs.ALTURA * esperado, rel=0.1)
    despues = _estimado_contra_real(u.fotos, v)
    assert np.median(np.abs(despues - 1)) < 0.08


def test_con_relieve_variable_cada_imagen_recibe_su_propia_escala(tmp_path):
    # El suelo baja hacia el este: la altura real sobre el suelo va de ~35 m a ~47 m en el mismo vuelo
    v = vs.crear(tmp_path / "v", suelo_m=-10.0, pendiente=-0.15, gps_ruido_m=0.5, semilla=4)
    u = ubicar(leer_entrada(v.entrada))
    aj = ajustar(u.fotos)
    assert aj.aplicado
    despues = _estimado_contra_real(u.fotos, v)
    assert np.median(np.abs(despues - 1)) < 0.08 and np.percentile(np.abs(despues - 1), 90) < 0.2
    assert aj.altura_max_m - aj.altura_min_m > 5                              # vio que la altura cambia a lo largo del vuelo


def test_el_mosaico_con_relieve_sale_nitido_y_sin_duplicados(tmp_path):
    """El caso real: sin tener en cuenta el relieve las imágenes se mezclan con el tamaño equivocado y el mosaico no se parece al terreno."""
    kw = dict(suelo_m=-20.0, gps_ruido_m=0.5, semilla=3)
    mal = vs.crear(tmp_path / "mal", ajustar=False, **kw)
    bien = vs.crear(tmp_path / "bien", **kw)
    armar(mal.entrada, tmp_path / "s_mal", lambda *_: None)
    r = armar(bien.entrada, tmp_path / "s_bien", lambda *_: None)
    c_mal, mae_mal = vs.calidad(mal, tmp_path / "s_mal" / GEOTIFF, quitar_desplazamiento_global=True)
    c_bien, mae_bien = vs.calidad(bien, tmp_path / "s_bien" / GEOTIFF, quitar_desplazamiento_global=True)
    assert c_bien > 0.85 and mae_bien < 0.6 * mae_mal
    assert c_bien > c_mal + 0.3
    assert r["motor"] == config.MOTOR_AJUSTADO
    # Se le dice al usuario que el terreno no es plano respecto del despegue y cuál es la altura real
    assert any("altura" in a and "despegue" in a for a in r["advertencias"])
    assert r["ajuste"]["escala_mediana"] == pytest.approx(1.8, rel=0.1)
