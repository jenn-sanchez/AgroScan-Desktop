package org.agroscan.project.features.preproceso

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.agroscan.project.core.EventLog
import org.agroscan.project.core.MiniJson
import org.agroscan.project.features.flightlog.FlightLogStore
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * El preprocesamiento de imágenes en marcha (o el último) y lo que la pantalla de Procesamiento muestra de él. Ejecuta el módulo
 * de Python ([PreprocessModule]) por detrás, video por video, y lee su avance. Vive aquí y no en la pantalla: cambiar de pestaña
 * no lo corta. Solo hay uno a la vez.
 *
 * Cada video va a su propia carpeta (`<salida>\preproceso\<video>\`): el módulo borra lo que hay en su carpeta al empezar, y una
 * salida puede tener varios videos (el dron parte los largos). Dentro, el módulo deja `frames_originales\` e `imagenes_procesadas\`.
 */
object Preprocesamiento {

    /** Totales del módulo (suma de todos los videos procesados). */
    data class Resumen(
        val imagenes: Int = 0,
        val modificadas: Int = 0,
        val conAdvertencias: Int = 0,
        val conPendientes: Int = 0,
        /** Videos en que el módulo no detectó el recorrido y eligió las imágenes cada cierto tiempo (plan B), sin medir el solape. */
        val sinRecorrido: Int = 0
    ) {
        operator fun plus(o: Resumen) = Resumen(
            imagenes + o.imagenes, modificadas + o.modificadas, conAdvertencias + o.conAdvertencias,
            conPendientes + o.conPendientes, sinRecorrido + o.sinRecorrido
        )
    }

    data class Estado(
        val activo: Boolean = false,
        /** Qué se procesa, para mostrarlo ("Lote 28/10 · Salida 1"). */
        val titulo: String = "",
        /** Carpeta donde quedan los resultados. */
        val resultados: File? = null,
        val video: Int = 0,
        val videos: Int = 0,
        val nombreVideo: String = "",
        /** Avance de todo (todos los videos), 0–100. */
        val porcentaje: Int = 0,
        val etapa: String = "",
        val resumen: Resumen? = null,
        val error: String? = null,
        val cancelado: Boolean = false
    )

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var proceso: Process? = null
    @Volatile private var cancelar = false

    init {
        // Si se cierra la app a mitad, el módulo no debe quedar trabajando solo
        Runtime.getRuntime().addShutdownHook(Thread { proceso?.destroyForcibly() })
    }

    /** Carpeta de resultados de una salida. */
    fun carpeta(salida: FlightLogStore.Stored): File = File(salida.dir, "preproceso")

    /** Preprocesa los videos de una salida recibida (los que importó la microSD). */
    fun iniciar(salida: FlightLogStore.Stored) {
        val videos = salida.videoDir.listFiles { f -> f.extension.equals("mp4", ignoreCase = true) }?.sortedBy { it.name }.orEmpty()
        val vuelo = salida.dir.parentFile.name
        iniciar(videos, carpeta(salida), "${vuelo}_${salida.dir.name}", "${salida.info.lote} · Salida ${salida.info.indice + 1}")
    }

    /** Preprocesa [videos]: cada uno en `<resultados>\<nombre del video>\`. No hace nada si ya hay uno en marcha. */
    fun iniciar(videos: List<File>, resultados: File, mision: String, titulo: String) {
        if (_estado.value.activo) return
        val ubicado = PreprocessModule.ubicar()
        val problema = ubicado.exceptionOrNull()?.message ?: if (videos.isEmpty()) "No hay videos para procesar" else null
        if (problema != null) {
            _estado.value = Estado(titulo = titulo, resultados = resultados, error = problema)
            return
        }
        val modulo = ubicado.getOrThrow()
        cancelar = false
        _estado.value = Estado(activo = true, titulo = titulo, resultados = resultados, videos = videos.size, etapa = "Preparando video")
        EventLog.log("preproceso", "Inicio: $titulo · ${videos.size} video(s) · módulo: ${modulo.programa.joinToString(" ")} en ${modulo.carpeta}")
        scope.launch {
            var total = Resumen()
            for ((i, video) in videos.withIndex()) {
                val r = runCatching { procesar(modulo, video, File(resultados, video.nameWithoutExtension), mision, i, videos.size) }
                val error = r.exceptionOrNull()?.message ?: r.getOrNull()?.exceptionOrNull()?.message
                when {
                    cancelar -> return@launch terminar(total, cancelado = true)
                    error != null -> return@launch terminar(total, error = "${video.name}: $error")
                    else -> total += r.getOrThrow().getOrThrow()
                }
            }
            terminar(total)
        }
    }

    /** Pide parar: se corta el módulo en el acto (lo que dejó a medias en la carpeta de ese video no vale). */
    fun cancelar() {
        cancelar = true
        proceso?.destroy()
    }

    private fun terminar(total: Resumen, error: String? = null, cancelado: Boolean = false) {
        _estado.value = _estado.value.copy(
            activo = false, resumen = total, error = error, cancelado = cancelado,
            porcentaje = if (error == null && !cancelado) 100 else _estado.value.porcentaje,
            etapa = when { cancelado -> "Cancelado"; error != null -> "Se detuvo por un error"; else -> "Preprocesamiento completado" }
        )
        EventLog.log("preproceso", if (error != null) "ERROR: $error" else if (cancelado) "Cancelado" else "Terminado: $total")
        proceso = null
    }

    /** Ejecuta el módulo con un video y devuelve su resumen (o el error que informó). */
    private fun procesar(modulo: PreprocessModule.Modulo, video: File, salida: File, mision: String, i: Int, n: Int): Result<Resumen> {
        _estado.value = _estado.value.copy(video = i + 1, nombreVideo = video.name)
        val log = File(EventLog.dir.apply { mkdirs() }, "preproceso-${LocalDate.now()}.log")
        val p = PreprocessModule.proceso(modulo, video, salida, mision)
            // El registro detallado del módulo sale por la salida de errores: hay que leerlo o el proceso se traba al llenarse
            .redirectError(ProcessBuilder.Redirect.appendTo(log))
            .start()
        proceso = p
        var resultado: Result<Resumen>? = null
        p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { linea ->
            when (val e = evento(linea)) {
                is Evento.Progreso -> _estado.value = _estado.value.copy(porcentaje = total(i, n, e.porcentaje), etapa = e.etapa)
                is Evento.Fin -> resultado = Result.success(e.resumen)
                is Evento.Error -> resultado = Result.failure(IllegalStateException(e.mensaje))
                null -> {}   // una línea que no es del contrato se ignora
            }
        }
        val codigo = if (p.waitFor(10, TimeUnit.SECONDS)) p.exitValue() else { p.destroyForcibly(); -1 }
        return resultado ?: Result.failure(IllegalStateException("el módulo terminó sin resultado (código $codigo); detalle en ${log.name}"))
    }

    sealed interface Evento {
        data class Progreso(val porcentaje: Int, val etapa: String) : Evento
        data class Fin(val resumen: Resumen) : Evento
        data class Error(val mensaje: String) : Evento
    }

    /** Una línea de la salida del módulo (`{"tipo": "progreso" | "fin" | "error", …}`); null si no es del contrato. */
    internal fun evento(linea: String): Evento? {
        val j = runCatching { MiniJson.parse(linea) }.getOrNull() as? Map<*, *> ?: return null
        fun num(m: Map<*, *>, k: String) = (m[k] as? Double)?.toInt() ?: 0
        return when (j["tipo"]) {
            "progreso" -> Evento.Progreso(num(j, "porcentaje").coerceIn(0, 100), j["etapa"] as? String ?: "")
            "error" -> Evento.Error(j["mensaje"] as? String ?: "error sin detalle")
            "fin" -> {
                val r = j["resumen"] as? Map<*, *> ?: return null
                Evento.Fin(
                    Resumen(
                        imagenes = num(r, "imagenes_finales"), modificadas = num(r, "modificadas"),
                        conAdvertencias = num(r, "con_advertencias"), conPendientes = num(r, "con_pendientes"),
                        sinRecorrido = if (r["plan_b"] == true) 1 else 0
                    )
                )
            }
            else -> null
        }
    }

    /** Avance de todo: el video [i] de [n] va por [porcentaje] %. */
    internal fun total(i: Int, n: Int, porcentaje: Int): Int = ((i * 100 + porcentaje) / n.coerceAtLeast(1)).coerceIn(0, 100)

    /** Cuántas imágenes finales ya hay en la carpeta de resultados de una salida (de todos sus videos). */
    fun imagenesListas(resultados: File): Int =
        resultados.listFiles()?.sumOf { File(it, "imagenes_procesadas").listFiles { f -> f.extension.equals("jpg", true) }?.size ?: 0 } ?: 0
}
