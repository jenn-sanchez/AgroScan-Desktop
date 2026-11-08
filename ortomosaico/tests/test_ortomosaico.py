import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest
import tifffile

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import main as cli
from src import config, imagen
from src.entrada import leer_entrada
from src.errores import ErrorOrtomosaico
from src.lienzo import Marco, matriz
from src.proceso import GEOTIFF, MINIATURA, VISTA, armar
from src.registro import inicio_del_video_ms, leer_registro
from src.ubicacion import Foto, ubicar
from src.utm import Zona
from tests import vuelo_sintetico as vs


@pytest.fixture(scope="module")
def vuelo(tmp_path_factory):
    return vs.crear(tmp_path_factory.mktemp("vuelo ñandú"))


@pytest.fixture(scope="module")
def armado(vuelo, tmp_path_factory):
    salida = tmp_path_factory.mktemp("salida Árbol")
    avances = []
    resumen = armar(vuelo.entrada, salida, lambda p, e: avances.append((p, e)))
    return resumen, salida, avances


# ---------- coordenadas ----------

def test_utm_coincide_con_pyproj_y_vuelve_al_punto():
    pyproj = pytest.importorskip("pyproj")
    for lat, lon in [(-0.30, -79.488), (-2.2, -80.9), (4.6, -74.1), (40.4, -3.7), (-33.9, 151.2)]:
        z = Zona.de(lat, lon)
        e, n = z.a_utm(lat, lon)
        e2, n2 = pyproj.Transformer.from_crs("EPSG:4326", z.crs, always_xy=True).transform(lon, lat)
        assert abs(e - e2) < 0.01 and abs(n - n2) < 0.01
        la, lo = z.a_geo(e, n)
        assert abs(la - lat) < 1e-7 and abs(lo - lon) < 1e-7      # 1e-7° ≈ 1 cm


def test_la_zona_se_elige_por_longitud_y_hemisferio():
    assert Zona.de(-0.3, -79.488).crs == "EPSG:32717"
    assert Zona.de(4.6, -74.1).crs == "EPSG:32618"


# ---------- geometría ----------

def _foto(yaw_deg):
    return Foto(Path("x.jpg"), 101, 101, 0, 0, math.radians(yaw_deg), 20.0, este=1000.0, norte=2000.0, gsd=0.1)


def _a_lienzo(yaw_deg, u, v):
    marco = Marco(990.0, 2010.0, 0.1, 200, 200)
    x, y = matriz(_foto(yaw_deg), marco) @ np.array([u, v, 1.0])
    return x, y


def test_mirando_al_norte_arriba_es_norte_y_derecha_es_este():
    cx, cy = _a_lienzo(0, 50, 50)
    assert (cx, cy) == pytest.approx((100.0, 100.0))           # el centro de la imagen cae en la posición del dron
    x, y = _a_lienzo(0, 50, 0)                                   # arriba de la imagen
    assert x == pytest.approx(cx) and y < cy                     # → más al norte (fila menor)
    x, y = _a_lienzo(0, 100, 50)                                 # derecha de la imagen
    assert x > cx and y == pytest.approx(cy)                     # → más al este


def test_mirando_al_este_arriba_es_este_y_derecha_es_sur():
    cx, cy = _a_lienzo(90, 50, 50)
    x, y = _a_lienzo(90, 50, 0)
    assert x > cx and y == pytest.approx(cy)
    x, y = _a_lienzo(90, 100, 50)
    assert x == pytest.approx(cx) and y > cy


# ---------- registro y sincronización ----------

def test_el_inicio_del_video_sale_de_las_marcas_y_no_del_telefono():
    marcas = [(10_000 + s * 1000, float(s)) for s in range(3, 20)]
    ms, de = inicio_del_video_ms(marcas, respaldo_ms=999_999)
    assert ms == pytest.approx(10_000) and de == "marcas"


def test_sin_marcas_se_usa_el_inicio_del_telefono_y_sin_nada_se_pide_arreglar():
    assert inicio_del_video_ms([], 123_000.0) == (123_000.0, "inicio-telefono")
    with pytest.raises(ErrorOrtomosaico, match="No se puede saber en qué momento"):
        inicio_del_video_ms([], None)


def test_marcas_que_no_concuerdan_indican_un_video_de_otra_salida():
    marcas = [(0, 5.0), (1000, 50.0), (2000, 9.0), (3000, 90.0)]
    with pytest.raises(ErrorOrtomosaico, match="no concuerdan"):
        inicio_del_video_ms(marcas, None)


def test_un_registro_sin_columnas_o_sin_posiciones_se_explica(tmp_path):
    malo = tmp_path / "r.csv"
    malo.write_text("a,b\n1,2\n")
    with pytest.raises(ErrorOrtomosaico, match="no tiene las columnas"):
        leer_registro(malo)
    vacio = tmp_path / "v.csv"
    vacio.write_text("t_ms,lat,lon,alt_m,yaw_deg,gimbal_pitch_deg\n1,0,0,5,0,-90\n2,,,5,0,-90\n")
    with pytest.raises(ErrorOrtomosaico, match="casi no tiene posiciones"):
        leer_registro(vacio)
    with pytest.raises(ErrorOrtomosaico, match="Falta el registro"):
        leer_registro(tmp_path / "nada.csv")


def test_el_rumbo_se_interpola_sin_dar_la_vuelta_por_el_lado_largo(tmp_path):
    f = tmp_path / "r.csv"
    f.write_text("t_ms,lat,lon,alt_m,yaw_deg,gimbal_pitch_deg,tramo,grabando\n0,1,1,5,350,-90,barrido,1\n1000,1,1,5,10,-90,barrido,1\n")
    r = leer_registro(f)
    yaw = math.degrees(r.en(500)["yaw_rad"]) % 360
    assert yaw == pytest.approx(0.0, abs=1e-6) or yaw == pytest.approx(360.0, abs=1e-6)


def test_se_descartan_las_imagenes_fuera_del_barrido_o_con_la_camara_inclinada(vuelo, tmp_path):
    inclinado = vs.crear(tmp_path / "inclinado", gimbal_inclinado=True)
    with pytest.raises(ErrorOrtomosaico, match="con la cámara inclinada"):
        ubicar(leer_entrada(inclinado.entrada))


def test_sin_imagenes_se_dice_que_hay_que_preprocesar(vuelo, tmp_path):
    j = json.loads(vuelo.entrada.read_text())
    j["salidas"][0]["videos"][0]["imagenes"] = str(tmp_path / "no_existe")
    entrada = tmp_path / "e.json"
    entrada.write_text(json.dumps(j))
    with pytest.raises(ErrorOrtomosaico, match="Preprocesa"):
        armar(entrada, tmp_path / "s", lambda *_: None)


def test_una_orden_ilegible_o_de_otra_version_se_rechaza(tmp_path):
    malo = tmp_path / "e.json"
    malo.write_text("no es json")
    with pytest.raises(ErrorOrtomosaico, match="no se pudo leer"):
        leer_entrada(malo)
    malo.write_text('{"v": 2}')
    with pytest.raises(ErrorOrtomosaico, match="versión"):
        leer_entrada(malo)


# ---------- el mosaico completo ----------

def test_deja_los_tres_archivos_y_avisa_el_progreso_de_menos_a_mas(armado):
    resumen, salida, avances = armado
    for nombre in (GEOTIFF, VISTA, MINIATURA):
        assert (salida / nombre).is_file()
    assert not list(salida.glob("*.part"))
    porcentajes = [p for p, _ in avances]
    assert porcentajes == sorted(porcentajes) and porcentajes[0] == 0 and porcentajes[-1] >= 95


def test_el_resumen_trae_lo_que_la_ficha_necesita(armado, vuelo):
    resumen, _, _ = armado
    assert resumen["imagenes_total"] == vuelo.n_imagenes and resumen["imagenes_usadas"] == vuelo.n_imagenes
    assert resumen["crs"] == "EPSG:32717" and resumen["motor"] == config.MOTOR_DIRECTO
    assert resumen["error_estimado_m"] == config.ERROR_GPS_M
    assert resumen["cobertura_pct"] > 95
    assert resumen["area_ha"] == pytest.approx(vs.ANCHO_M * vs.ALTO_M / 10_000, rel=0.01)   # 0,48 ha
    l = resumen["limites"]
    assert l["oeste"] < l["este"] and l["sur"] < l["norte"]
    # El mosaico cubre el terreno y un poco más (las imágenes de las orillas se salen)
    assert l["oeste"] <= vs.LON0 and l["norte"] >= vs.LAT0
    json.dumps(resumen)    # tiene que poder viajar como JSON


def test_el_geotiff_esta_georreferenciado_donde_esta_el_terreno(armado, vuelo):
    resumen, salida, _ = armado
    with tifffile.TiffFile(salida / GEOTIFF) as tf:
        pagina = tf.pages[0]
        assert pagina.shape[2] == 4 and pagina.dtype == np.uint8
        escala = pagina.tags[33550].value
        punto = pagina.tags[33922].value
        claves = pagina.tags[34735].value
        assert escala[0] == escala[1] == pytest.approx(resumen["gsd_cm_px"] / 100, rel=1e-3)
        assert 3072 in claves and 32717 in claves
        este0, norte1 = punto[3], punto[4]
        # La esquina noroeste del mosaico está cerca de la del terreno (algo más al noroeste: sobran las orillas de las imágenes)
        assert -25 < este0 - vuelo.este0 <= 0.5 and -0.5 <= norte1 - vuelo.norte1 < 25


def test_el_mosaico_se_parece_al_terreno_y_no_a_su_espejo(armado, vuelo):
    resumen, salida, _ = armado
    with tifffile.TiffFile(salida / GEOTIFF) as tf:
        rgba = tf.pages[0].asarray()
        punto = tf.pages[0].tags[33922].value
        g = tf.pages[0].tags[33550].value[0]
    bgr = rgba[..., 2::-1].copy()
    cubierto = rgba[..., 3] > 0
    # Para cada píxel del mosaico, el píxel del terreno de verdad en las mismas coordenadas
    ys, xs = np.mgrid[0:rgba.shape[0], 0:rgba.shape[1]]
    este = punto[3] + (xs + 0.5) * g
    norte = punto[4] - (ys + 0.5) * g
    tx = ((este - vuelo.este0) / vs.GSD_TERRENO).astype(np.float32)
    ty = ((vuelo.norte1 - norte) / vs.GSD_TERRENO).astype(np.float32)
    verdad = cv2.remap(vuelo.terreno, tx, ty, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    dentro = cubierto & (tx > 5) & (ty > 5) & (tx < vuelo.terreno.shape[1] - 5) & (ty < vuelo.terreno.shape[0] - 5)
    assert dentro.sum() > 0.25 * cubierto.sum()

    def gris(a):
        return cv2.GaussianBlur(cv2.cvtColor(a, cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 3)

    a, b = gris(bgr)[dentro], gris(verdad)[dentro]
    assert float(np.corrcoef(a, b)[0, 1]) > 0.97
    assert float(np.abs(a - b).mean()) < 8
    espejo = gris(np.ascontiguousarray(verdad[:, ::-1]))[dentro]
    assert float(np.corrcoef(a, espejo)[0, 1]) < 0.5      # si estuviera espejado, esto también se parecería


def test_la_vista_previa_se_puede_abrir_y_la_miniatura_es_chica(armado):
    _, salida, _ = armado
    vista = imagen.leer(salida / VISTA)
    mini = imagen.leer(salida / MINIATURA)
    assert vista is not None and mini is not None
    assert max(mini.shape[:2]) <= config.LADO_MINIATURA_PX
    # Donde no hay imagen, la vista tiene el gris del visor y no negro: el terreno recorrido no es un rectángulo exacto
    sin_imagen = np.all(np.abs(vista.astype(int) - np.array(config.FONDO_VISTA[::-1])) <= 6, axis=2)
    assert sin_imagen.any() and not np.all(vista[sin_imagen] == 0)


# ---------- la línea de comandos ----------

def test_main_emite_eventos_json_y_el_codigo_de_salida(vuelo, tmp_path, capsys):
    codigo = cli.main(["--entrada", str(vuelo.entrada), "--salida", str(tmp_path / "s"), "--json"])
    lineas = [json.loads(l) for l in capsys.readouterr().out.splitlines()]
    assert codigo == 0
    assert lineas[0]["tipo"] == "progreso" and lineas[-1]["tipo"] == "fin"
    assert all(l["tipo"] in ("progreso", "fin") for l in lineas)
    assert lineas[-1]["resumen"]["imagenes_usadas"] == vuelo.n_imagenes


def test_main_informa_el_problema_del_usuario_como_error_json(tmp_path, capsys):
    malo = tmp_path / "e.json"
    malo.write_text("{}")
    codigo = cli.main(["--entrada", str(malo), "--salida", str(tmp_path / "s"), "--json"])
    evento = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert codigo == 1 and evento["tipo"] == "error" and "orden de armado" in evento["mensaje"]


def test_dos_posiciones_con_el_mismo_archivo_no_se_confunden(tmp_path):
    from src.lienzo import Compositor, hacer_marco
    ruta = tmp_path / "a.jpg"
    imagen.escribir_jpg(ruta, np.full((60, 100, 3), 200, np.uint8), 90)
    fotos = [Foto(ruta, 100, 60, 0, 0, 0.0, 10.0, este=1000.0 + dx, norte=2000.0, gsd=0.1) for dx in (0.0, 30.0)]
    marco = hacer_marco(fotos, "alta")
    _, alfa = Compositor(fotos, marco).todo()
    # Cada foto cubre 10 m de ancho; a 30 m una de otra deja un hueco en medio, no una pila en un solo sitio
    assert alfa[:, 0].any() and alfa[:, -1].any() and not alfa[:, alfa.shape[1] // 2].any()
