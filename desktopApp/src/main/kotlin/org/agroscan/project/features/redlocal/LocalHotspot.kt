package org.agroscan.project.features.redlocal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
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

    /**
     * [PC_IP] si esta PC la tiene ahora (Red Local encendida, también si se encendió desde Windows); null si no. Los receptores
     * de la red local abren sus puertos solo en esta IP.
     */
    fun address(): InetAddress? = runCatching {
        InetAddress.getByName(PC_IP).takeIf { NetworkInterface.getByInetAddress(it) != null }
    }.getOrNull()

    /**
     * true si [address] es un equipo de la red de la zona móvil (192.168.137.x): lo único que aceptan los receptores de la red local.
     * El loopback solo puede llegar en las pruebas, que escuchan en 127.0.0.1; en la app los puertos están en [PC_IP] y ahí no
     * llega nada de 127.0.0.1.
     */
    fun isLocalNetwork(address: InetAddress): Boolean =
        address.isLoopbackAddress || (address is Inet4Address && address.hostAddress.startsWith(PC_IP.substringBeforeLast('.') + "."))

    private val _phoneConnected = MutableStateFlow(false)
    /** true cuando al menos un dispositivo (el teléfono) está conectado a la red de la PC. */
    val phoneConnected: StateFlow<Boolean> = _phoneConnected

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poller: Job? = null

    init {
        // Si la app se cierra con la red encendida, la apagamos para no dejarla abierta.
        Runtime.getRuntime().addShutdownHook(Thread { if (isOn) runCatching { stop() } })
    }

    /** Bloqueante (~2-5 s): llamar fuera del hilo de UI. */
    fun start(): Result<Unit> = run("start").map { }.onSuccess {
        isOn = true
        poller?.cancel()
        // Cada consulta lanza PowerShell (~1 s), por eso se pregunta cada 5 s y solo con la red encendida.
        poller = scope.launch {
            while (isActive) {
                _phoneConnected.value = run("count").getOrNull()?.toIntOrNull()?.let { it > 0 } ?: false
                delay(5_000)
            }
        }
    }

    fun stop(): Result<Unit> = run("stop").map { }.onSuccess {
        isOn = false
        poller?.cancel()
        _phoneConnected.value = false
    }

    /** Devuelve lo que el script imprime después de "OK". */
    private fun run(action: String): Result<String> = runCatching {
        val script = SCRIPT.replace("__ACTION__", action).replace("__SSID__", SSID).replace("__PASS__", password)
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        proc.waitFor()
        val result = out.lines().lastOrNull { it.startsWith("OK") || it.startsWith("ERR:") }
        check(result != null && result.startsWith("OK")) { result?.removePrefix("ERR:")?.trim() ?: out.trim().ifEmpty { "Sin respuesta de Windows" } }
        result.removePrefix("OK").trim()
    }

    private val SCRIPT = """
        ${'$'}ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = [Text.Encoding]::UTF8
        try {
            if (-not (Get-NetAdapter -Physical | Where-Object { ${'$'}_.NdisPhysicalMedium -eq 9 })) {
                throw 'Esta computadora no tiene tarjeta Wi-Fi (o está desactivada/sin driver). Se necesita Wi-Fi para crear la red local.'
            }
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

            if ('__ACTION__' -eq 'count') {
                "OK ${'$'}(${'$'}tm.ClientCount)"
                return
            }
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
