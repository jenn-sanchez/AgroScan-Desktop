package org.agroscan.project.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Qué pantalla del menú se muestra y qué piden unas pantallas a otras. Vive aquí y no dentro de `Home()` para que una pantalla pueda
 * llevar a otra (p. ej. Procesamiento → «Crear ortomosaico») y para que, al volver a una pantalla, siga donde se dejó.
 */
object Navegacion {

    const val INICIO = "Inicio"
    const val PROCESAMIENTO = "Procesamiento"
    const val ORTOMOSAICOS = "Ortomosaicos"

    enum class Pestana { CREADOS, CREAR }

    /** Lo que muestra Ortomosaicos: la pestaña y, si hay, el lote y el vuelo elegidos. */
    data class VistaOrtomosaicos(val pestana: Pestana = Pestana.CREADOS, val loteUuid: String? = null, val vueloUuid: String? = null)

    private val _pantalla = MutableStateFlow(INICIO)
    val pantalla: StateFlow<String> = _pantalla

    private val _ortomosaicos = MutableStateFlow(VistaOrtomosaicos())
    val ortomosaicos: StateFlow<VistaOrtomosaicos> = _ortomosaicos

    fun ir(pantalla: String) {
        _pantalla.value = pantalla
    }

    /** Abre Ortomosaicos → Crear con ese vuelo ya elegido. */
    fun crearOrtomosaico(vueloUuid: String) {
        _ortomosaicos.update { it.copy(pestana = Pestana.CREAR, vueloUuid = vueloUuid) }
        ir(ORTOMOSAICOS)
    }

    /** Abre Ortomosaicos → Creados, en el historial de [loteUuid] (o el que ya estaba elegido). */
    fun verOrtomosaicos(loteUuid: String? = null) {
        _ortomosaicos.update { it.copy(pestana = Pestana.CREADOS, loteUuid = loteUuid ?: it.loteUuid) }
        ir(ORTOMOSAICOS)
    }

    fun elegirPestana(pestana: Pestana) = _ortomosaicos.update { it.copy(pestana = pestana) }

    fun elegirLote(loteUuid: String?) = _ortomosaicos.update { it.copy(loteUuid = loteUuid) }

    fun elegirVuelo(vueloUuid: String?) = _ortomosaicos.update { it.copy(vueloUuid = vueloUuid) }
}
