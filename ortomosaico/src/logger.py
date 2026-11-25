import logging
import sys


def configurar() -> None:
    """El registro detallado va a la salida de errores: la estándar queda solo para los eventos JSON que lee la app."""
    logging.basicConfig(
        stream=sys.stderr, level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S", force=True
    )
