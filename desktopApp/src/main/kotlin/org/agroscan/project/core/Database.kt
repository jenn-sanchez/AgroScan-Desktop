package org.agroscan.project.core

import java.sql.Connection
import java.sql.DriverManager

/**
 * Conexión a PostgreSQL. La URL (con usuario y contraseña) viene de la variable de entorno [ENV] para que
 * la contraseña no quede en el código ni en git, p. ej.:
 * `jdbc:postgresql://localhost:5432/agroscan?user=postgres&password=...`
 */
object Database {
    const val ENV = "AGROSCAN_DB_URL"

    @Volatile private var fromWindows: String? = null

    /**
     * La URL de conexión: la del proceso o, si no llegó, la guardada en la cuenta de Windows. `setx` la guarda en la cuenta, pero los
     * programas que ya estaban abiertos (IntelliJ, el proceso de Gradle de fondo, el Explorador) no la ven hasta reiniciarse.
     */
    private fun url(): String? = System.getenv(ENV) ?: fromWindows ?: windowsUserVariable(ENV)?.also { fromWindows = it }

    fun connect(): Connection = DriverManager.getConnection(
        url() ?: error("Falta configurar la base de datos: no existe la variable $ENV en esta PC (ver docs/BASE_DE_DATOS.md)")
    )

    val isLocal: Boolean get() = url().orEmpty().let { "//localhost" in it || "//127.0.0.1" in it }

    /** Nombre de la base si se pudo conectar. Bloqueante: llamar fuera del hilo de UI. */
    fun check(): Result<String> = runCatching { connect().use { it.catalog } }

    /** Variable de entorno de la cuenta de Windows, leída en el momento (no la copia que recibió el proceso al abrirse). */
    internal fun windowsUserVariable(name: String): String? = runCatching {
        if (!System.getProperty("os.name").startsWith("Windows")) return null
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-Command",
            "[Console]::OutputEncoding = [Text.Encoding]::UTF8; [Environment]::GetEnvironmentVariable('$name', 'User')"
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        proc.waitFor()
        out.ifEmpty { null }
    }.getOrNull()
}
