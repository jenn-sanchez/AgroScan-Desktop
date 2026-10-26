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

    fun connect(): Connection =
        DriverManager.getConnection(System.getenv(ENV) ?: error("Falta la variable de entorno $ENV"))

    val isLocal: Boolean get() = System.getenv(ENV).orEmpty().let { "//localhost" in it || "//127.0.0.1" in it }

    /** Nombre de la base si se pudo conectar. Bloqueante: llamar fuera del hilo de UI. */
    fun check(): Result<String> = runCatching { connect().use { it.catalog } }
}
