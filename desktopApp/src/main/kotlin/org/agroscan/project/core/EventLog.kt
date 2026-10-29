package org.agroscan.project.core

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Registro de lo que pasa con la Red Local y el enlace con el teléfono, para revisarlo después de volar: una línea por evento en
 * `<carpeta de datos>\logs\pc-AAAA-MM-DD.log` (un archivo por día). Los registros del teléfono llegan a `logs\telefono\`.
 * Nunca falla hacia afuera: si no se puede escribir (disco lleno, carpeta sin permiso), se ignora.
 */
object EventLog {

    private val hora = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** Las pruebas lo apagan (build.gradle.kts) para no escribir en la carpeta de datos real. */
    private val enabled = System.getProperty("agroscan.eventlog") != "off"

    val dir: File get() = File(DataFolder.current.value, "logs")

    @Synchronized
    fun log(tema: String, mensaje: String) {
        if (!enabled) return
        runCatching {
            val now = LocalDateTime.now()
            dir.mkdirs()
            File(dir, "pc-${now.toLocalDate()}.log").appendText("${now.format(hora)} [$tema] $mensaje\n", Charsets.UTF_8)
        }
    }
}
