package org.agroscan.project.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.prefs.Preferences
import javax.swing.filechooser.FileSystemView

/**
 * Carpeta donde la app guarda los datos de cada vuelo (registros del teléfono, videos de la microSD y lo que generen los módulos
 * de procesamiento). Por defecto `Documentos\AgroScan` (la carpeta Documentos real de Windows, también si está en OneDrive); el
 * usuario puede elegir otra (p. ej. un disco con más espacio) y queda guardada en esta PC. Cambiarla no mueve lo ya guardado.
 */
object DataFolder {

    private const val CLAVE = "carpetaDatos"
    private val prefs = Preferences.userRoot().node("org/agroscan/desktop")

    val default: File get() = File(FileSystemView.getFileSystemView().defaultDirectory, "AgroScan")

    private val _current = MutableStateFlow(prefs.get(CLAVE, null)?.let(::File) ?: default)
    val current: StateFlow<File> = _current

    /** `<carpeta>\vuelos`: una subcarpeta por vuelo. */
    val vuelos: File get() = File(current.value, "vuelos")

    fun set(dir: File) {
        prefs.put(CLAVE, dir.absolutePath)
        prefs.flush()
        _current.value = dir
    }
}
