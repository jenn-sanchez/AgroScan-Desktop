package org.agroscan.project.features.flightlog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * La importación de videos que está en marcha y lo que la pantalla de Procesamiento muestra de ella. Vive aquí y no en la pantalla:
 * si viviera en la pantalla, cambiar de pestaña la cerraría y dejaría la copia sin dueño. Solo hay una a la vez.
 */
object VideoImport {

    /** [progreso] = copiando; [buscando] = leyendo la tarjeta antes de copiar; [mensaje] = resultado de la última importación. */
    data class Estado(
        val buscando: Boolean = false,
        val progreso: VideoImporter.Progreso? = null,
        val mensaje: String? = null
    ) {
        val activo: Boolean get() = buscando || progreso != null
    }

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelar = AtomicBoolean(false)

    /** Empieza a importar los videos de [origen] en segundo plano. No hace nada si ya hay una importación en marcha. */
    fun iniciar(origen: File, store: FlightLogStore) {
        if (_estado.value.activo) return
        cancelar.set(false)
        _estado.value = Estado(buscando = true, mensaje = null)
        scope.launch {
            val r = runCatching {
                VideoImporter(store).importar(origen, progreso = { _estado.value = Estado(progreso = it) }, cancelado = cancelar::get)
            }
            _estado.value = Estado(mensaje = r.fold(::resumen) { "No se pudo importar: ${it.message}" })
        }
    }

    /** Pide parar: la copia se detiene en el siguiente trozo y borra el video a medias. */
    fun cancelar() {
        cancelar.set(true)
    }

    internal fun resumen(r: VideoImporter.Resultado): String = buildString {
        append("${r.copiados} video(s) copiado(s)")
        if (r.yaEstaban > 0) append(", ${r.yaEstaban} ya estaban")
        if (r.sinSalida.isNotEmpty()) append(", ${r.sinSalida.size} sin salida (no son de una misión o falta su registro)")
        if (r.cancelado) append(". Importación cancelada")
    }

    /**
     * La unidad que tiene una carpeta DCIM (la microSD del dron puesta en un lector), para no pedirle al usuario que la busque.
     * null si no hay ninguna o hay más de una (entonces se le pregunta). Se descarta la unidad del sistema.
     */
    fun detectarTarjeta(unidades: Array<File> = File.listRoots(), unidadSistema: String? = System.getenv("SystemDrive")): File? =
        unidades
            .filter { u -> unidadSistema == null || !u.path.startsWith(unidadSistema, ignoreCase = true) }
            .filter { u -> File(u, "DCIM").isDirectory }
            .singleOrNull()

    /** "4 min", "1 h 02 min", "35 s": para decir cuánto falta. */
    fun duracion(segundos: Long): String = when {
        segundos < 60 -> "$segundos s"
        segundos < 3600 -> "${(segundos + 30) / 60} min"
        else -> "${segundos / 3600} h %02d min".format((segundos % 3600) / 60)
    }

    /** "Copiando 1 de 3 · DJI_0101.MP4 · 34 % · 28,4 MB/s · faltan 4 min". */
    fun texto(p: VideoImporter.Progreso): String = buildString {
        append("Copiando ${p.archivo} de ${p.archivos} · ${p.nombre} · ${(p.fraccion * 100).toInt()} %")
        if (p.bytesPorSegundo > 0) append(" · %.1f MB/s".format(p.bytesPorSegundo / 1e6))
        p.segundosRestantes?.let { append(" · faltan ${duracion(it)}") }
    }
}
