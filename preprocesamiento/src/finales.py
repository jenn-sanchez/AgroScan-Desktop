import cv2
from PIL import Image
from src import config, mejora, metadatos

def generar_imagenes_finales(ruta_video, carpeta_destino, trabajos, referencia, al_avanzar=None):
    for archivo in carpeta_destino.glob("*.jpg"):
        archivo.unlink()
    ultimo = max(trabajos.keys())
    captura = cv2.VideoCapture(str(ruta_video))
    indice = 0
    resumen = []
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

        if indice in trabajos:
            trabajo = trabajos[indice]
            mejorada, aplicados, diagnostico_final = mejora.mejorar_imagen(
                frame, trabajo["diagnostico"], referencia
            )
            textos = metadatos.agregar_calidad(trabajo["textos"], aplicados, diagnostico_final)
            exif = metadatos.crear_exif(textos)
            frame_rgb = cv2.cvtColor(mejorada, cv2.COLOR_BGR2RGB)
            imagen = Image.fromarray(frame_rgb)
            imagen.save(carpeta_destino / trabajo["nombre"], quality=config.CALIDAD_JPG, exif=exif)
            resumen.append({
                "nombre": trabajo["nombre"],
                "aplicados": aplicados,
                "pendientes": diagnostico_final["ajustes"],
            })
        indice += 1

    captura.release()
    return resumen