package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.DataFolder
import org.agroscan.project.core.EventLog

/** Lo que pasó al eliminar: cuánto se liberó y, si la base no se pudo actualizar, por qué (los archivos se borraron igual). */
data class Eliminado(val liberadoBytes: Long, val avisoBase: String?) {
    /** La frase que ve el usuario, con lo que importa: que se borró, cuánto se liberó y, si hace falta, lo que no salió bien. */
    fun mensaje(ficha: Ficha): String = buildString {
        append("Ortomosaico del ${Formato.fecha(ficha.vueloInicioMs)} eliminado. Se liberaron ${Formato.bytes(liberadoBytes)}.")
        if (avisoBase != null) append(" Pero no se pudo actualizar la base de datos: $avisoBase.")
    }
}

object OrtomosaicoAcciones {

    /**
     * Elimina un ortomosaico: sus archivos de esta PC y su fila de la base. No toca los videos ni los registros del vuelo. Lo de la
     * base es de mejor esfuerzo ([quitarDeBase]): si falla no se deshace el borrado, se avisa. Bloqueante: llamar fuera del hilo de UI.
     */
    fun eliminar(
        entrada: Entrada,
        store: OrtomosaicoStore = OrtomosaicoStore(DataFolder.ortomosaicos),
        quitarDeBase: (String) -> Result<Unit> = OrtomosaicoDb::eliminar
    ): Result<Eliminado> = store.eliminar(entrada).map { liberado ->
        val base = quitarDeBase(entrada.ficha.vueloUuid)
        base.exceptionOrNull()?.let { EventLog.log("ortomosaico", "No se pudo quitar de la base ${entrada.ficha.vueloUuid}: ${it.message}") }
        Eliminado(liberado, base.exceptionOrNull()?.let(OrtomosaicoDb::motivo))
    }
}

/** Comparar dos fechas de un mismo polígono. */
object Comparacion {

    /** (antes, después): la más antigua primero, sea cual sea el orden en que se eligieron. */
    fun ordenar(a: Entrada, b: Entrada): Pair<Entrada, Entrada> =
        if (a.ficha.vueloInicioMs <= b.ficha.vueloInicioMs) a to b else b to a

    /** Qué parte de la zona más pequeña cubren las dos a la vez, en %: 100 = una está dentro de la otra; 0 = no se tocan. */
    fun solapePct(a: Limites, b: Limites): Double {
        val ancho = minOf(a.este, b.este) - maxOf(a.oeste, b.oeste)
        val alto = minOf(a.norte, b.norte) - maxOf(a.sur, b.sur)
        if (ancho <= 0 || alto <= 0) return 0.0
        val menor = minOf((a.este - a.oeste) * (a.norte - a.sur), (b.este - b.oeste) * (b.norte - b.sur))
        return if (menor <= 0) 0.0 else (ancho * alto / menor * 100).coerceIn(0.0, 100.0)
    }

    /** Lo que conviene saber antes de fiarse de la comparación: resolución distinta, zonas que no coinciden, ubicación dudosa. */
    fun avisos(antes: Ficha, despues: Ficha): List<String> = buildList {
        val ga = antes.gsdCmPx
        val gd = despues.gsdCmPx
        if (ga != null && gd != null && ga > 0 && gd > 0 && kotlin.math.abs(ga - gd) / minOf(ga, gd) > 0.25) {
            add("Tienen resoluciones distintas (${Formato.resolucion(ga)} y ${Formato.resolucion(gd)}): los detalles finos no son comparables.")
        }
        val la = antes.limites
        val ld = despues.limites
        if (la != null && ld != null) {
            val solape = solapePct(la, ld)
            if (solape < 80) add("Solo ${Formato.porcentaje(solape)} de la zona más pequeña aparece en las dos fechas.")
        }
        listOfNotNull(antes.errorEstimadoM, despues.errorEstimadoM).maxOrNull()?.takeIf { it > MAX_ERROR_FIABLE_M }?.let {
            add("La ubicación puede variar hasta ±${"%.0f".format(it)} m entre fechas: no compares por píxel.")
        }
    }

    /** Por encima de esto el corrimiento entre fechas se nota a simple vista (a 4 cm por píxel, 5 m son 125 píxeles). */
    const val MAX_ERROR_FIABLE_M = 5.0
}
