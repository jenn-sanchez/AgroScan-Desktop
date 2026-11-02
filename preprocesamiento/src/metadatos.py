from datetime import datetime, timedelta
from PIL import Image
from src import config

def _texto_windows(texto):
    return texto.encode("utf-16-le") + b"\x00\x00"

def calcular_fecha_inicio(ruta_video, duracion_segundos):
    fecha_fin = datetime.fromtimestamp(ruta_video.stat().st_mtime)
    return fecha_fin - timedelta(seconds=duracion_segundos)

def agregar_posicion_en_pasada(elegidos):
    totales = {}
    for dato in elegidos:
        pasada = dato["pasada"]
        totales[pasada] = totales.get(pasada, 0) + 1

    contadores = {}
    for dato in elegidos:
        pasada = dato["pasada"]
        contadores[pasada] = contadores.get(pasada, 0 ) + 1

    contadores = {}
    for dato in elegidos:
        pasada = dato["pasada"]
        contadores[pasada] = contadores.get(pasada, 0) + 1
        dato["numero_en_pasada"] = contadores[pasada]
        dato["total_en_pasada"] = totales[pasada]

def crear_textos(dato, nombre_video, fecha_inicio, fps):
    segundo = dato["frame"] / fps
    fecha_frame = fecha_inicio + timedelta(seconds=segundo)

    if dato["sentido"] == "intervalo fijo":
        texto_solape = "no medido (selección por intervalo fijo)"
    elif dato["solape"] is None:
        texto_solape = "no aplica (primera imagen de la pasada)"
    else:
        texto_solape = f"{dato['solape']:.1f} %"

    titulo = (
        f"Pasada {dato['pasada']} - "
        f"Imagen {dato['numero_en_pasada']} de {dato['total_en_pasada']}"
    )
    comentarios = (
        f"Video: {nombre_video} | Frame: {dato['frame']} | "
        f"Segundo del video: {segundo:.1f} s | "
        f"Pasada: {dato['pasada']} ({dato['sentido']}) | "
        f"Solape con la imagen anterior: {texto_solape} | "
        "Fecha y hora estimadas a partir del archivo de video"
    )

    return {
        "titulo": titulo,
        "comentarios": comentarios,
        "fecha": fecha_frame.strftime("%Y:%m:%d %H:%M:%S"),
    }


def crear_exif(textos):
    exif = Image.Exif()
    exif[0x0131] = config.NOMBRE_PROGRAMA                  
    exif[0x9C9B] = _texto_windows(textos["titulo"])        
    exif[0x9C9C] = _texto_windows(textos["comentarios"])   
    exif[0x9C9F] = _texto_windows(config.ASUNTO_IMAGENES)  

    bloque_exif = exif.get_ifd(0x8769)
    bloque_exif[0x9003] = textos["fecha"]                     
    return exif

def crear_nombre_archivo(dato, nombre_base):
    return(
        f"{nombre_base}_P{dato['pasada']}_I{dato['numero_en_pasada']:03d}"
        f"_F{dato['frame']:06d}.jpg"
    )

def agregar_calidad(textos, aplicados, diagnostico_final):
    if len(aplicados) == 0:
        ajustes = "ninguno (imagen sin modificar)"
    else:
        ajustes = ", ".join(aplicados)

    if len(diagnostico_final["ajustes"]) == 0:
        pendientes = "ninguno"
    else:
        pendientes = ", ".join(diagnostico_final["ajustes"])

    if len(diagnostico_final["advertencias"]) == 0:
        advertencias = "ninguna"
    else:
        advertencias = ", ".join(diagnostico_final["advertencias"])

    nuevos = dict(textos)
    nuevos["comentarios"] = (
        f"{textos['comentarios']} | Ajustes aplicados: {ajustes} | "
        f"Problemas pendientes: {pendientes} | Advertencias: {advertencias}" 
    )
    return nuevos