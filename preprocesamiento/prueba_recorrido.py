from src import config, recorrido, video

ruta_video = video.buscar_videos()[0]
pasos = recorrido.medir_recorrido(ruta_video, config.PASO_MEDICION_FRAMES)
print(f"Pasos medidos: {len(pasos)}")

grupos = []
for paso in pasos:
    clase = recorrido.clasificar_paso(paso)
    inicio = paso["frame"] - config.PASO_MEDICION_FRAMES
    if len(grupos) > 0 and grupos[-1][0] == clase:
        grupos[-1][2] += 1
    else:
        grupos.append([clase, inicio, 1])

omitidos = 0
for clase, inicio, cantidad in grupos:
    if cantidad >= 3:
        fin = inicio + cantidad * config.PASO_MEDICION_FRAMES
        print(f"frames {inicio:5d} - {fin:5d}: {clase:14s} ({cantidad} pasos)")
    else:
        omitidos += 1

print(f"Grupos cortos omitidos (menos de 3 pasos): {omitidos}")