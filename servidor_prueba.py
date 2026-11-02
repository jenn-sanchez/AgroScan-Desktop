import hashlib
import os
import time
from pathlib import Path
from flask import Flask, jsonify, request
from werkzeug.utils import secure_filename

app = Flask(__name__)

CARPETA = Path(__file__).resolve().parent / "data" / "servidor_prueba"
CARPETA.mkdir(parents=True, exist_ok=True)
RETRASO = float(os.environ.get("RETRASO", "0"))
FALLAR_EN = os.environ.get("FALLAR_EN", "")
CAER_TRAS = int(os.environ.get("CAER_TRAS", "0"))
contador = {"recibidas": 0}

@app.get("/api/salud")
def salud():
    return jsonify({"estado": "ok"})

@app.post("/api/imagenes")
def recibir_imagen():
    time.sleep(RETRASO)
    archivo_pedido = request.files.get("archivo")
    if FALLAR_EN and archivo_pedido is not None and FALLAR_EN in archivo_pedido.filename:
        return jsonify({"recibido": False, "error": "Fallo simulado"}), 500
    archivo = request.files.get("archivo")
    if archivo is None:
        return jsonify({"recibido": False, "error": "Falta el campo 'archivo'"}), 400
    mision = secure_filename(request.form.get("mision", "sin_mision")) or "sin_mision"
    nombre = secure_filename(archivo.filename)
    contenido = archivo.read()
    destino = CARPETA / mision
    destino.mkdir(parents=True, exist_ok=True)
    (destino / nombre).write_bytes(contenido)
    contador["recibidas"] += 1
    if CAER_TRAS > 0 and contador["recibidas"] >= CAER_TRAS:
        os._exit(0)
    return jsonify({
        "recibido": True,
        "nombre": nombre,
        "bytes": len(contenido),
        "sha256": hashlib.sha256(contenido).hexdigest(),
    }), 201

if __name__ == "__main__":
    app.run(port=5000)