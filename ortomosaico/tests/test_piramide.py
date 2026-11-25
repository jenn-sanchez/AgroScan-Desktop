import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src import config, imagen
from src.piramide import EscritorPiramide


def _imagen(ancho=1300, alto=900):
    rng = np.random.default_rng(3)
    base = cv2.GaussianBlur(rng.random((alto, ancho)).astype(np.float32), (0, 0), 2)
    gris = (255 * (base - base.min()) / (base.max() - base.min())).astype(np.uint8)
    return np.dstack([gris, gris, gris])      # gris: el JPG submuestrea el color y el ruido de color distinto por canal no sobrevive


def _alimentar(e: EscritorPiramide, img: np.ndarray):
    n = e.lado
    alfa = np.full(img.shape[:2], 255, np.uint8)
    for fila in range(math.ceil(img.shape[0] / n)):
        for col in range(math.ceil(img.shape[1] / n)):
            e.nivel0(fila, col, img[fila * n:(fila + 1) * n, col * n:(col + 1) * n], alfa[fila * n:(fila + 1) * n, col * n:(col + 1) * n])


def test_una_vista_que_ya_esta_a_resolucion_completa_no_necesita_piramide(tmp_path):
    assert not EscritorPiramide(tmp_path, 1300, 900, gsd_m=0.02, gsd_vista_m=0.02).necesaria
    assert not EscritorPiramide(tmp_path, 1300, 900, gsd_m=0.02, gsd_vista_m=0.021).necesaria      # casi igual: no aporta


def test_solo_se_guardan_los_niveles_mas_finos_que_la_vista(tmp_path):
    assert EscritorPiramide(tmp_path, 1300, 900, 0.02, 0.06).niveles == 2          # 2 cm y 4 cm; 8 cm ya lo cubre la vista de 6 cm
    assert EscritorPiramide(tmp_path, 1300, 900, 0.02, 0.20).niveles == 4          # 2, 4, 8 y 16 cm
    assert EscritorPiramide(tmp_path, 1300, 900, 0.02, 100.0).niveles == config.PIRAMIDE_MAX_NIVELES


def test_los_niveles_son_la_mitad_del_anterior_y_los_bordes_van_recortados(tmp_path):
    img = _imagen()
    e = EscritorPiramide(tmp_path, img.shape[1], img.shape[0], 0.02, 0.20)
    _alimentar(e, img)
    meta = e.terminar()
    assert meta == json.loads((tmp_path / "piramide.json").read_text()) and meta["niveles"] == 4 and meta["ancho_px"] == 1300
    n = e.lado
    for nivel in range(meta["niveles"]):
        w, h = math.ceil(1300 / 2**nivel), math.ceil(900 / 2**nivel)
        archivos = {p.name for p in (tmp_path / "piramide" / str(nivel)).glob("*.jpg")}
        assert archivos == {f"{f}_{c}.jpg" for f in range(math.ceil(h / n)) for c in range(math.ceil(w / n))}
        # La tesela de la esquina inferior derecha tiene el tamaño que queda, no el de relleno
        ultima = imagen.leer(tmp_path / "piramide" / str(nivel) / f"{math.ceil(h / n) - 1}_{math.ceil(w / n) - 1}.jpg")
        assert ultima.shape[:2] == (h - (math.ceil(h / n) - 1) * n, w - (math.ceil(w / n) - 1) * n)


def test_cada_nivel_se_parece_al_anterior_reducido(tmp_path):
    img = _imagen()
    e = EscritorPiramide(tmp_path, img.shape[1], img.shape[0], 0.02, 0.20)
    _alimentar(e, img)
    e.terminar()
    n = e.lado
    esperado = cv2.resize(img, (650, 450), interpolation=cv2.INTER_AREA)
    # el nivel 1 tiene 2 × 1 teselas (650 × 450): se arma entero y se compara con la imagen original reducida a la mitad
    nivel1 = np.hstack([imagen.leer(tmp_path / "piramide" / "1" / "0_0.jpg"), imagen.leer(tmp_path / "piramide" / "1" / "0_1.jpg")])
    assert nivel1.shape[:2] == esperado.shape[:2]
    assert float(np.abs(nivel1.astype(int) - esperado.astype(int)).mean()) < 4      # el JPG de por medio le añade un par de niveles de gris


def test_donde_no_hay_imagen_la_tesela_lleva_el_gris_del_visor(tmp_path):
    e = EscritorPiramide(tmp_path, 600, 400, 0.02, 0.20)
    img = _imagen(600, 400)
    alfa = np.zeros((400, 512), np.uint8)
    e.nivel0(0, 0, img[:, :512], alfa)
    tesela = imagen.leer(tmp_path / "piramide" / "0" / "0_0.jpg")
    assert np.all(np.abs(tesela.astype(int) - np.array(config.FONDO_VISTA[::-1])) <= 6)
