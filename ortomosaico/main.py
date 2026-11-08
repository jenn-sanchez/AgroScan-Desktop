"""Arma el ortomosaico de un vuelo.

    python main.py --entrada entrada.json --salida carpeta [--json]

Con --json escribe una línea JSON por evento en la salida estándar (el contrato con la app de escritorio):
    {"tipo": "progreso", "porcentaje": 42, "etapa": "…"}
    {"tipo": "fin", "resumen": {…}}
    {"tipo": "error", "mensaje": "…"}          (mensaje para el usuario: qué pasó y qué hacer)
El registro detallado sale por la salida de errores. Código de salida: 0 bien, 1 problema del usuario, 2 fallo inesperado.
"""
import argparse
import json
import logging
import sys
import traceback
from pathlib import Path

from src import logger
from src.errores import ErrorOrtomosaico
from src.proceso import armar


def _linea(evento: dict) -> None:
    sys.stdout.write(json.dumps(evento, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Arma el ortomosaico de un vuelo")
    ap.add_argument("--entrada", required=True, type=Path, help="la orden de armado (entrada.json)")
    ap.add_argument("--salida", required=True, type=Path, help="carpeta donde se escribe el resultado")
    ap.add_argument("--json", action="store_true", help="eventos en JSON, una línea cada uno")
    args = ap.parse_args(argv)
    logger.configurar()

    ultimo = [-1]

    def avisar(porcentaje, etapa):
        p = max(0, min(100, int(porcentaje)))
        if p != ultimo[0] or not args.json:
            ultimo[0] = p
            if args.json:
                _linea({"tipo": "progreso", "porcentaje": p, "etapa": etapa})
            else:
                print(f"[{p:3d} %] {etapa}")

    try:
        resumen = armar(args.entrada, args.salida, avisar)
    except ErrorOrtomosaico as e:
        logging.getLogger("ortomosaico").error("%s", e)
        _linea({"tipo": "error", "mensaje": str(e)}) if args.json else print(f"ERROR: {e}", file=sys.stderr)
        return 1
    except Exception as e:      # un fallo del módulo, no del usuario: se informa y el detalle queda en el registro
        traceback.print_exc()
        mensaje = f"El módulo de ortomosaicos falló de forma inesperada ({type(e).__name__}: {e}). El detalle quedó en el registro."
        _linea({"tipo": "error", "mensaje": mensaje}) if args.json else print(f"ERROR: {mensaje}", file=sys.stderr)
        return 2
    if args.json:
        _linea({"tipo": "fin", "resumen": resumen})
    else:
        print(json.dumps(resumen, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
