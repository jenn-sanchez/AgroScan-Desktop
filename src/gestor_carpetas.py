from src import config

def crear_carpetas_de_trabajo():
    for carpeta in config.CARPETAS_DE_TRABAJO:
        carpeta.mkdir(parents=True, exist_ok=True)

def crear_carpeta_de_mision(carpeta_base, ruta_video):
    carpeta = carpeta_base / ruta_video.stem
    carpeta.mkdir(parents=True, exist_ok=True)
    return carpeta