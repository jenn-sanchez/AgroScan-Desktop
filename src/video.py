import cv2

from src import config
from PIL import Image

def buscar_videos():
    videos = []
    for archivo in config.CARPETA_VIDEOS.iterdir():
        if archivo.suffix.lower() in ".mp4":
            videos.append(archivo)
    return videos

def obtener_info_video(ruta_video):
    captura = cv2.VideoCapture(str(ruta_video))

    if not captura.isOpened():
        raise ValueError(f"No se pudo abrir el video: {ruta_video}")

    fps = captura.get(cv2.CAP_PROP_FPS)
    total_frames = int(captura.get(cv2.CAP_PROP_FRAME_COUNT))
    ancho = int(captura.get(cv2.CAP_PROP_FRAME_WIDTH))
    alto = int(captura.get(cv2.CAP_PROP_FRAME_HEIGHT))

    captura.release()

    duracion_segundos = total_frames / fps 
    info = {
        "nombre": ruta_video.name,
        "fps": fps,
        "total_frames": total_frames,
        "ancho": ancho,
        "alto": alto,
        "duracion_segundos": duracion_segundos,
    }
    return info

def calcular_salto_frames(fps, intervalo_segundos):
    salto = round(fps * intervalo_segundos)
    if salto < 1:
        salto = 1
    return salto

def leer_frame(ruta_video, numero_frame):
    captura = cv2.VideoCapture(str(ruta_video))
    captura.set(cv2.CAP_PROP_POS_FRAMES, numero_frame)
    hay_frame, frame = captura.read()
    captura.release()

    if not hay_frame:
        raise ValueError(f"No se pudo leer el frame {numero_frame}")
    return frame

def extraer_frames_elegidos(ruta_video, carpeta_destino, imagenes, al_avanzar=None):
    for archivo in carpeta_destino.glob("*.jpg"):
        archivo.unlink()

    ultimo = max(imagenes.keys())
    captura = cv2.VideoCapture(str(ruta_video))
    indice = 0
    guardados = 0
    ultimo_porcentaje = -1

    while indice <= ultimo:
        hay_frame, frame = captura.read()
        if not hay_frame:
            break

        if al_avanzar is not None and ultimo > 0:
            porcentaje = int(100 * indice / ultimo)
            if porcentaje != ultimo_porcentaje:
                ultimo_porcentaje = porcentaje
                al_avanzar(indice / ultimo)

        if indice in imagenes:
            datos = imagenes[indice]
            ruta_frame = carpeta_destino / datos["nombre"]

            frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            imagen = Image.fromarray(frame_rgb)
            imagen.save(ruta_frame, quality=config.CALIDAD_JPG, exif=datos["exif"])
            guardados += 1
        indice += 1

    captura.release()
    return guardados