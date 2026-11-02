import hashlib
import requests
from src import config

URL = "http://127.0.0.1:5000"

rutas = sorted((config.CARPETA_DATOS / "salidas").glob("*/imagenes_procesadas/*.jpg"))
ruta = rutas[0]
print(f"Imagen a subir: {ruta.name}")

respuesta = requests.get(f"{URL}/api/salud", timeout=3)
print("Salud:", respuesta.status_code, respuesta.json())

with open(ruta, "rb") as archivo:
    respuesta = requests.post(
        f"{URL}/api/imagenes",
        files={"archivo": (ruta.name, archivo, "image/jpeg")},
        data={"mision": "prueba"},
        timeout=30,
    )
print("Subida:", respuesta.status_code, respuesta.json())

huella_local = hashlib.sha256(ruta.read_bytes()).hexdigest()
print("La huella coincide:", respuesta.json()["sha256"] == huella_local)