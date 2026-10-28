package org.agroscan.project.features.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos

/** Último paquete de telemetría del teléfono. Un campo nulo = el teléfono no tenía ese dato. Formato en docs/PROTOCOLO_PC.md del proyecto del dron. */
data class LiveTelemetry(
    val tMs: Long,
    /** Hora de la PC a la que llegó (sirve para saber si dejó de llegar). */
    val recibidoMs: Long,
    val lat: Double?,
    val lon: Double?,
    val altM: Double,
    val velMs: Double,
    val rumbo: Double?,
    val bat: Int,
    val sat: Int,
    val enlace: Int,
    val fase: String,
    val volando: Boolean,
    val wp: Int,
    val wpTotal: Int,
    val avance: Double,
    val distM: Double,
    val grabando: Boolean,
    val fotos: Int,
    val tVueloS: Long,
    /** El teléfono está conectado al dron; con false el resto de datos del dron son valores por defecto y no deben mostrarse. */
    val dron: Boolean = true
)

/** Fases en que la salida está en marcha (las mismas que "running" en la app del dron). */
private val MISSION_PHASES = setOf("PREPARING", "TAKING_OFF", "CLIMBING", "FLYING", "PAUSED")

/** El dron está ejecutando una salida de misión (no solo volando). */
val LiveTelemetry.enMision: Boolean get() = fase in MISSION_PHASES

/**
 * La misión de la salida preparada o en curso, tal como la manda el teléfono (se repite cada pocos segundos): nombre, número de salida,
 * altura, modo de captura ("video" o "foto"), punto de origen y la ruta completa. Los campos que no lleguen quedan en su valor vacío.
 */
data class LivePlan(
    val mision: String,
    val ruta: List<Pair<Double, Double>>,
    val recibidoMs: Long,
    val salida: Int = 0,
    val salidas: Int = 0,
    val alturaM: Double = 0.0,
    val modo: String = "",
    val home: Pair<Double, Double>? = null
)

/** Contorno del lote de la misión en curso (lat, lon en orden de captura). */
data class LiveLot(val perimetro: List<Pair<Double, Double>>, val recibidoMs: Long)

/**
 * Recibe por UDP la telemetría que manda la app del dron por el Wi-Fi de esta PC y la deja en [live], [plan], [lote] y [trail] para la
 * pantalla de Telemetría. UDP no tiene conexión: "hay señal" significa que llegó un paquete hace menos de [STALE_MS].
 *
 * Seguridad, por capas:
 * - El puerto se abre **solo en la IP de la zona móvil** ([HOTSPOT_IP]), no en todas las redes de la PC: desde el Wi-Fi de la casa,
 *   la oficina o un café el puerto no existe. Mientras la Red Local está apagada no hay puerto abierto.
 * - El Firewall solo lo deja pasar desde esa red y para este programa ([TelemetryFirewall]).
 * - Además se descartan los paquetes cuyo origen no sea esa red ([isTrustedSource]).
 * - El protocolo no lleva órdenes: la PC solo lee y muestra. Lo peor que puede hacer un paquete falso es mostrar datos falsos.
 */
object TelemetryReceiver {

    const val PORT = 8765

    /** IP de esta PC en su zona móvil (la misma de LocalHotspot.PC_IP): el único lugar donde se escucha. */
    const val HOTSPOT_IP = "192.168.137.1"

    /** Prefijo de la red de la zona móvil de Windows. */
    private const val HOTSPOT_PREFIX = "192.168.137."

    /** Cada cuánto se revisa si la Red Local se encendió o se apagó. */
    private const val CHECK_MS = 2_000L

    /** Sin paquetes durante este tiempo se considera que no hay señal del teléfono. */
    const val STALE_MS = 3_000L

    private const val PLAN_STALE_MS = 15_000L
    private const val MAX_TRAIL_POINTS = 20_000
    private const val MIN_TRAIL_STEP_M = 1.0

    private val _live = MutableStateFlow<LiveTelemetry?>(null)
    val live: StateFlow<LiveTelemetry?> = _live

    private val _plan = MutableStateFlow<LivePlan?>(null)
    val plan: StateFlow<LivePlan?> = _plan

    private val _lote = MutableStateFlow<LiveLot?>(null)
    val lote: StateFlow<LiveLot?> = _lote

    /** Por donde ha pasado el dron en esta salida (lat, lon); se vacía al empezar otra. */
    private val _trail = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val trail: StateFlow<List<Pair<Double, Double>>> = _trail

    /** Motivo por el que no se pudo abrir el puerto (otro programa lo usa, firewall...); null si está escuchando. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Volatile private var started = false

    /**
     * Empieza a escuchar en un hilo propio. Llamarla de nuevo no hace nada. Espera a que exista la IP de la zona móvil (Red Local
     * encendida), abre el puerto solo en ella y lo suelta si la Red Local se apaga. [listenAddress] solo se cambia en las pruebas.
     */
    fun start(listenAddress: () -> InetAddress? = ::hotspotAddress) {
        if (started) return
        started = true
        thread(isDaemon = true, name = "telemetria-udp") { listen(listenAddress) }
    }

    private fun listen(listenAddress: () -> InetAddress?) {
        val buffer = ByteArray(65_535)
        while (true) {
            val address = listenAddress()
            if (address == null) {   // Red Local apagada: no hay nada que escuchar
                _error.value = null
                Thread.sleep(CHECK_MS)
                continue
            }
            try {
                DatagramSocket(InetSocketAddress(address, PORT)).use { socket ->
                    socket.soTimeout = CHECK_MS.toInt()
                    _error.value = null
                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            if (listenAddress() == null) break   // se apagó la Red Local: se suelta el puerto
                            continue
                        }
                        if (!isTrustedSource(packet.address)) continue
                        // Un paquete que provoque un error inesperado se descarta: nunca debe apagar el receptor
                        runCatching { handle(String(packet.data, 0, packet.length, Charsets.UTF_8), System.currentTimeMillis()) }
                    }
                }
            } catch (e: IOException) {
                // Si la Red Local se apagó en medio, no es un error; si no (otro programa usa el puerto), se avisa y se reintenta
                if (listenAddress() != null) _error.value = "No se pudo escuchar el puerto UDP $PORT: ${e.message}"
                Thread.sleep(CHECK_MS)
            }
        }
    }

    /** La IP de la zona móvil si esta PC la tiene ahora (Red Local encendida); null si no. */
    internal fun hotspotAddress(): InetAddress? = runCatching {
        InetAddress.getByName(HOTSPOT_IP).takeIf { NetworkInterface.getByInetAddress(it) != null }
    }.getOrNull()

    /** Procesa un paquete. Los que no son JSON válido o no traen lo esperado se ignoran. */
    internal fun handle(text: String, now: Long) {
        val json = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return
        when (json["tipo"]) {
            "tel" -> parseTelemetry(json, now)?.let(::accept)
            "plan" -> parsePlan(json, now)?.let { _plan.value = it }
            "lote" -> parseLote(json, now)?.let { _lote.value = it }
        }
    }

    /**
     * Solo se aceptan paquetes de la red de la zona móvil (192.168.137.x). El loopback solo puede llegar en las pruebas, que escuchan
     * en 127.0.0.1; en la app el puerto está en [HOTSPOT_IP] y ahí no llega nada de 127.0.0.1.
     */
    internal fun isTrustedSource(address: InetAddress): Boolean =
        address.isLoopbackAddress || (address is Inet4Address && address.hostAddress.startsWith(HOTSPOT_PREFIX))

    private fun accept(t: LiveTelemetry) {
        val prev = _live.value
        if (prev != null && t.tMs <= prev.tMs) return   // llegó tarde o repetido
        // Salida nueva (o despegue manual): se empieza una estela limpia
        if ((t.enMision && prev?.enMision != true) || (t.volando && prev?.volando != true)) _trail.value = emptyList()
        if (t.lat != null && t.lon != null && (t.enMision || t.volando)) {
            val trail = _trail.value
            val last = trail.lastOrNull()
            if (last == null || metersBetween(last.first, last.second, t.lat, t.lon) >= MIN_TRAIL_STEP_M) {
                _trail.value = (if (trail.size >= MAX_TRAIL_POINTS) trail.drop(1) else trail) + (t.lat to t.lon)
            }
        }
        _live.value = t
    }

    /** La misión en curso solo cuenta si su paquete es reciente. */
    fun currentPlan(now: Long): LivePlan? = _plan.value?.takeIf { now - it.recibidoMs <= PLAN_STALE_MS }

    internal fun parseTelemetry(j: Map<*, *>, now: Long): LiveTelemetry? {
        fun num(k: String) = j[k] as? Double
        return LiveTelemetry(
            tMs = num("t_ms")?.toLong() ?: return null,
            recibidoMs = now,
            lat = num("lat"), lon = num("lon"),
            altM = num("alt_m") ?: 0.0, velMs = num("vel_ms") ?: 0.0, rumbo = num("rumbo"),
            bat = num("bat")?.toInt() ?: 0, sat = num("sat")?.toInt() ?: 0, enlace = num("enlace")?.toInt() ?: 0,
            fase = j["fase"] as? String ?: "", volando = j["volando"] as? Boolean ?: false,
            wp = num("wp")?.toInt() ?: 0, wpTotal = num("wp_total")?.toInt() ?: 0,
            avance = num("avance") ?: 0.0, distM = num("dist_m") ?: 0.0,
            grabando = j["grabando"] as? Boolean ?: false, fotos = num("fotos")?.toInt() ?: 0, tVueloS = num("t_vuelo_s")?.toLong() ?: 0,
            dron = j["dron"] as? Boolean ?: true
        )
    }

    /** Lista plana `[lat1, lon1, lat2, lon2, …]` → pares; null si falta o trae algo que no es número. */
    private fun pairs(value: Any?): List<Pair<Double, Double>>? {
        val flat = (value as? List<*>)?.map { it as? Double ?: return null } ?: return null
        return flat.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
    }

    internal fun parsePlan(j: Map<*, *>, now: Long): LivePlan? = LivePlan(
        mision = j["mision"] as? String ?: "",
        ruta = pairs(j["ruta"]) ?: return null,
        recibidoMs = now,
        salida = (j["salida"] as? Double)?.toInt() ?: 0,
        salidas = (j["salidas"] as? Double)?.toInt() ?: 0,
        alturaM = j["altura_m"] as? Double ?: 0.0,
        modo = j["modo"] as? String ?: "",
        home = pairs(j["home"])?.firstOrNull()   // el origen es opcional: si viene mal, solo falta el origen
    )

    internal fun parseLote(j: Map<*, *>, now: Long): LiveLot? = LiveLot(pairs(j["perimetro"]) ?: return null, now)

    /** Solo para las pruebas: deja el receptor como recién abierto. */
    internal fun reset() {
        _live.value = null
        _plan.value = null
        _lote.value = null
        _trail.value = emptyList()
    }

    /** Distancia aproximada entre dos puntos cercanos (plano local); suficiente para decidir si el dron se movió. */
    internal fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val metersPerDegree = 111_320.0
        val dy = (lat2 - lat1) * metersPerDegree
        val dx = (lon2 - lon1) * metersPerDegree * cos(Math.toRadians((lat1 + lat2) / 2))
        return Math.hypot(abs(dx), abs(dy))
    }
}
