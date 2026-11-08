package org.agroscan.project.core

import java.io.File

/**
 * Un módulo de Python que la app ejecuta por detrás (preprocesamiento, ortomosaicos): el programa con sus primeros argumentos, y la
 * carpeta desde donde se ejecuta.
 *
 * - Programando (`./gradlew :desktopApp:run` o IntelliJ): el código de la carpeta del módulo, en la raíz del repositorio, con el Python
 *   de su `venv` (que crea la tarea de Gradle del módulo). Así un cambio en el módulo se prueba sin volver a empaquetar.
 * - Instalado: un `.exe` que el instalador trae en los recursos de la app con su propio Python y librerías.
 */
data class ModuloPython(val programa: List<String>, val carpeta: File) {

    companion object {
        /**
         * El módulo de esta PC, o por qué no se puede usar.
         * @param carpeta nombre de la carpeta del módulo en el repositorio, que también es la de sus recursos instalados con `$exe`
         * @param exe nombre del ejecutable instalado, sin `.exe`
         * @param tarea tarea de Gradle que prepara su entorno de Python
         * @param nombre cómo se llama el módulo en los mensajes
         */
        fun ubicar(carpeta: String, exe: String, tarea: String, nombre: String): Result<ModuloPython> {
            fuente(carpeta)?.let { dir ->
                val python = File(dir, "venv/Scripts/python.exe")
                return if (python.isFile) Result.success(ModuloPython(listOf(python.path, "main.py"), dir))
                else Result.failure(IllegalStateException("Falta el entorno de Python del módulo de $nombre: ejecuta ./gradlew $tarea (ver README)"))
            }
            val instalado = System.getProperty("compose.application.resources.dir")?.let { File(it, "$exe/$exe.exe") }
            return if (instalado != null && instalado.isFile) Result.success(ModuloPython(listOf(instalado.path), instalado.parentFile))
            else Result.failure(IllegalStateException("No se encontró el módulo de $nombre; reinstala AgroScan"))
        }

        /** La carpeta [nombre] del repositorio si la app corre desde él (la busca subiendo desde la carpeta actual). */
        private fun fuente(nombre: String): File? = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, nombre) }
            .firstOrNull { File(it, "main.py").isFile }
    }
}
