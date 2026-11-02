import hashlib
import json
import time
from datetime import datetime
from pathlib import Path
import requests
from src import config

def calcular_huella(ruta):
    return hashlib.sha256(Path(ruta).read_bytes()).hexdigest()

def servidor_disponible(url):
    try:
        respuesta = requests.get(f"{url}/api/salud", timeout=config.TIEMPO_ESPERA_SALUD)
        return respuesta.status_code == 200
    except requests.exceptions.RequestException:
        return False

def subir_imagen(url, ruta, mision, huella):
    ruta = Path(ruta)
    try:
        with open(ruta, "rb") as archivo:
            respuesta = requests.post(
                f"{url}/api/imagenes",
                files={"archivo": (ruta.name, archivo, "image/jpeg")},
                data={"mision": mision},
                timeout=config.TIEMPO_ESPERA_SUBIDA,
            )
    except requests.exceptions.RequestException as error:
        return "red", type(error).__name__

    if respuesta.status_code != 201:
        return "rechazada", f"El servidor respondió {respuesta.status_code}"

    try:
        datos = respuesta.json()
    except ValueError:
        return "rechazada", "Respuesta del servidor ilegible"

    if datos.get("sha256") != huella:
        return "rechazada", "La huella devuelta no coincide: el archivo llegó alterado"
    return "ok", ""

def cargar_estado(ruta_estado):
    if ruta_estado.exists():
        with open(ruta_estado, "r", encoding="utf-8") as archivo:
            return json.load(archivo)
    return {"imagenes": {}}

def guardar_estado(ruta_estado, estado):
    temporal = ruta_estado.with_suffix(".tmp")
    with open(temporal, "w", encoding="utf-8") as archivo:
        json.dump(estado, archivo, indent=2, ensure_ascii=False)
    temporal.replace(ruta_estado)

def sincronizar(carpeta_finales, url, mision, ruta_estado, al_avanzar=None):
    carpeta_finales = Path(carpeta_finales)
    ruta_estado = Path(ruta_estado)
    rutas = sorted(carpeta_finales.glob("*.jpg"))
    estado = cargar_estado(ruta_estado)
    estado["servidor"] = url
    estado["mision"] = mision
    imagenes = estado["imagenes"]

    huellas = {}
    for ruta in rutas:
        huella = calcular_huella(ruta)
        huellas[ruta.name] = huella
        registro = imagenes.get(ruta.name)
        if registro is None or registro["huella"] != huella:
            imagenes[ruta.name] = {
                "estado": "pendiente", "huella": huella,
                "intentos": 0, "ultimo_error": "", "fecha": "",
            }

    for nombre in list(imagenes.keys()):
        if nombre not in huellas:
            del imagenes[nombre]

    guardar_estado(ruta_estado, estado)

    sin_conexion = False
    por_enviar = [n for n in sorted(imagenes) if imagenes[n]["estado"] != "enviada"]

    if len(por_enviar) > 0:
        if not servidor_disponible(url):
            sin_conexion = True
        else:
            for numero, nombre in enumerate(por_enviar, start=1):
                registro = imagenes[nombre]
                resultado = "red"
                mensaje = ""
                espera = config.SEGUNDOS_ENTRE_INTENTOS

                for intento in range(1, config.INTENTOS_POR_IMAGEN + 1):
                    registro["intentos"] += 1
                    resultado, mensaje = subir_imagen(
                        url, carpeta_finales / nombre, mision, huellas[nombre]
                    )
                    if resultado == "ok":
                        break
                    if intento < config.INTENTOS_POR_IMAGEN:
                        time.sleep(espera)
                        espera = espera * 2

                registro["fecha"] = datetime.now().isoformat(timespec="seconds")
                if resultado == "ok":
                    registro["estado"] = "enviada"
                    registro["ultimo_error"] = ""
                elif resultado == "rechazada":
                    registro["estado"] = "error"
                    registro["ultimo_error"] = mensaje
                else:
                    registro["estado"] = "pendiente"
                    registro["ultimo_error"] = mensaje
                    sin_conexion = True

                guardar_estado(ruta_estado, estado)
                if al_avanzar is not None:
                    al_avanzar(numero / len(por_enviar))

                if resultado == "red":
                    break   
    enviadas = 0
    pendientes = 0
    con_error = 0
    for registro in imagenes.values():
        if registro["estado"] == "enviada":
            enviadas += 1
        elif registro["estado"] == "error":
            con_error += 1
        else:
            pendientes += 1

    return {
        "enviadas": enviadas,
        "pendientes": pendientes,
        "con_error": con_error,
        "sin_conexion": sin_conexion,
    }