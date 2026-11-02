import exifread
from PIL import Image
from src import config

def texto_windows(texto):
    return texto.encode("utf-16-le") + b"\x00\x00"

origen = sorted(config.CARPETA_FRAMES_ORIGINALES.glob("*.jpg"))[0]
destino = config.CARPETA_IMAGENES_PROCESADAS / "prueba_exif.jpg"
imagen = Image.open(origen)
exif = Image.Exif()

exif[0x0131] = "AgroScan El Carmen - Preprocesamiento"
exif[0x010E] = "Frame de prueba"

exif[0x9C9B] = texto_windows("AgroScan - Pasada 1 - Frame 000064")
exif[0x9C9C] = texto_windows("Video: prueba | Frame: 64 | Pasada: 1 (avance)")
exif[0x9C9F] = texto_windows("Asunto de prueba")

bloque_exif = exif.get_ifd(0x8769)
bloque_exif[0x9003] = "2026:10:26 16:00:00"

imagen.save(destino, quality=95, exif=exif)
print("Imagen guardada en:", destino)

print("\nMetadatos leídos con ExifRead:")
with open(destino, "rb") as archivo:
    etiquetas = exifread.process_file(archivo, details=False)

for nombre, valor in etiquetas.items():
    print(f" {nombre}: {valor}")