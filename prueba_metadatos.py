from src import config, metadatos, recorrido, seleccion, video

ruta_video = video.buscar_videos()[0]
info = video.obtener_info_video(ruta_video)

archivo_pasos = config.CARPETA_METADATOS / f"{ruta_video.stem}_pasos.json"
pasos = recorrido.cargar_pasos(archivo_pasos)

elegidos = seleccion.seleccionar_frames(pasos)
metadatos.agregar_posicion_en_pasada(elegidos)

fecha_inicio = metadatos.calcular_fecha_inicio(ruta_video, info["duracion_segundos"])
print("Fecha de inicio estimada:", fecha_inicio)

for dato in (elegidos[0], elegidos[1], elegidos[23], elegidos[-1]):
    textos = metadatos.crear_textos(dato, ruta_video.name, fecha_inicio, info["fps"])
    print()
    print("Título     :", textos["titulo"])
    print("Fecha      :", textos["fecha"])
    print("Comentarios:", textos["comentarios"])