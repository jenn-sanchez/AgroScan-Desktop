package org.agroscan.project.features.ortomosaico

import org.agroscan.project.features.flightlog.FlightLogStore
import org.agroscan.project.features.preproceso.Preprocesamiento
import java.io.File

/** En qué punto del camino está un vuelo hacia su ortomosaico. */
enum class EstadoVuelo(val etiqueta: String) {
    SIN_PREPROCESAR("Sin preprocesar"),
    LISTO("Listo para armar"),
    ARMANDO("Armando"),
    CREADO("Ortomosaico creado")
}

/** Un vuelo recibido, con lo que tiene cada salida y el ortomosaico que ya tiene, si tiene. */
data class VueloArmado(
    val uuid: String,
    val carpeta: File,
    val lote: String,
    val mision: String,
    val inicioMs: Long,
    val modo: String,
    val salidas: List<SalidaArmado>,
    val existente: Entrada?
) {
    val estado: EstadoVuelo
        get() = when {
            existente?.ficha?.estado == EstadoOrtomosaico.PROCESANDO -> EstadoVuelo.ARMANDO
            existente?.listo == true -> EstadoVuelo.CREADO
            salidas.any { it.lista } -> EstadoVuelo.LISTO
            else -> EstadoVuelo.SIN_PREPROCESAR
        }

    /** Las salidas que se incluyen si el usuario no cambia nada: todas las que ya están listas. */
    val incluidasPorDefecto: Set<Int> get() = salidas.filter { it.lista }.map { it.indice }.toSet()
}

/** Arma la lista de vuelos de la pestaña Crear a partir de lo que ya está en disco. */
object VuelosParaArmar {

    /** Los vuelos recibidos, del más reciente al más antiguo. Bloqueante (lee carpetas): llamar fuera del hilo de UI. */
    fun leer(store: FlightLogStore, ortomosaicos: List<Entrada>): List<VueloArmado> {
        val porVuelo = ortomosaicos.associateBy { it.ficha.vueloUuid }
        return store.salidas()
            .groupBy { it.info.vueloUuid }
            .map { (uuid, salidas) ->
                val primera = salidas.minBy { it.info.indice }.info
                VueloArmado(
                    uuid = uuid,
                    carpeta = salidas.first().dir.parentFile,
                    lote = primera.lote,
                    mision = primera.mision,
                    inicioMs = primera.vueloInicioMs,
                    modo = primera.modo,
                    salidas = salidas.sortedBy { it.info.indice }.map { s ->
                        SalidaArmado(
                            indice = s.info.indice,
                            registro = s.registro.isFile,
                            videos = s.videoDir.listFiles { f -> f.extension.equals("mp4", ignoreCase = true) }?.size ?: 0,
                            imagenes = Preprocesamiento.imagenesListas(Preprocesamiento.carpeta(s))
                        )
                    },
                    existente = porVuelo[uuid]
                )
            }
            .sortedByDescending { it.inicioMs }
    }
}
