"""Ajuste entre imágenes, alineado entre fechas, teselas y georreferencia guardada (parte 3)."""
import csv
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest
import tifffile

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from src import config, imagen
from src.ajuste import _vecinas, ajustar
from src.cuadricula import COLUMNAS_CSV
from src.entrada import leer_entrada
from src.proceso import COBERTURA, GEOREF, GEOTIFF, armar
from src.ubicacion import ubicar
from src.utm import Zona
from tests import vuelo_sintetico as vs


def _errores(fotos, vuelo):
    """(error de posición absoluto, error de posición entre vecinas) en metros, contra donde de verdad estaba el dron."""
    t = np.array([vuelo.verdad[f.ruta.name][:2] for f in fotos])
    e = np.array([(f.este, f.norte) for f in fotos]) - t
    rel = np.array([e[i] - e[j] for i, j in _vecinas(fotos)])
    return float(np.sqrt((e**2).sum(1).mean())), float(np.sqrt((rel**2).sum(1).mean()))


# ---------- ajuste entre imágenes ----------

def test_el_ajuste_deja_coherentes_las_imagenes_vecinas_aunque_el_gps_ande_mal(tmp_path):
    v = vs.crear(tmp_path / "v", gps_ruido_m=2.0, yaw_error_grados=2.0)
    u = ubicar(leer_entrada(v.entrada))
    _, rel_antes = _errores(u.fotos, v)
    aj = ajustar(u.fotos)
    abs_despues, rel_despues = _errores(u.fotos, v)
    assert aj.aplicado and aj.advertencia is None
    assert rel_despues < 0.5 * rel_antes                    # las vecinas ya no se contradicen
    assert abs_despues < 3.5                                # y el bloque no se va lejos del GPS (que es el ancla)
    assert aj.rms_despues_m < 0.3 * aj.rms_antes_m


def test_con_el_gps_acertado_el_ajuste_no_toca_nada(tmp_path):
    v = vs.crear(tmp_path / "v")
    u = ubicar(leer_entrada(v.entrada))
    antes = [(f.este, f.norte, f.yaw_rad) for f in u.fotos]
    aj = ajustar(u.fotos)
    assert not aj.aplicado and aj.advertencia is None       # no es un problema: ya coincidían
    assert antes == [(f.este, f.norte, f.yaw_rad) for f in u.fotos]


def test_un_error_de_brujula_no_se_explica_moviendo_las_imagenes(tmp_path):
    v = vs.crear(tmp_path / "v", yaw_error_grados=3.0)       # el GPS es perfecto: solo falla el rumbo
    u = ubicar(leer_entrada(v.entrada))
    ajustar(u.fotos)
    abs_despues, rel_despues = _errores(u.fotos, v)
    assert abs_despues < 0.8 and rel_despues < 0.4           # sin la suavidad entre imágenes seguidas esto daba 1.1 y 0.7


def test_un_terreno_liso_no_se_ajusta_y_se_dice_pero_el_mosaico_sale(tmp_path):
    v = vs.crear(tmp_path / "v", liso=True, gps_ruido_m=2.0)
    resumen = armar(v.entrada, tmp_path / "s", lambda *_: None)
    assert resumen["ajuste"]["aplicado"] is False
    assert any("puntos en común" in a for a in resumen["advertencias"])
    assert resumen["motor"] == config.MOTOR_DIRECTO
    assert (tmp_path / "s" / GEOTIFF).is_file()


def test_el_ajuste_se_anota_en_el_motor_y_en_el_resumen(tmp_path):
    v = vs.crear(tmp_path / "v", gps_ruido_m=3.0, yaw_error_grados=4.0, semilla=5)
    r = armar(v.entrada, tmp_path / "s", lambda *_: None)
    assert r["motor"] == config.MOTOR_AJUSTADO and r["ajuste"]["aplicado"]
    assert r["ajuste"]["rms_despues_m"] < r["ajuste"]["rms_antes_m"]


def test_con_ajustar_en_falso_no_se_ajusta(tmp_path):
    v = vs.crear(tmp_path / "v", gps_ruido_m=2.0, ajustar=False)
    r = armar(v.entrada, tmp_path / "s", lambda *_: None)
    assert r["ajuste"]["aplicado"] is False and r["ajuste"]["pares"] == 0 and r["motor"] == config.MOTOR_DIRECTO


# ---------- alinear una fecha con la primera del lote ----------

def _comparar(carpeta_a: Path, carpeta_b: Path) -> float:
    """Correlación entre dos mosaicos en el terreno que tienen en común, según su georreferencia."""
    def leer(c):
        with tifffile.TiffFile(c / GEOTIFF) as tf:
            p = tf.pages[0]
            return p.asarray(), p.tags[33922].value, p.tags[33550].value[0]
    a, pa, ga = leer(carpeta_a)
    b, pb, gb = leer(carpeta_b)
    ys, xs = np.mgrid[0:a.shape[0], 0:a.shape[1]]
    este, norte = pa[3] + (xs + 0.5) * ga, pa[4] - (ys + 0.5) * ga
    bm = cv2.remap(b, ((este - pb[3]) / gb - 0.5).astype(np.float32), ((pb[4] - norte) / gb - 0.5).astype(np.float32), cv2.INTER_LINEAR,
                   borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0, 0))
    ok = (a[..., 3] > 0) & (bm[..., 3] > 0)
    gris = lambda x: cv2.GaussianBlur(cv2.cvtColor(np.ascontiguousarray(x[..., 2::-1]), cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 2)
    return float(np.corrcoef(gris(a)[ok], gris(bm)[ok])[0, 1])


@pytest.fixture(scope="module")
def dos_fechas(tmp_path_factory):
    d = tmp_path_factory.mktemp("fechas ñ")
    a = vs.crear(d / "A", gps_ruido_m=0.7, semilla=1)
    ra = armar(a.entrada, d / "sA", lambda *_: None)
    kw = dict(gps_sesgo=(5.0, -4.0), gps_ruido_m=0.8, semilla=9)
    b0 = vs.crear(d / "B0", **kw)
    rb0 = armar(b0.entrada, d / "sB0", lambda *_: None)
    b1 = vs.crear(d / "B1", referencia=d / "sA", **kw)
    rb1 = armar(b1.entrada, d / "sB1", lambda *_: None)
    return d, ra, rb0, rb1


def test_una_fecha_nueva_se_alinea_con_la_primera_y_se_puede_comparar(dos_fechas):
    d, ra, rb0, rb1 = dos_fechas
    sin, con = _comparar(d / "sA", d / "sB0"), _comparar(d / "sA", d / "sB1")
    assert con > 0.95 and con > sin + 0.1
    assert rb1["alineado_con"] is not None and rb0["alineado_con"] is None
    # El error estimado sale de medir lo que queda de desacuerdo con la referencia, no de una constante
    assert config.ALINEADO_ERROR_MIN_M <= rb1["error_estimado_m"] < config.ERROR_GPS_M and rb0["error_estimado_m"] == config.ERROR_GPS_M
    assert rb1["alineado_con"]["desacuerdo_m"] < 1.0 and rb1["alineado_con"]["bloques"] >= 4
    assert math.hypot(rb1["alineado_con"]["este_m"], rb1["alineado_con"]["norte_m"]) > 2      # corrigió un corrimiento de verdad


def test_sin_la_georreferencia_de_la_referencia_se_avisa_y_se_arma_igual(tmp_path):
    vieja = tmp_path / "vieja"
    vieja.mkdir()
    v = vs.crear(tmp_path / "v", referencia=vieja)
    r = armar(v.entrada, tmp_path / "s", lambda *_: None)
    assert r["alineado_con"] is None and r["error_estimado_m"] == config.ERROR_GPS_M
    assert any("referencia" in a for a in r["advertencias"])


# ---------- teselas para la IA, georreferencia y cobertura ----------

@pytest.fixture(scope="module")
def armado(tmp_path_factory):
    d = tmp_path_factory.mktemp("armado ñ")
    v = vs.crear(d / "v")
    return armar(v.entrada, d / "s", lambda *_: None), d / "s", v


def _correlacion_por_tesela(carpeta_a: Path, carpeta_b: Path) -> list:
    """Correlación de cada tesela que las dos fechas tienen en común (donde las dos tienen imagen)."""
    fa = {f["id"]: f for f in csv.DictReader(open(carpeta_a / "teselas.csv", encoding="utf-8"))}
    fb = {f["id"]: f for f in csv.DictReader(open(carpeta_b / "teselas.csv", encoding="utf-8"))}
    g = lambda x: cv2.GaussianBlur(cv2.cvtColor(x, cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 2)
    salida = []
    for i in sorted(set(fa) & set(fb)):
        ta, tb = imagen.leer(carpeta_a / fa[i]["archivo"]), imagen.leer(carpeta_b / fb[i]["archivo"])
        assert ta.shape[0] == ta.shape[1] and tb.shape[0] == tb.shape[1]          # cuadradas; pero con otros píxeles si el vuelo fue a otra altura
        tb = cv2.resize(tb, (ta.shape[1], ta.shape[0]), interpolation=cv2.INTER_AREA)
        ok = (ta.max(axis=2) > 0) & (tb.max(axis=2) > 0)
        if ok.sum() > 20000:
            salida.append(float(np.corrcoef(g(ta)[ok], g(tb)[ok])[0, 1]))
    return salida


def test_las_teselas_son_las_mismas_celdas_en_todas_las_fechas(dos_fechas):
    d, ra, rb0, rb1 = dos_fechas
    ga, gb = json.loads((d / "sA" / GEOREF).read_text()), json.loads((d / "sB1" / GEOREF).read_text())
    fija = lambda c: {k: c[k] for k in ("crs", "este0", "norte1", "lado_m", "columnas", "filas")}
    assert fija(ga["cuadricula"]) == fija(gb["cuadricula"])                      # el mismo origen, lado en metros y rejilla; los píxeles pueden diferir
    ids = lambda c: {f["id"] for f in csv.DictReader(open(d / c / "teselas.csv", encoding="utf-8"))}
    assert ids("sA") and len(ids("sA") & ids("sB1")) >= 0.8 * len(ids("sA"))     # casi las mismas celdas (los bordes pueden diferir)
    con, sin = _correlacion_por_tesela(d / "sA", d / "sB1"), _correlacion_por_tesela(d / "sA", d / "sB0")
    # La misma tesela de dos fechas se parece, y mucho más que sin alinear. Con este terreno de prueba (detalles de 30 cm a 4 cm por píxel) basta
    # un desacuerdo de unos decímetros para bajar la correlación, así que el umbral es moderado: lo que se verifica es la mejora.
    assert len(con) >= 6 and float(np.median(con)) > 0.5 and float(np.median(con)) > float(np.median(sin)) + 0.3


def test_el_indice_de_teselas_dice_donde_esta_cada_una(armado):
    resumen, salida, _ = armado
    filas = list(csv.DictReader(open(salida / "teselas.csv", encoding="utf-8")))
    assert resumen["teselas"]["escritas"] == len(filas) > 0
    assert tuple(filas[0].keys()) == COLUMNAS_CSV
    georef = json.loads((salida / GEOREF).read_text())
    cu = georef["cuadricula"]
    for f in filas:
        assert (salida / f["archivo"]).is_file()
        assert 5 <= float(f["en_lote_pct"]) <= 100 and float(f["cobertura_pct"]) >= config.TESELA_MIN_COBERTURA_PCT
        # la posición de la celda sale de su fila y columna
        assert float(f["este_min"]) == pytest.approx(cu["este0"] + int(f["columna"]) * cu["lado_m"], abs=1e-3)
        assert float(f["norte_max"]) == pytest.approx(cu["norte1"] - int(f["fila"]) * cu["lado_m"], abs=1e-3)
        assert float(f["oeste"]) < float(f["lon_centro"]) < float(f["este"]) and float(f["sur"]) < float(f["lat_centro"]) < float(f["norte"])
    assert cu["crs"] == Zona.de(vs.LAT0, vs.LON0).crs == georef["crs"]


def test_una_tesela_es_el_terreno_de_su_celda(armado):
    """Lo que hay dentro de cada tesela es lo que el terreno tiene en esa celda (no una celda corrida ni girada)."""
    _, salida, v = armado
    cu = json.loads((salida / GEOREF).read_text())["cuadricula"]
    fila = next(f for f in csv.DictReader(open(salida / "teselas.csv", encoding="utf-8")) if float(f["cobertura_pct"]) > 90)
    tesela = imagen.leer(salida / fila["archivo"])
    n = cu["lado_px"]
    ys, xs = np.mgrid[0:n, 0:n]
    este = float(fila["este_min"]) + (xs + 0.5) * cu["gsd_m"]
    norte = float(fila["norte_max"]) - (ys + 0.5) * cu["gsd_m"]
    verdad = cv2.remap(v.terreno, ((este - v.este0) / vs.GSD_TERRENO - 0.5).astype(np.float32), ((v.norte1 - norte) / vs.GSD_TERRENO - 0.5).astype(np.float32),
                       cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    dentro = (tesela.max(axis=2) > 0) & (verdad.max(axis=2) > 0)
    g = lambda x: cv2.GaussianBlur(cv2.cvtColor(x, cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 2)
    assert float(np.corrcoef(g(tesela)[dentro], g(verdad)[dentro])[0, 1]) > 0.95


def test_la_georreferencia_guardada_coincide_con_la_del_geotiff(armado):
    _, salida, _ = armado
    georef = json.loads((salida / GEOREF).read_text())
    with tifffile.TiffFile(salida / GEOTIFF) as tf:
        p = tf.pages[0]
        assert georef["este0"] == pytest.approx(p.tags[33922].value[3]) and georef["norte1"] == pytest.approx(p.tags[33922].value[4])
        assert georef["gsd_m"] == pytest.approx(p.tags[33550].value[0])
        assert (georef["alto_px"], georef["ancho_px"]) == p.shape[:2]
    assert georef["vista"]["ancho_px"] <= config.LADO_VISTA_PX


def test_la_cobertura_geojson_trae_el_contorno_del_mosaico_y_el_del_lote(armado):
    _, salida, _ = armado
    gj = json.loads((salida / COBERTURA).read_text())
    assert gj["type"] == "FeatureCollection"
    tipos = {f["properties"]["tipo"]: f["geometry"] for f in gj["features"]}
    assert tipos["cobertura"]["type"] == "MultiPolygon" and tipos["cobertura"]["coordinates"]
    lote = tipos["lote"]["coordinates"][0]
    assert lote[0] == lote[-1] and len(lote) == 5                                 # cerrado, con sus cuatro esquinas
    assert lote[0] == pytest.approx([vs.LON0, vs.LAT0], abs=1e-7)                 # [longitud, latitud]


def test_sin_el_contorno_del_lote_no_hay_teselas_pero_si_mosaico(tmp_path):
    v = vs.crear(tmp_path / "v", con_lote=False)
    r = armar(v.entrada, tmp_path / "s", lambda *_: None)
    assert r["teselas"] is None and r["cobertura_pct"] is None
    assert any("contorno del lote" in a for a in r["advertencias"])
    assert not (tmp_path / "s" / "teselas.csv").exists() and (tmp_path / "s" / GEOTIFF).is_file()
    assert json.loads((tmp_path / "s" / GEOREF).read_text())["cuadricula"] is None


# ---------- las teselas conservan la resolución de las imágenes ----------

def test_la_tesela_sale_de_una_sola_imagen_a_la_resolucion_real_del_vuelo(armado):
    resumen, salida, v = armado
    cu = json.loads((salida / GEOREF).read_text())["cuadricula"]
    filas = list(csv.DictReader(open(salida / "teselas.csv", encoding="utf-8")))
    assert tuple(filas[0].keys()) == COLUMNAS_CSV
    # El lado es fijo en metros y los píxeles salen de la resolución real de las imágenes (no de un valor elegido de antemano)
    assert cu["lado_m"] == config.TESELA_LADO_M
    gsd_real = float(np.median([g[3] for g in v.verdad.values()]))
    assert cu["gsd_m"] == pytest.approx(gsd_real, rel=0.06)
    assert cu["lado_px"] == round(cu["lado_m"] / cu["gsd_m"])
    # Casi todas salen de una imagen (sin mezclar) y dicen de cuál
    de_una = [f for f in filas if f["fuente"] == "imagen"]
    assert len(de_una) >= 0.7 * len(filas) and resumen["teselas"]["de_una_imagen"] == len(de_una)
    assert all(f["imagen"].endswith(".jpg") and float(f["cobertura_pct"]) == 100.0 for f in de_una)
    assert all(float(f["gsd_cm_px"]) == pytest.approx(cu["gsd_m"] * 100, abs=0.01) for f in filas)


def test_una_tesela_de_una_imagen_conserva_el_detalle_que_el_mosaico_mezclado_pierde(tmp_path):
    """Con las imágenes algo desalineadas entre sí (como en un vuelo real), mezclarlas empasta el detalle; cortar de una sola no."""
    v = vs.crear(tmp_path / "v", gps_ruido_m=0.8, yaw_error_grados=1.5, semilla=3, ajustar=False)
    armar(v.entrada, tmp_path / "s", lambda *_: None)
    cu = json.loads((tmp_path / "s" / GEOREF).read_text())["cuadricula"]
    filas = [f for f in csv.DictReader(open(tmp_path / "s" / "teselas.csv", encoding="utf-8")) if f["fuente"] == "imagen" and float(f["en_lote_pct"]) > 95]
    assert filas
    n = cu["lado_px"]
    ys, xs = np.mgrid[0:n, 0:n]
    nitidez = lambda a: float(cv2.Laplacian(cv2.cvtColor(a, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var())
    mejora = []
    for f in filas[:6]:
        tesela = imagen.leer(tmp_path / "s" / f["archivo"])
        # La misma celda, pero mezclando todas las imágenes como lo hace el mosaico
        from src.lienzo import Compositor
        from src.cuadricula import Cuadricula
        from src.ubicacion import ubicar
        from src.entrada import leer_entrada
        fotos = ubicar(leer_entrada(v.entrada)).fotos
        cuad = Cuadricula(Zona.de(vs.LAT0, vs.LON0), cu["este0"], cu["norte1"], cu["lado_m"], cu["lado_px"], cu["columnas"], cu["filas"], np.zeros((3, 2)))
        mezclada, _ = Compositor(fotos, cuad.marco()).region(int(f["columna"]) * n, int(f["fila"]) * n, n, n)
        mejora.append(nitidez(tesela) / max(nitidez(mezclada), 1e-6))
    assert float(np.median(mejora)) > 1.0
