package org.agroscan.project.features.preproceso

import org.agroscan.project.core.ModuloPython
import java.io.File

/**
 * Dónde está el módulo de preprocesamiento de imágenes (Python, carpeta `preprocesamiento/` del repositorio) y cómo se ejecuta
 * (ver [ModuloPython]). La app lo ejecuta por detrás, sin ventana ni servidor, con el contrato que el módulo documenta:
 * `--video … --salida … --json` escribe una línea JSON por evento en la salida estándar.
 */
object PreprocessModule {

    /** Carpeta de trabajo propia del módulo (su registro y carpetas vacías): instalado, la carpeta del programa no se puede escribir. */
    val trabajo = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "AgroScan/preproceso")

    /** El módulo de esta PC, o por qué no se puede usar. */
    fun ubicar(): Result<ModuloPython> = ModuloPython.ubicar("preprocesamiento", "agroscan-preproceso", "prepararPreproceso", "preprocesamiento")

    /** El proceso que preprocesa un video. Sin `--servidor`: todo queda en esta PC, no se envía nada a ningún lado. */
    fun proceso(modulo: ModuloPython, video: File, salida: File, mision: String): ProcessBuilder =
        ProcessBuilder(modulo.programa + listOf("--video", video.path, "--salida", salida.path, "--mision", mision, "--json"))
            .directory(modulo.carpeta)
            .apply {
                environment()["PYTHONIOENCODING"] = "utf-8"
                environment()["AGROSCAN_PREPROCESO_DATOS"] = trabajo.path   // lo lee src/config.py del módulo
            }
}
