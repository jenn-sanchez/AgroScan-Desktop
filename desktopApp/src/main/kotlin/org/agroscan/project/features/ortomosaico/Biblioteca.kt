package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.DataFolder
import org.agroscan.project.features.flightlog.FlightLogStore
import java.util.UUID


/** Un lote tal como lo guarda la base (para mostrar también los que todavía no tienen ortomosaicos). */
data class LoteBase(val uuid: String, val nombre: String, val areaHa: Double?)

/** El historial de un polígono: sus ortomosaicos del más reciente al más antiguo. */
data class LoteHistorial(val uuid: String, val nombre: String, val areaHa: Double?, val ortomosaicos: List<Entrada>) {
    /** Los que se pueden ver y comparar. */
    val listos: List<Entrada> get() = ortomosaicos.filter { it.listo }
    val ultimo: Entrada? get() = ortomosaicos.firstOrNull()
}

/** Todo lo que muestra la pestaña Creados. [aviso] explica, si hace falta, por qué la lista puede estar incompleta. */
data class Biblioteca(val lotes: List<LoteHistorial>, val aviso: String? = null)

object Bibliotecas {

    /**
     * Agrupa los ortomosaicos por lote. Solo se muestran los del usuario que entró (igual que el resto de pantallas). Si hay [lotesBase],
     * también salen los lotes sin ortomosaicos, para poder ver de un vistazo cuáles faltan; si es null (base apagada), solo salen los
     * lotes que ya tienen alguno. Primero van los lotes con el ortomosaico más reciente.
     */
    fun construir(entradas: List<Entrada>, lotesBase: List<LoteBase>?, usuarioId: String, aviso: String? = null): Biblioteca {
        val visibles = entradas.filter { it.ficha.usuarioId == null || it.ficha.usuarioId == usuarioId }
        val porLote = visibles.groupBy { it.ficha.loteUuid }
        val base = lotesBase.orEmpty().associateBy { it.uuid }

        val lotes = (porLote.keys + base.keys).map { uuid ->
            val ortos = porLote[uuid].orEmpty().sortedByDescending { it.ficha.vueloInicioMs }
            LoteHistorial(
                uuid = uuid,
                nombre = base[uuid]?.nombre ?: ortos.first().ficha.loteNombre,
                areaHa = base[uuid]?.areaHa,
                ortomosaicos = ortos
            )
        }.sortedWith(
            compareByDescending<LoteHistorial> { it.ultimo?.ficha?.vueloInicioMs ?: Long.MIN_VALUE }.thenBy { it.nombre.lowercase() }
        )
        return Biblioteca(lotes, aviso)
    }
}

/** Todo lo que necesitan las dos pestañas, leído de una vez para que se vea coherente. */
data class Carga(val biblioteca: Biblioteca, val vuelos: List<VueloArmado>, val espacioLibreBytes: Long, val armadoNoDisponible: String?)

object Cargas {

    /**
     * Lee de disco y de la base lo que muestran Creados y Crear. Bloqueante: llamar fuera del hilo de UI. Si la base no responde igual se
     * muestran los ortomosaicos guardados en esta PC, con un aviso. [activos] son los vuelos que se están armando ahora mismo.
     */
    fun leer(usuarioId: UUID, activos: Set<String> = Armado.activos()): Carga {
        val store = OrtomosaicoStore(DataFolder.ortomosaicos)
        store.mantenimiento(activos)
        val entradas = store.listar()
        val base = OrtomosaicoDb.lotes(usuarioId)
        val biblioteca = Bibliotecas.construir(
            entradas, base.getOrNull(), usuarioId.toString(),
            aviso = base.exceptionOrNull()?.let {
                "No se pudo leer la base de datos, así que solo se ven los ortomosaicos guardados en esta PC y no los lotes que aún no tienen ninguno. (${OrtomosaicoDb.motivo(it)})"
            }
        )
        return Carga(
            biblioteca,
            VuelosParaArmar.leer(FlightLogStore(DataFolder.vuelos), entradas),
            OrtomosaicoStore.espacioLibre(DataFolder.current.value),
            Armado.noDisponible()
        )
    }
}
