"""Valores del módulo en un solo lugar."""

NOMBRE_PROGRAMA = "AgroScan ortomosaico"
MOTOR_DIRECTO = "directo-gps 1.0"      # cada imagen donde dice el GPS
MOTOR_AJUSTADO = "ajustado 1.0"        # y luego corregida con los puntos en común entre imágenes vecinas

# Cámara por defecto: DJI Mini 3 grabando video 16:9 (campo de visión vertical de 45.8°, el mismo que usa la app del teléfono).
# La entrada puede traer otro (`camara.fov_vertical_deg`); el horizontal sale del tamaño de la imagen.
FOV_VERTICAL_DEG = 45.8

# Sincronización del video con el registro
MARCAS_MINIMAS = 3
DESVIO_MARCAS_MAX_S = 2.0          # si las marcas varían más que esto, el video no es de esta salida
TRAMO_UTIL = "barrido"             # solo se usan las imágenes tomadas barriendo el lote (no ascenso, tránsito ni giro)
GIMBAL_NADIR_DEG = -90.0
GIMBAL_TOLERANCIA_DEG = 10.0       # más inclinada que esto, la imagen no se puede apoyar plana sobre el terreno
ALTURA_MINIMA_M = 3.0              # por debajo, el dron está subiendo o bajando
ERROR_GPS_M = 3.0                  # lo que puede estar corrida cada imagen: es un GPS sin ajuste por las imágenes

# Resolución del mosaico: cuántas veces el tamaño de píxel de las imágenes según la calidad elegida
FACTOR_RESOLUCION = {"rapida": 4.0, "estandar": 2.0, "alta": 1.0}
MAX_PIXELES = 150_000_000          # un lote enorme baja de resolución en vez de agotar la memoria o el disco
LADO_VISTA_PX = 3072               # lado mayor de vista.jpg (lo que muestra el visor)
LADO_MINIATURA_PX = 480
CALIDAD_JPEG = 88
TESELA_PX = 512                    # tamaño de las teselas internas del GeoTIFF (compresión), no las de la IA
MARGEN_M = 0.0

# Teselas para la IA: cuadrícula fija por lote (en metros), la misma en todas las fechas
TESELA_LADO_M = 10.0               # cada celda mide 10 m × 10 m; sus píxeles dependen de la resolución real de las imágenes del vuelo (no se baja ni se sube)
TESELA_GSD_MIN_M = 0.005           # (nunca más fino que 5 mm por píxel: no tiene sentido y haría teselas enormes)
TESELA_LADO_MIN_PX = 64
TESELA_MIN_EN_LOTE_PCT = 5.0       # una celda que apenas roza el lote no se corta
TESELA_MIN_COBERTURA_PCT = 30.0    # ni la que casi no tiene imagen dentro del lote (solo cuenta si hay que mezclar)
TESELA_CALIDAD_JPEG = 98           # casi sin pérdida, y sin submuestrear el color: una hoja con un síntoma tiene que llegar entera al modelo
TESELA_CANDIDATAS = 3              # de cuántas imágenes (las más centrales que contienen la celda) se elige la más nítida
TESELA_MARGEN_BORDE = 0.04         # la celda debe quedar a más de esta fracción del borde de la imagen (el borde es lo más deformado)

# Ajuste de las imágenes entre sí (ver ajuste.py)
AJUSTE_GSD_M = 0.06                # resolución a la que se buscan puntos en común (6 cm por píxel)
AJUSTE_RASGOS = 700                # puntos por imagen
AJUSTE_VECINAS = 6                 # con cuántas vecinas se compara cada imagen
AJUSTE_RAZON = 0.75                # una coincidencia vale si es bastante mejor que la segunda mejor
AJUSTE_MIN_COINCIDENCIAS = 12
AJUSTE_MIN_INLIERS = 10
AJUSTE_UMBRAL_M = 0.35             # un punto «coincide» si cae a menos de esto del movimiento común de su par
AJUSTE_PUNTOS_POR_PAR = 25
AJUSTE_SIGMA_PUNTO_M = 0.3         # cuánto se confía en cada punto en común
AJUSTE_SIGMA_GPS_M = 3.0           # cuánto se confía en el GPS (el ancla)
AJUSTE_SIGMA_GIRO_GRADOS = 8.0     # y en la brújula (el ancla del rumbo es floja: lo que ata es la suavidad)
AJUSTE_SUAVE_M = 1.0               # cuánto puede cambiar el error de posición de una imagen a la siguiente
AJUSTE_SUAVE_GIRO_GRADOS = 1.0     # y el de rumbo
AJUSTE_SUAVE_MAX_S = 4.0           # solo entre imágenes tomadas con menos de esto de diferencia (no a través de giros ni cortes)
AJUSTE_ESCALA_MIN = 0.3            # entre qué factores se busca el tamaño real de las imágenes respecto del que da el registro
AJUSTE_ESCALA_MAX = 5.0            # (un vuelo desde una loma puede tener el suelo muy por debajo del punto de despegue)
AJUSTE_SIGMA_ESCALA = 0.35         # cuánto puede apartarse cada imagen del factor del vuelo (en logaritmo natural: ±35 %; hay vuelos con el doble de altura de un extremo a otro)
AJUSTE_SUAVE_ESCALA = 0.07         # y cuánto cambia el factor de una imagen a la siguiente (el relieve cambia despacio)
AJUSTE_ESCALA_PAR_MAX = 1.6        # un par de vecinas con tamaños que difieren más que esto no es creíble
AJUSTE_GIRO_PAR_MAX_GRADOS = 10.0
AJUSTE_ITERACIONES = 12
AJUSTE_VUELTAS_SIN_ROBUSTEZ = 3     # las primeras vueltas, con todos los puntos por igual (todavía hay mucho desajuste y todo parece «raro»)
AJUSTE_GIRO_FOTO_MAX_GRADOS = 15.0     # una imagen que salga con más giro o corrimiento que esto no es creíble: se le da el de sus vecinas
AJUSTE_DESPLAZAMIENTO_FOTO_MAX_M = 12.0
AJUSTE_ROBUSTO_VECES = 2.5         # a partir de ahí, un punto que se aparta más que esto veces lo típico pesa menos
AJUSTE_ROBUSTO_MIN_M = 0.4         # (pero nunca se castiga lo que está a menos de esto)
AJUSTE_ESCALA_AVISO = 0.15         # si la escala estimada se aparta más que esto de 1, se le dice al usuario que el terreno no está a la altura del despegue
AJUSTE_MIN_FOTOS = 4
AJUSTE_MAX_FOTOS = 8000
AJUSTE_MIN_FOTOS_CON_PARES_PCT = 60.0
AJUSTE_YA_COINCIDEN_M = 0.25       # si ya coinciden así, el GPS acertó y no hay nada que ajustar
AJUSTE_MEJORA_MINIMA = 0.9         # el ajuste debe dejar el desacuerdo en menos del 90 % del que había
AJUSTE_MAX_DESPLAZAMIENTO_M = 15.0
AJUSTE_MAX_GIRO_GRADOS = 20.0

# Alinear una fecha con la primera del lote (ver referencia.py)
ALINEADO_GSD_MIN_M = 0.15          # resolución a la que se comparan las dos vistas
ALINEADO_SIGMA_PX = 6.0            # se quita lo que cambia más despacio que esto (brillo, color general)
ALINEADO_MIN_SOLAPE_PCT = 30.0     # lo que deben superponerse, respecto del más chico
ALINEADO_MIN_RESPUESTA = 0.15      # confianza mínima de la correlación para fiarse del corrimiento
ALINEADO_MAX_M = 25.0              # más que esto no parece un error de GPS
ALINEADO_BLOQUE_M = 16.0           # tamaño de los bloques en que se mide la deriva local
ALINEADO_BLOQUE_MIN_SOLAPE = 0.6   # fracción del bloque que deben ver las dos fechas
ALINEADO_BLOQUE_MIN_RESPUESTA = 0.08
ALINEADO_BLOQUE_DESVIO_M = 3.0     # tras el corrimiento de conjunto, un bloque que pide más que esto es una coincidencia falsa
ALINEADO_CAMPO_MAX_M = 4.0         # y el campo nunca corre una imagen más que esto además del conjunto
ALINEADO_RESIDUO_MIN_RESPUESTA = 0.2
ALINEADO_MIN_BLOQUES = 4
ALINEADO_ANCHO_CAMPO_M = 24.0      # a qué escala espacial promedia el campo de corrección entre fechas (kernel gaussiano)
ALINEADO_PESO_MIN_CAMPO = 0.15     # donde los bloques cercanos pesan menos que esto, la corrección se atenúa hasta cero
ALINEADO_BLOQUE_FUERTE = 0.25      # un bloque con este parecido o más cuenta como claro por sí solo
ALINEADO_MIN_BLOQUES_FUERTES = 8   # con tantos bloques claros se puede alinear aunque la correlación de todo junto no lo sea
ALINEADO_BLOQUE_RESTO_MIN_M = 0.5  # un bloque que difiere de sus vecinos menos que esto nunca se descarta
ALINEADO_ERROR_MIN_M = 0.3         # piso del error estimado: no se afirma más precisión que esto
ALINEADO_RESIDUO_AVISO_M = 1.5     # si tras alinear queda más desacuerdo que esto, se avisa
ALINEADO_VUELTAS = 3               # cuántas veces se mide y corrige lo que queda
ALINEADO_RESIDUO_OBJETIVO_M = 0.4  # con menos desacuerdo que esto no se sigue

# Mezcla de imágenes superpuestas: el peso baja hacia el borde de cada imagen (más lejos del centro, más deformación y menos precisión)
POTENCIA_PESO = 40.0               # alta a propósito: con relieve y árboles altos, mezclar vistas distintas del mismo punto lo vuelve borroso y doble;
                                   # así manda casi solo la imagen que lo ve más de frente (la más cercana a su centro). Probado en vuelos reales: con 3 sale
                                   # velado, con 10 mejora y con 40 se distinguen copas y ramas
FONDO_VISTA = (31, 41, 55)         # lo que rodea al mosaico en vista.jpg; el mismo gris del visor de la app

# Progreso (porcentaje al terminar cada etapa)
PROGRESO_FIN_LEER = 3
PROGRESO_FIN_UBICAR = 15
PROGRESO_FIN_AJUSTAR = 45
PROGRESO_FIN_VISTA = 52
PROGRESO_FIN_GEOTIFF = 85
PROGRESO_FIN_TESELAS = 97
