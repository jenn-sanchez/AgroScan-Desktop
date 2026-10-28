package org.agroscan.project.features.flightlog

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.agroscan.project.core.DataFolder
import org.agroscan.project.core.MiniJson
import org.agroscan.project.features.redlocal.LocalHotspot
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * Recibe del teléfono, por HTTP en la Red Local, el registro de vuelo de cada salida (`registro.csv`) y sus eventos con las marcas
 * del video (`eventos.jsonl`), y los guarda con [FlightLogStore]. A diferencia de la telemetría (UDP), aquí cada envío se confirma:
 * una marca perdida dejaría mal ubicados los cuadros del video. Formato en docs/PROTOCOLO_PC.md del proyecto del dron.
 *
 * - `GET /salidas` → `{"salidas":["uuid", …]}`: las salidas que la PC ya tiene (el teléfono envía solo las que faltan).
 * - `POST /salidas/<uuid>` con el JSON de la salida y sus dos archivos → `{"ok":true,"nuevo":true|false}`.
 *
 * Como la telemetría: el puerto se abre **solo en la IP de la zona móvil** y solo mientras la Red Local está encendida, y solo se
 * aceptan equipos de esa red. Lo que llega solo se guarda como texto (nunca se ejecuta), cada salida se escribe una sola vez y el
 * cuerpo tiene un tamaño máximo.
 */
object FlightLogServer {

    const val PORT = 8766

    /** Una salida de 15 min son ~0,5 MB; esto deja mucho margen sin permitir que un envío llene la memoria. */
    private const val MAX_BODY = 64 * 1024 * 1024
    private const val CHECK_MS = 2_000L

    private val _received = MutableStateFlow(0L)
    /** Cambia cada vez que se guarda una salida nueva (para refrescar la pantalla de Procesamiento). */
    val received: StateFlow<Long> = _received

    private val _error = MutableStateFlow<String?>(null)
    /** Motivo por el que no se pudo abrir el puerto; null si está bien. */
    val error: StateFlow<String?> = _error

    @Volatile private var started = false

    /**
     * Empieza en un hilo propio: abre el servidor cuando aparece la IP de la Red Local y lo cierra si se apaga. Llamarla de nuevo
     * no hace nada. [listenAddress] y [store] solo se cambian en las pruebas.
     */
    fun start(
        listenAddress: () -> InetAddress? = LocalHotspot::address,
        store: () -> FlightLogStore = { FlightLogStore(DataFolder.vuelos) }
    ) {
        if (started) return
        started = true
        thread(isDaemon = true, name = "registros-http") {
            var server: HttpServer? = null
            while (true) {
                val address = listenAddress()
                if (address != null && server == null) {
                    server = runCatching {
                        HttpServer.create(InetSocketAddress(address, PORT), 0).apply {
                            createContext("/salidas") { handle(it, store()) }
                            start()   // un solo hilo atiende: los envíos del teléfono van de a uno
                        }
                    }.onFailure { _error.value = "No se pudo abrir el puerto TCP $PORT: ${it.message}" }.getOrNull()
                    if (server != null) _error.value = null
                } else if (address == null && server != null) {
                    server.stop(0)   // se apagó la Red Local: se suelta el puerto
                    server = null
                }
                Thread.sleep(CHECK_MS)
            }
        }
    }

    private fun handle(ex: HttpExchange, store: FlightLogStore) {
        try {
            if (!LocalHotspot.isLocalNetwork(ex.remoteAddress.address)) return reply(ex, 403, error("Solo se aceptan datos de la Red Local"))
            val path = ex.requestURI.path.trimEnd('/')
            when {
                ex.requestMethod == "GET" && path == "/salidas" ->
                    reply(ex, 200, """{"salidas":[${store.received().joinToString(",") { "\"$it\"" }}]}""")
                ex.requestMethod == "POST" && path.startsWith("/salidas/") -> receive(ex, store, path.removePrefix("/salidas/"))
                else -> reply(ex, 404, error("No existe"))
            }
        } catch (e: Exception) {
            runCatching { reply(ex, 500, error("Error en la PC: ${e.message}")) }
        } finally {
            ex.close()
        }
    }

    private fun receive(ex: HttpExchange, store: FlightLogStore, pathUuid: String) {
        val bytes = ex.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) return reply(ex, 413, error("El envío es demasiado grande"))
        val json = runCatching { MiniJson.parse(String(bytes, Charsets.UTF_8)) }.getOrNull() as? Map<*, *>
            ?: return reply(ex, 400, error("El envío no es JSON válido"))
        val info = SalidaInfo.from(json) ?: return reply(ex, 400, error("Faltan datos de la salida o no son válidos"))
        if (info.uuid != SalidaInfo.canonicalUuid(pathUuid)) return reply(ex, 400, error("El UUID de la dirección no coincide con el de la salida"))
        val registro = json["registro_csv"] as? String ?: return reply(ex, 400, error("Falta registro_csv"))
        val eventos = json["eventos_jsonl"] as? String ?: return reply(ex, 400, error("Falta eventos_jsonl"))
        val nuevo = store.save(info, registro, eventos)
        if (nuevo) _received.value = System.currentTimeMillis()
        reply(ex, 200, """{"ok":true,"nuevo":$nuevo}""")
    }

    private fun error(message: String) = """{"error":${MiniJson.quote(message)}}"""

    private fun reply(ex: HttpExchange, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
