class ErrorOrtomosaico(Exception):
    """Un problema que el usuario puede entender y resolver: el mensaje dice qué pasó y qué hacer.

    Es lo único que `main.py` informa tal cual; cualquier otra excepción es un fallo del módulo y se informa como inesperado.
    """
