package org.agroscan.project.features.camera

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.agroscan.project.core.EventLog
import org.agroscan.project.features.redlocal.LocalHotspot

/**
 * Vista previa de la cámara del dron que manda el teléfono durante la misión (JPEG, ~4 por segundo). Llega por el mismo servidor
 * HTTP que los registros de vuelo (FlightLogServer, ruta `/camara`): mismo puerto y misma regla del Firewall. Formato en
 * docs/PROTOCOLO_PC.md del proyecto del dron.
 *
 * - `GET /camara` → `{"seguir":true|false}`: si la PC quiere imágenes (la pantalla de Telemetría está abierta).
 * - `POST /camara` con un JPEG → `{"seguir":…}`. El teléfono deja de capturar en cuanto la respuesta es false: no gasta batería
 *   si nadie mira.
 *
 * Solo se aceptan equipos de la Red Local, solo imágenes JPEG y de un tamaño máximo; la imagen solo se muestra.
 */
object CameraFeed {

    /** Una imagen de 960×540 en JPEG son ~50 KB: esto deja margen sin dejar que un envío llene la memoria. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** Sin imágenes durante este tiempo se deja de mostrar la última. */
    const val STALE_MS = 3_000L

    class Frame(val jpeg: ByteArray, val recibidoMs: Long)

    /** La pantalla de Telemetría está abierta: solo entonces se piden imágenes al teléfono. */
    @Volatile var watching = false
        private set

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame

    private var minuteStart = 0L
    private var frames = 0

    fun startWatching() {
        watching = true
        EventLog.log("camara", "Pantalla de Telemetría abierta: se pide la cámara al teléfono")
    }

    fun stopWatching() {
        watching = false
        _frame.value = null
        EventLog.log("camara", "Pantalla de Telemetría cerrada: el teléfono deja de mandar la cámara")
    }

    @Synchronized
    private fun countFrame(now: Long) {
        if (minuteStart == 0L) minuteStart = now
        frames++
        if (now - minuteStart >= 60_000) {
            EventLog.log("camara", "Último minuto: $frames imágenes de la cámara (${"%.1f".format(frames / 60.0)} por segundo)")
            minuteStart = now
            frames = 0
        }
    }

    internal fun handle(ex: HttpExchange) {
        try {
            if (!LocalHotspot.isLocalNetwork(ex.remoteAddress.address)) return reply(ex, 403, """{"error":"Solo la Red Local"}""")
            when (ex.requestMethod) {
                "GET" -> reply(ex, 200, """{"seguir":$watching}""")
                "POST" -> {
                    val bytes = ex.requestBody.readNBytes(MAX_BYTES + 1)
                    if (bytes.size > MAX_BYTES) return reply(ex, 413, """{"error":"Imagen demasiado grande"}""")
                    // Un JPEG empieza siempre con FF D8: lo demás se descarta sin intentar leerlo
                    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return reply(ex, 400, """{"error":"No es un JPEG"}""")
                    if (watching) {
                        _frame.value = Frame(bytes, System.currentTimeMillis())
                        countFrame(System.currentTimeMillis())
                    }
                    reply(ex, 200, """{"seguir":$watching}""")
                }
                else -> reply(ex, 405, """{"error":"Método no permitido"}""")
            }
        } catch (_: Exception) {
            // conexión cortada a mitad: la siguiente imagen la reemplaza
        } finally {
            ex.close()
        }
    }

    private fun reply(ex: HttpExchange, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
