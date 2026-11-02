package org.agroscan.project.features.preproceso

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.prefs.Preferences
import javax.swing.filechooser.FileSystemView

/**
 * Dónde está el módulo de preprocesamiento de imágenes (Python, repositorio `agroscan-preprocesamiento`) y cómo se ejecuta.
 * La app lo ejecuta por detrás, sin ventana ni servidor, con el contrato que el módulo documenta: `main.py --video … --salida …
 * --json` escribe una línea JSON por evento en la salida estándar.
 *
 * shortcut: cada PC necesita el módulo descargado y su entorno de Python (`venv`); cuando el módulo esté estable, empaquetarlo como
 * .exe dentro del instalador de AgroScan y buscarlo aquí primero.
 */
object  PreprocessModule {

    private const val CLAVE = "moduloPreproceso"
    private val prefs = Preferences.userRoot().node("org/agroscan/desktop")

    /** Por defecto `Documentos\agroscan-preprocesamiento`, donde se descarga el repositorio. */
    val default: File get() = File(FileSystemView.getFileSystemView().defaultDirectory, "agroscan-preprocesamiento")

    private val _folder = MutableStateFlow(prefs.get(CLAVE, null)?.let(::File) ?: default)
    val folder: StateFlow<File> = _folder

    fun set(dir: File) {
        prefs.put(CLAVE, dir.absolutePath)
        prefs.flush()
        _folder.value = dir
    }

    /** Qué le falta a [dir] para poder usarse; null si está listo. */
    fun problema(dir: File): String? = when {
        !File(dir, "main.py").isFile -> "No se encontró el módulo en $dir (falta main.py). Descárgalo o elige su carpeta."
        else -> null
    }

    /** El Python del entorno del módulo (`venv`, con OpenCV y demás instalados) si existe; si no, el del sistema. */
    fun python(dir: File): String = File(dir, "venv/Scripts/python.exe").takeIf { it.isFile }?.path ?: "python"

    /** El comando para procesar un video. Sin `--servidor`: todo queda en esta PC, no se envía nada a ningún lado. */
    fun command(dir: File, video: File, salida: File, mision: String): List<String> =
        listOf(python(dir), "main.py", "--video", video.path, "--salida", salida.path, "--mision", mision, "--json")
}
