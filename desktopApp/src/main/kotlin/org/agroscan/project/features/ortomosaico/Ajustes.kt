package org.agroscan.project.features.ortomosaico

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.prefs.Preferences

/** Cuánto detalle se busca al armar: más detalle tarda más y ocupa más. */
enum class Calidad(val etiqueta: String, val ayuda: String) {
    RAPIDA("Rápida", "Menos detalle; termina antes. Sirve para revisar el vuelo."),
    ESTANDAR("Estándar", "El equilibrio recomendado."),
    ALTA("Alta", "Más detalle; tarda más y ocupa más disco.");

    val clave: String get() = name.lowercase()

    companion object {
        fun desde(clave: String?): Calidad? = entries.firstOrNull { it.clave == clave }
    }
}

/** De qué imágenes parte el mosaico. */
enum class OrigenImagenes(val etiqueta: String, val ayuda: String) {
    ORIGINALES("Originales", "Tal como salieron del video, sin retoques."),
    CORREGIDAS("Corregidas", "Con el brillo y la nitidez que ajustó el preprocesamiento.");

    val clave: String get() = name.lowercase()

    companion object {
        fun desde(clave: String?): OrigenImagenes? = entries.firstOrNull { it.clave == clave }
    }
}

/**
 * Lo último que el usuario eligió al armar, para no pedírselo cada vez. Se guarda en esta PC, junto a la carpeta de datos
 * ([org.agroscan.project.core.DataFolder]).
 */
object AjustesOrtomosaico {

    private val prefs = Preferences.userRoot().node("org/agroscan/desktop")

    private val _calidad = MutableStateFlow(Calidad.desde(prefs.get("ortoCalidad", null)) ?: Calidad.ESTANDAR)
    val calidad: StateFlow<Calidad> = _calidad

    private val _imagenes = MutableStateFlow(OrigenImagenes.desde(prefs.get("ortoImagenes", null)) ?: OrigenImagenes.ORIGINALES)
    val imagenes: StateFlow<OrigenImagenes> = _imagenes

    fun elegir(calidad: Calidad) {
        prefs.put("ortoCalidad", calidad.clave)
        _calidad.value = calidad
    }

    fun elegir(imagenes: OrigenImagenes) {
        prefs.put("ortoImagenes", imagenes.clave)
        _imagenes.value = imagenes
    }
}

/** Lo que el usuario pide al pulsar «Armar ortomosaico»: qué vuelo, qué salidas y con qué ajustes. */
data class Solicitud(val vueloUuid: String, val salidas: Set<Int>, val calidad: Calidad, val imagenes: OrigenImagenes)
