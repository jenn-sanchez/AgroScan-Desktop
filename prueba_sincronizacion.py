from src import config, sincronizacion

URL = "http://127.0.0.1:5000"

carpeta_salida = sorted((config.CARPETA_DATOS / "salidas").glob("*"))[0]
carpeta_finales = carpeta_salida / config.SUBCARPETA_FINALES
ruta_estado = carpeta_salida / config.ARCHIVO_ESTADO

def mostrar(fraccion):
    print(f"  sincronizando... {int(fraccion * 100)} %")

resumen = sincronizacion.sincronizar(carpeta_finales, URL, "prueba-mision", ruta_estado, mostrar)
print("\nResumen:", resumen)
print("Estado guardado en:", ruta_estado)