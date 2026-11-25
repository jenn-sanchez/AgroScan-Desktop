"""El armado completo: leer la orden → ubicar las imágenes → ajustarlas entre sí → vista previa → alinear con la primera fecha del lote →
GeoTIFF → teselas para la IA → medidas del resultado.

Motor «ajustado»: cada imagen se coloca donde el GPS dice y luego se corrige con los puntos del terreno que comparte con sus vecinas
(`ajuste`); si eso no se puede de forma confiable, queda el mosaico «directo» por GPS y se dice. La precisión absoluta sigue siendo la del GPS
(unos metros); lo que se corrige es la coherencia entre imágenes y, con `referencia`, entre fechas.
"""
import logging
import math
from pathlib import Path

import cv2
import numpy as np

from src import config
from src.ajuste import Ajuste, ajustar
from src.cuadricula import escribir_teselas, hacer_cuadricula
from src.entrada import leer_entrada
from src.lienzo import Compositor, Marco, hacer_marco
from src.piramide import EscritorPiramide
from src.referencia import Alineado, alinear, residuo
from src.salida import escribir_cobertura, escribir_geotiff, escribir_json, escribir_vista
from src.ubicacion import ubicar
from src.utm import Zona

log = logging.getLogger("ortomosaico")

GEOTIFF = "ortomosaico.tif"
VISTA = "vista.jpg"
MINIATURA = "miniatura.jpg"
GEOREF = "georef.json"
COBERTURA = "cobertura.geojson"
TESELAS_CSV = "teselas.csv"


def _limites(marco: Marco, zona: Zona) -> dict:
    """El rectángulo en latitud/longitud que contiene al lienzo (que está en UTM, un poco girado respecto a los meridianos)."""
    lat, lon = zona.a_geo([marco.este0, marco.este1, marco.este1, marco.este0], [marco.norte0, marco.norte0, marco.norte1, marco.norte1])
    return {"oeste": float(np.min(lon)), "sur": float(np.min(lat)), "este": float(np.max(lon)), "norte": float(np.max(lat))}


def _lote_en_utm(lote: list, zona: Zona):
    if len(lote) < 3:
        return None
    este, norte = zona.a_utm([p[0] for p in lote], [p[1] for p in lote])
    return np.column_stack([este, norte])


def _cobertura(alfa: np.ndarray, marco: Marco, lote_utm) -> tuple[float | None, float]:
    """(porcentaje del lote cubierto por el mosaico, área del lote o de lo cubierto en hectáreas)."""
    if lote_utm is None:
        return None, float((alfa > 0).sum()) * marco.g**2 / 10_000
    x, y = marco.a_pixel(lote_utm[:, 0], lote_utm[:, 1])
    mascara = np.zeros(alfa.shape, np.uint8)
    cv2.fillPoly(mascara, [np.round(np.column_stack([x, y]) * 16).astype(np.int32)], 1, lineType=cv2.LINE_8, shift=4)
    en_lote = int(mascara.sum())
    e, n = lote_utm[:, 0], lote_utm[:, 1]
    area_ha = abs(float(np.dot(e, np.roll(n, -1)) - np.dot(n, np.roll(e, -1)))) / 2 / 10_000    # fórmula del topógrafo
    if en_lote == 0:
        return 0.0, area_ha     # el lote queda fuera de lo que cubrieron las imágenes (o del lienzo)
    return float(((alfa > 0) & (mascara > 0)).sum()) / en_lote * 100, area_ha


def _desplazar(fotos: list, marcos: list, este: float, norte: float) -> None:
    """Corre todo el mosaico (las imágenes y los lienzos) tanto al este y al norte: el contenido de los píxeles no cambia, solo dónde están."""
    for f in fotos:
        f.este += este
        f.norte += norte
    for m in marcos:
        m.este0 += este
        m.norte1 += norte


def _lienzos(fotos: list, calidad: str, avisar):
    """El lienzo del mosaico, el de su vista previa y la vista armada: el mosaico entero en pequeño, de donde salen también la comparación con
    la referencia y las medidas de cobertura."""
    marco = hacer_marco(fotos, calidad)
    avisar(config.PROGRESO_FIN_AJUSTAR, "Armando la vista previa")
    lado = max(marco.ancho, marco.alto)
    marco_vista = Marco(marco.este0, marco.norte1, marco.g, marco.ancho, marco.alto)
    if lado > config.LADO_VISTA_PX:
        marco_vista = marco.con_resolucion(marco.g * lado / config.LADO_VISTA_PX)
    bgr, alfa = Compositor(fotos, marco_vista).todo()
    return marco, marco_vista, bgr, alfa


def armar(archivo_entrada: Path, salida: Path, avisar) -> dict:
    """Arma el ortomosaico y devuelve el resumen. [avisar](porcentaje, etapa) informa el avance."""
    salida = Path(salida)
    salida.mkdir(parents=True, exist_ok=True)

    avisar(0, "Leyendo la orden")
    entrada = leer_entrada(archivo_entrada)

    def etapa(inicio, fin, texto):
        avisar(inicio, texto)
        return lambda fraccion: avisar(inicio + (fin - inicio) * min(1.0, fraccion), texto)

    ubicadas = ubicar(entrada, etapa(config.PROGRESO_FIN_LEER, config.PROGRESO_FIN_UBICAR, "Ubicando las imágenes en el terreno"))
    fotos, zona = ubicadas.fotos, ubicadas.zona
    log.info("Imágenes: %d usables de %d; descartadas: %s; reloj del video: %s", len(fotos), ubicadas.total, ubicadas.descartadas, ubicadas.origen_tiempo)
    advertencias = []

    ajuste = Ajuste(False)
    if entrada.ajustar:
        ajuste = ajustar(fotos, etapa(config.PROGRESO_FIN_UBICAR, config.PROGRESO_FIN_AJUSTAR, "Ajustando las imágenes entre sí"))
        log.info("Ajuste: %s", ajuste)
        if ajuste.advertencia:
            advertencias.append(ajuste.advertencia)
        if ajuste.aplicado and abs(ajuste.escala_mediana - 1) > config.AJUSTE_ESCALA_AVISO:
            real = (f"a unos {ajuste.altura_min_m:.0f} m" if round(ajuste.altura_min_m) == round(ajuste.altura_max_m)
                    else f"entre {ajuste.altura_min_m:.0f} y {ajuste.altura_max_m:.0f} m")
            advertencias.append(
                f"El registro dice que el dron voló a {ajuste.altura_registro_m:.0f} m sobre la copa, pero por las imágenes estuvo {real}: "
                "el terreno no está a la altura del punto de despegue (¿se despegó desde una loma?). Se corrigió el tamaño de cada imagen."
            )

    marco, marco_vista, bgr, alfa = _lienzos(fotos, entrada.calidad, avisar)
    nativo = float(np.median([f.gsd for f in fotos]))
    log.info("Lienzo: %d × %d px a %.2f cm/px (imagen original: %.2f cm/px); zona %s", marco.ancho, marco.alto, marco.g * 100, nativo * 100, zona.crs)

    alineado, error_estimado, residuo_m = Alineado(False), config.ERROR_GPS_M, None
    if entrada.referencia is not None:
        avisar(config.PROGRESO_FIN_VISTA, "Alineando con la primera fecha del lote")
        # Varias vueltas: cada una mide lo que queda tras la anterior y lo corrige (la primera quita lo grande; las siguientes, lo fino)
        for vuelta in range(config.ALINEADO_VUELTAS):
            paso = alinear(bgr, alfa, marco_vista, entrada.referencia, zona.crs)
            log.info("Alineado con la referencia, vuelta %d: conjunto (%.2f, %.2f) m, respuesta %.2f, campo local con %d bloques",
                     vuelta + 1, paso.este_m, paso.norte_m, paso.respuesta, paso.bloques)
            if vuelta == 0:
                alineado = paso
            if not paso.aplicado:
                if vuelta == 0 and paso.advertencia:
                    advertencias.append(paso.advertencia)
                break
            if paso.campo is not None:
                # Cada imagen se corre según el campo en su lugar: la deriva del GPS no es la misma en todo el lote
                de, dn = paso.campo([f.este for f in fotos], [f.norte for f in fotos])
                for f, e, n in zip(fotos, de, dn):
                    f.este += float(e)
                    f.norte += float(n)
                marco, marco_vista, bgr, alfa = _lienzos(fotos, entrada.calidad, avisar)
            else:
                _desplazar(fotos, [marco, marco_vista], paso.este_m, paso.norte_m)
                if vuelta == 0:
                    advertencias.append("Solo se pudo alinear el mosaico en conjunto con la fecha de referencia, no por zonas: puede quedar un corrimiento de un par de metros en algunas partes.")
            medido = residuo(bgr, alfa, marco_vista, entrada.referencia, zona.crs)
            if medido is None or medido[0] <= config.ALINEADO_RESIDUO_OBJETIVO_M:
                residuo_m = medido[0] if medido else residuo_m
                break
            residuo_m = medido[0]
        if alineado.aplicado and residuo_m is not None:
            error_estimado = max(config.ALINEADO_ERROR_MIN_M, round(residuo_m, 1))
            if residuo_m > config.ALINEADO_RESIDUO_AVISO_M:
                advertencias.append(f"Quedó un desacuerdo de {residuo_m:.1f} m con la fecha de referencia: compara con cuidado a nivel de planta.")
    escribir_vista(salida / VISTA, salida / MINIATURA, bgr, alfa)
    del bgr

    lote_utm = _lote_en_utm(entrada.lote, zona)
    cobertura, area_ha = _cobertura(alfa, marco_vista, lote_utm)
    escribir_cobertura(salida / COBERTURA, alfa, marco_vista, zona, lote_utm)

    avance_tif = etapa(config.PROGRESO_FIN_VISTA, config.PROGRESO_FIN_GEOTIFF, "Escribiendo el mosaico georreferenciado")
    piramide = EscritorPiramide(salida, marco.ancho, marco.alto, marco.g, marco_vista.g)
    escribir_geotiff(salida / GEOTIFF, Compositor(fotos, marco), zona, avance_tif, piramide.nivel0 if piramide.necesaria else None)
    if piramide.necesaria:
        piramide.terminar()

    # Las teselas salen a la resolución real de las imágenes de este vuelo (ya corregida por el ajuste): ni más gruesas ni inventando detalle
    cuadricula = hacer_cuadricula(lote_utm, zona, float(np.median([f.gsd for f in fotos])))
    teselas = None
    if cuadricula is None:
        advertencias.append("No se conoce el contorno del lote, así que no se cortaron las teselas para la IA.")
    else:
        teselas = escribir_teselas(salida, fotos, cuadricula, etapa(config.PROGRESO_FIN_GEOTIFF, config.PROGRESO_FIN_TESELAS, "Cortando las teselas para la IA"))
        if teselas["escritas"] == 0:
            advertencias.append("Ninguna celda del lote quedó con imágenes suficientes para cortar teselas.")
    escribir_json(salida / GEOREF, {
        "v": 1, "crs": zona.crs, "este0": marco.este0, "norte1": marco.norte1, "gsd_m": marco.g, "ancho_px": marco.ancho, "alto_px": marco.alto,
        "vista": {"gsd_m": marco_vista.g, "ancho_px": marco_vista.ancho, "alto_px": marco_vista.alto},
        "cuadricula": cuadricula.a_json() if cuadricula else None,
    })
    avisar(config.PROGRESO_FIN_TESELAS, "Terminando")

    if ubicadas.origen_tiempo != "marcas":
        advertencias.append("La salida no tenía marcas del video: se usó la hora de inicio anotada por el teléfono, que es menos precisa.")
    perdidas = ubicadas.total - len(fotos)
    if perdidas > ubicadas.total * 0.3:
        motivos = ", ".join(f"{n} {m}" for m, n in ubicadas.descartadas.items())
        advertencias.append(f"Se descartó el {perdidas * 100 // ubicadas.total} % de las imágenes ({motivos}).")
    if cobertura is not None and cobertura < 80:
        advertencias.append(f"El mosaico cubre solo el {cobertura:.0f} % del lote.")
    if marco.g > nativo * config.FACTOR_RESOLUCION[entrada.calidad] * 1.01:
        advertencias.append("El lote es muy grande: se bajó la resolución para que el mosaico entre en memoria y disco.")

    return {
        "imagenes_usadas": len(fotos),
        "imagenes_total": ubicadas.total,
        "cobertura_pct": None if cobertura is None else round(cobertura, 1),
        "area_ha": round(area_ha, 3),
        "gsd_cm_px": round(marco.g * 100, 2),
        "crs": zona.crs,
        "limites": _limites(marco, zona),
        "error_estimado_m": error_estimado,
        "motor": config.MOTOR_AJUSTADO if ajuste.aplicado else config.MOTOR_DIRECTO,
        "ancho_px": marco.ancho,
        "alto_px": marco.alto,
        "descartadas": ubicadas.descartadas,
        "ajuste": {
            "aplicado": ajuste.aplicado, "pares": ajuste.pares, "fotos_con_pares": ajuste.fotos_con_pares,
            "rms_antes_m": round(ajuste.rms_antes_m, 3), "rms_despues_m": round(ajuste.rms_despues_m, 3),
            "desplazamiento_mediano_m": round(ajuste.desplazamiento_mediano_m, 3), "giro_maximo_grados": round(ajuste.giro_maximo_grados, 2),
            "escala_mediana": round(ajuste.escala_mediana, 3), "altura_registro_m": round(ajuste.altura_registro_m, 1),
            "altura_min_m": round(ajuste.altura_min_m, 1), "altura_max_m": round(ajuste.altura_max_m, 1),
        },
        "alineado_con": {
            "vuelo": entrada.referencia.vuelo, "este_m": round(alineado.este_m, 3), "norte_m": round(alineado.norte_m, 3),
            "respuesta": round(alineado.respuesta, 3), "bloques": alineado.bloques,
            "desacuerdo_m": None if residuo_m is None else round(residuo_m, 3),
        } if alineado.aplicado and entrada.referencia else None,
        "teselas": teselas,
        "advertencias": advertencias,
    }
