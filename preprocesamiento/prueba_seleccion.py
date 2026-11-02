from src import config, recorrido, seleccion, video

ruta_video = video.buscar_videos()[0]
archivo_pasos = config.CARPETA_METADATOS / f"{ruta_video.stem}_pasos.json"

if archivo_pasos.exists():
    pasos = recorrido.cargar_pasos(archivo_pasos)
    print("Pasos cargados desde el archivo guardado")
else:
    pasos = recorrido.medir_recorrido(ruta_video, config.PASO_MEDICION_FRAMES)
    recorrido.guardar_pasos(pasos, archivo_pasos)
    print("Pasos medidos y guardados")

elegidos = seleccion.seleccionar_frames(pasos)
print(f"Frames seleccionados: {len(elegidos)}\n")

for e in elegidos:
    if e["solape"] is None:
        solape = "  --  "
    else:
        solape = f"{e['solape']:.1f}%"
    print(f"frame {e['frame']:5d} | pasada {e['pasada']} | {e['sentido']:9s} | solape {solape}")

solapes = []
for e in elegidos:
    if e["solape"] is not None:
        solapes.append(e["solape"])
print(f"\nSolape mínimo entre frames consecutivos: {min(solapes):.1f}%")

for numero in (1, 2, 3):
    cantidad = 0
    for e in elegidos:
        if e["pasada"] == numero:
            cantidad += 1
    print(f"Pasada {numero}: {cantidad} frames")