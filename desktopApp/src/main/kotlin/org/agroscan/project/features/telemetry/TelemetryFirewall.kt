package org.agroscan.project.features.telemetry

import java.security.MessageDigest
import java.util.Base64

/**
 * Regla del Firewall de Windows que deja llegar la telemetría del teléfono, lo más estrecha posible:
 * solo UDP [TelemetryReceiver.PORT], solo en la IP de la zona móvil, solo desde equipos de esa red y solo para este programa.
 *
 * La crea la app al encender la Red Local. Cambiar el Firewall exige permisos de administrador, así que Windows muestra **una sola**
 * confirmación ("¿Permitir que esta aplicación haga cambios?"); nunca se cambia en silencio. Si la regla ya existe no se vuelve a preguntar.
 *
 * El nombre de la regla lleva una huella de la ruta del programa ("AgroScan telemetria 1a2b3c4d"): así saber si existe es una consulta
 * por nombre a `netsh` (rápida, sin depender del idioma de Windows ni de tildes en la ruta) y, si AgroScan cambia de lugar (otra
 * instalación, otro JDK en desarrollo), la huella cambia y la regla se rehace. Si alguien desactiva la regla a mano, la app no lo nota.
 */
object TelemetryFirewall {

    const val RULE_PREFIX = "AgroScan telemetria"

    /** Programa que recibe los paquetes: AgroScan.exe instalado, o el java.exe con el que corre en desarrollo. */
    private val program: String? = ProcessHandle.current().info().command().orElse(null)

    /** true si ya existe la regla para este programa. Leer reglas no pide permisos. Bloqueante (~0,2 s). */
    fun exists(): Boolean {
        val prog = program ?: return false
        val proc = ProcessBuilder("netsh", "advfirewall", "firewall", "show", "rule", "name=${ruleName(prog)}")
            .redirectErrorStream(true).start()
        proc.inputStream.readAllBytes()
        return proc.waitFor() == 0   // netsh devuelve 1 si no hay ninguna regla con ese nombre
    }

    /** Crea (o rehace) la regla si falta. Windows pide confirmación; si el usuario dice que no, devuelve un error. Bloqueante. */
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
            "Sin permiso en el Firewall: el teléfono no podrá enviar la telemetría. Apaga y enciende la Red Local para volver a intentarlo."
        }
    }

    /** Nombre de la regla para [program]: el prefijo y una huella de su ruta (Windows no distingue mayúsculas en rutas). */
    internal fun ruleName(program: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(program.lowercase().toByteArray())
        return "$RULE_PREFIX " + hash.take(4).joinToString("") { "%02x".format(it) }
    }

    /** Script (como administrador) que borra las reglas viejas de AgroScan y crea la de este programa. */
    internal fun ruleScript(program: String): String {
        val p = program.replace("'", "''")   // comilla simple escapada para PowerShell
        return "Get-NetFirewallRule -DisplayName '$RULE_PREFIX*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule\n" +
            "New-NetFirewallRule -DisplayName '${ruleName(program)}' -Direction Inbound -Action Allow -Protocol UDP " +
            "-LocalPort ${TelemetryReceiver.PORT} -LocalAddress ${TelemetryReceiver.HOTSPOT_IP} -RemoteAddress 192.168.137.0/24 " +
            "-Program '$p' -Profile Any | Out-Null"
    }

    // -EncodedCommand evita cualquier problema de comillas, espacios o tildes en la ruta del programa
    private fun encode(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
}
