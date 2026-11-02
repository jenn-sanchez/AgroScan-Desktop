import argparse
import json
import sys
from pathlib import Path
from src import config
from src import gestor_carpetas
from src import proceso
from src import video

def leer_argumentos():
    parser = argparse.ArgumentParser(
        description="Preprocesamiento de imágenes aéreas de AgroScan"
    )
    parser.add_argument("--video", help="Ruta del video MP4 a procesar")
    parser.add_argument("--salida", help="Carpeta donde se guardarán los resultados")
    parser.add_argument("--servidor", help="Dirección del servidor al que enviar las imágenes (opcional)")
    parser.add_argument("--mision", help="Identificador de la misión en el servidor (opcional)")
    parser.add_argument(
        "--solo-sincronizar", action="store_true",
        help="No procesar: solo enviar al servidor las imágenes pendientes de --salida",
    )
    parser.add_argument(
        "--json", action="store_true",
        help="Escribir el avance como líneas JSON (para otra aplicación)",
    )
    return parser.parse_args()

def avance_texto(porcentaje, mensaje):
    if porcentaje % 5 == 0:
        print(f"[{porcentaje:3d}%] {mensaje}", flush=True)

def avance_json(porcentaje, mensaje):
    evento = {"tipo": "progreso", "porcentaje": porcentaje, "etapa": mensaje}
    print(json.dumps(evento), flush=True)

def mostrar_error(mensaje, formato_json):
    if formato_json:
        print(json.dumps({"tipo": "error", "mensaje": mensaje}), flush=True)
    else:
        print(f"ERROR: {mensaje}", flush=True)

def mostrar_fin(resumen, formato_json):
    if formato_json:
        evento = {"tipo": "fin", "porcentaje": 100, "resumen": resumen}
        print(json.dumps(evento), flush=True)
    else:
        print("\nResumen:")
        for nombre, valor in resumen.items():
            print(f"  {nombre}: {valor}")

def sincronizar_pendientes(args, al_avanzar):
    if args.salida is None or args.servidor is None:
        mostrar_error("Con --solo-sincronizar se necesitan --salida y --servidor", args.json)
        sys.exit(1)

    try:
        resultado = proceso.sincronizar_salida(
            Path(args.salida), args.servidor, args.mision, al_avanzar
        )
    except Exception as error:
        mostrar_error(str(error), args.json)
        sys.exit(1)

    mostrar_fin({"sincronizacion": resultado}, args.json)

def main():
    args = leer_argumentos()
    gestor_carpetas.crear_carpetas_de_trabajo()
    al_avanzar = avance_json if args.json else avance_texto
    if args.solo_sincronizar:
        sincronizar_pendientes(args, al_avanzar)
        return

    if args.video is None:
        videos = video.buscar_videos()
        if len(videos) == 0:
            mostrar_error("No hay videos MP4 en la carpeta data/videos", args.json)
            sys.exit(1)
        ruta_video = videos[0]
    else:
        ruta_video = Path(args.video)

    if args.salida is None:
        carpeta_salida = config.CARPETA_DATOS / "salidas" / ruta_video.stem
    else:
        carpeta_salida = Path(args.salida)

    try:
        resumen = proceso.procesar_video(
            ruta_video, carpeta_salida, al_avanzar, args.servidor, args.mision
        )
    except Exception as error:
        mostrar_error(str(error), args.json)
        sys.exit(1)
    mostrar_fin(resumen, args.json)

if __name__ == "__main__":
    main()