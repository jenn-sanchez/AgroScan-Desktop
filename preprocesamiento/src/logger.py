import logging
from datetime import datetime
from src import config

def configurar_logger():
    logger = logging.getLogger("agroscan")
    if logger.handlers:
        return logger
    logger.setLevel(logging.INFO)

    fecha = datetime.now().strftime("%Y-%m-%d")
    ruta_log = config.CARPETA_LOGS / f"preproceso_{fecha}.log"

    formato = logging.Formatter(
        "%(asctime)s | %(levelname)s | %(message)s",
        "%Y-%m-%d %H:%M:%S",
    )

    destino_archivo = logging.FileHandler(ruta_log, encoding="utf-8")
    destino_archivo.setFormatter(formato)

    destino_pantalla = logging.StreamHandler()
    destino_pantalla.setFormatter(formato)

    logger.addHandler(destino_archivo)
    logger.addHandler(destino_pantalla)

    return logger