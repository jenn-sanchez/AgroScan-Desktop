package org.agroscan.project.features.redlocal

import org.agroscan.project.features.flightlog.FlightLogServer
import org.agroscan.project.features.telemetry.TelemetryReceiver
import java.security.MessageDigest
import java.util.Base64

/**
 * Reglas del Firewall de Windows para lo que el teléfono manda por la Red Local, lo más estrechas posible: cada una abre un solo
 * puerto, solo en la IP de la zona móvil, solo desde equipos de esa red y solo para este programa.
 * - `AgroScan telemetria …`: UDP [TelemetryReceiver.PORT] (telemetría en vivo).
 * - `AgroScan registros …`: TCP [FlightLogServer.PORT] (registros de vuelo y marcas del video).
 *
 * Las crea la app al encender la Red Local. Cambiar el Firewall exige permisos de administrador, así que Windows muestra **una sola**
 * confirmación ("¿Permitir que esta aplicación haga cambios?") para las dos; nunca se cambia en silencio. Si ya existen no se vuelve
 * a preguntar.
 *
 * El nombre de cada regla lleva una huella de la ruta del programa ("AgroScan telemetria 1a2b3c4d"): así saber si existe es una
 * consulta por nombre a `netsh` (rápida, sin depender del idioma de Windows ni de tildes en la ruta) y, si AgroScan cambia de lugar
 * (otra instalación, otro JDK en desarrollo), la huella cambia y las reglas se rehacen. Si alguien desactiva una regla a mano, la app
 * no lo nota.
 */
object LocalFirewall {

    private data class Rule(val prefix: String, val protocol: String, val port: Int)

    private val RULES = listOf(
        Rule("AgroScan telemetria", "UDP", TelemetryReceiver.PORT),
        Rule("AgroScan registros", "TCP", FlightLogServer.PORT)
    )

    /** Programa que recibe los datos: AgroScan.exe instalado, o el java.exe con el que corre en desarrollo. */
    private val program: String? = ProcessHandle.current().info().command().orElse(null)

    /** true si ya existen todas las reglas para este programa. Leer reglas no pide permisos. Bloqueante (~0,2 s por regla). */
    fun exists(): Boolean {
        val prog = program ?: return false
        return RULES.all { rule ->
            val proc = ProcessBuilder("netsh", "advfirewall", "firewall", "show", "rule", "name=${ruleName(rule.prefix, prog)}")
                .redirectErrorStream(true).start()
            proc.inputStream.readAllBytes()
            proc.waitFor() == 0   // netsh devuelve 1 si no hay ninguna regla con ese nombre
        }
    }

    /** Crea (o rehace) las reglas si falta alguna. Windows pide confirmación; si el usuario dice que no, devuelve un error. Bloqueante. */
    fun ensure(): Result<Unit> = runCatching {
        if (exists()) return@runCatching
        val prog = program ?: error("No se pudo identificar el programa de AgroScan para el Firewall")
        // Solo el script interno corre como administrador; la ventana de Windows (UAC) es la única interacción
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand",
            encode(
                "Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden " +
                    "-ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','${encode(ruleScript(prog))}'"
            )
        ).redirectErrorStream(true).start()
        proc.inputStream.readAllBytes()
        proc.waitFor()
        check(exists()) {
            "Sin permiso en el Firewall: el teléfono no podrá enviar datos a la PC. Apaga y enciende la Red Local para volver a intentarlo."
        }
    }

    /** Nombre de una regla para [program]: el prefijo y una huella de su ruta (Windows no distingue mayúsculas en rutas). */
    internal fun ruleName(prefix: String, program: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(program.lowercase().toByteArray())
        return "$prefix " + hash.take(4).joinToString("") { "%02x".format(it) }
    }

    /** Script (como administrador) que borra las reglas viejas de AgroScan y crea las de este programa. */
    internal fun ruleScript(program: String): String {
        val p = program.replace("'", "''")   // comilla simple escapada para PowerShell
        return RULES.joinToString("\n") { r ->
            "Get-NetFirewallRule -DisplayName '${r.prefix}*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule\n" +
                "New-NetFirewallRule -DisplayName '${ruleName(r.prefix, program)}' -Direction Inbound -Action Allow -Protocol ${r.protocol} " +
                "-LocalPort ${r.port} -LocalAddress ${LocalHotspot.PC_IP} -RemoteAddress 192.168.137.0/24 " +
                "-Program '$p' -Profile Any | Out-Null"
        }
    }

    // -EncodedCommand evita cualquier problema de comillas, espacios o tildes en la ruta del programa
    private fun encode(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
}
