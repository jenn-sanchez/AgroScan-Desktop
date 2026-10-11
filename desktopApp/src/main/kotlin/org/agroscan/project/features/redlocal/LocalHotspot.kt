package org.agroscan.project.features.redlocal

import java.security.SecureRandom
import java.util.Base64

/**
 * Enciende la "Zona con cobertura inalámbrica móvil" de Windows para que el teléfono se conecte a la PC sin internet.
 * Si no hay internet, Windows la acepta igual usando cualquier perfil de red guardado como origen.
 */
object LocalHotspot {
    const val SSID = "AgroScan"
    const val PC_IP = "192.168.137.1" // IP fija que Windows asigna a la PC en su zona móvil

    // Sin letras/números ambiguos para dictarla fácil; solo [a-z2-9], así es seguro meterla en el script.
    val password: String = SecureRandom().let { r -> (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)] }.joinToString("") }

    @Volatile var isOn = false
        private set

    init {
        // Si la app se cierra con la red encendida, la apagamos para no dejarla abierta.
        Runtime.getRuntime().addShutdownHook(Thread { if (isOn) runCatching { stop() } })
    }

    /** Bloqueante (~2-5 s): llamar fuera del hilo de UI. */
    fun start(): Result<Unit> = run("start").onSuccess { isOn = true }

    fun stop(): Result<Unit> = run("stop").onSuccess { isOn = false }

    private fun run(action: String): Result<Unit> = runCatching {
        val script = SCRIPT.replace("__ACTION__", action).replace("__SSID__", SSID).replace("__PASS__", password)
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val result = out.lines().lastOrNull { it.startsWith("OK") || it.startsWith("ERR:") }
        check(result == "OK") { result?.removePrefix("ERR:")?.trim() ?: out.trim().ifEmpty { "Sin respuesta de Windows" } }
    }

    private val SCRIPT = """
        ${'$'}ErrorActionPreference = 'Stop'
        try {
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            ${'$'}ext = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 }
            ${'$'}opAsTask = ${'$'}ext | Where-Object { ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
            ${'$'}actAsTask = ${'$'}ext | Where-Object { ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' } | Select-Object -First 1
            ${'$'}null = [Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType = WindowsRuntime]
            ${'$'}null = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager, Windows.Networking.NetworkOperators, ContentType = WindowsRuntime]
            ${'$'}null = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringAccessPointConfiguration, Windows.Networking.NetworkOperators, ContentType = WindowsRuntime]
            ${'$'}resultType = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult]

            ${'$'}profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
            if (-not ${'$'}profile) { ${'$'}profile = [Windows.Networking.Connectivity.NetworkInformation]::GetConnectionProfiles() | Select-Object -First 1 }
            if (-not ${'$'}profile) { throw 'No hay ningún perfil de red guardado. Conéctate una vez a cualquier Wi-Fi para crearlo.' }
            ${'$'}tm = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile(${'$'}profile)

            if ('__ACTION__' -eq 'start') {
                ${'$'}cfg = New-Object Windows.Networking.NetworkOperators.NetworkOperatorTetheringAccessPointConfiguration
                ${'$'}cfg.Ssid = '__SSID__'
                ${'$'}cfg.Passphrase = '__PASS__'
                ${'$'}actAsTask.Invoke(${'$'}null, @(${'$'}tm.ConfigureAccessPointAsync(${'$'}cfg))).Wait(-1) | Out-Null
                ${'$'}op = ${'$'}tm.StartTetheringAsync()
            } else {
                ${'$'}op = ${'$'}tm.StopTetheringAsync()
            }
            ${'$'}task = ${'$'}opAsTask.MakeGenericMethod(${'$'}resultType).Invoke(${'$'}null, @(${'$'}op))
            ${'$'}task.Wait(-1) | Out-Null
            if (${'$'}task.Result.Status -eq 'Success') { 'OK' } else { "ERR: ${'$'}(${'$'}task.Result.Status) ${'$'}(${'$'}task.Result.AdditionalErrorMessage)" }
        } catch {
            "ERR: ${'$'}(${'$'}_.Exception.Message)"
        }
    """.trimIndent()
}
