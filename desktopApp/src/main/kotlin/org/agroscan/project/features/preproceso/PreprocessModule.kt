package org.agroscan.project.features.preproceso

import java.io.File

/**
 * Dónde está el módulo de preprocesamiento de imágenes (Python, carpeta `preprocesamiento/` del repositorio) y cómo se ejecuta.
 * La app lo ejecuta por detrás, sin ventana ni servidor, con el contrato que el módulo documenta: `--video … --salida … --json`
 * escribe una línea JSON por evento en la salida estándar.
 *
 * - Programando (`./gradlew :desktopApp:run` o IntelliJ): el código de `preprocesamiento/` con el Python de su `venv`, que crea
 *   `./gradlew prepararPreproceso`. Así un cambio en el módulo se prueba sin volver a empaquetar.
 * - Instalado: `agroscan-preproceso.exe`, que el instalador trae en los recursos de la app con su propio Python y librerías.
 */
object PreprocessModule {

    /** Cómo se ejecuta: el programa con sus primeros argumentos, desde [carpeta]. */
    data class Modulo(val programa: List<String>, val carpeta: File)

    /** Carpeta de trabajo propia del módulo (su registro y carpetas vacías): instalado, la carpeta del programa no se puede escribir. */
    val trabajo = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "AgroScan/preproceso")

    /** El módulo de esta PC, o por qué no se puede usar. */
    fun ubicar(): Result<Modulo> {
        fuente()?.let { dir ->
            val python = File(dir, "venv/Scripts/python.exe")
            return if (python.isFile) Result.success(Modulo(listOf(python.path, "main.py"), dir))
            else Result.failure(IllegalStateException("Falta el entorno de Python del módulo: ejecuta ./gradlew prepararPreproceso (ver README)"))
        }
        val exe = System.getProperty("compose.application.resources.dir")?.let { File(it, "agroscan-preproceso/agroscan-preproceso.exe") }
        return if (exe != null && exe.isFile) Result.success(Modulo(listOf(exe.path), exe.parentFile))
        else Result.failure(IllegalStateException("No se encontró el módulo de preprocesamiento; reinstala AgroScan"))
    }

    /** La carpeta `preprocesamiento/` del repositorio si la app corre desde él (la busca subiendo desde la carpeta actual). */
    private fun fuente(): File? = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "preprocesamiento") }
        .firstOrNull { File(it, "main.py").isFile }

    /** El proceso que preprocesa un video. Sin `--servidor`: todo queda en esta PC, no se envía nada a ningún lado. */
    fun proceso(modulo: Modulo, video: File, salida: File, mision: String): ProcessBuilder =
        ProcessBuilder(modulo.programa + listOf("--video", video.path, "--salida", salida.path, "--mision", mision, "--json"))
            .directory(modulo.carpeta)
            .apply {
                environment()["PYTHONIOENCODING"] = "utf-8"
                environment()["AGROSCAN_PREPROCESO_DATOS"] = trabajo.path   // lo lee src/config.py del módulo
            }
}
