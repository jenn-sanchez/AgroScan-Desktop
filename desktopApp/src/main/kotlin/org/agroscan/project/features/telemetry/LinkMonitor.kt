package org.agroscan.project.features.telemetry

/**
 * Vigila el enlace con el teléfono a partir de los paquetes que llegan: avisa cuando se corta y cuando vuelve, mide qué parte de
 * los paquetes llega (por el contador `seq` que el teléfono pone en cada uno, sea `tel`, `plan` o `lote`) y deja un resumen por
 * minuto. [log] recibe cada línea; la hora entra por parámetro para poder probarlo.
 */
internal class LinkMonitor(private val log: (String) -> Unit) {

    private var lastAt = 0L
    private var receiving = false

    /** (hora de llegada, seq) de los últimos [WINDOW_MS], para [quality]. */
    private val window = ArrayDeque<Pair<Long, Long>>()

    private var minuteStart = 0L
    private var count = 0
    private var minSeq = Long.MAX_VALUE
    private var maxSeq = Long.MIN_VALUE

    /** Qué parte de los paquetes llegó en los últimos 30 s (0–100); null con muy pocos datos para decirlo. */
    var quality: Int? = null
        private set

    /** Llegó un paquete. [estado] describe la situación para el registro (fase, dron, batería…). */
    fun packet(now: Long, seq: Long?, estado: String) {
        if (!receiving) {
            log(if (lastAt == 0L) "Llegó el primer paquete del teléfono · $estado" else "SEÑAL RECUPERADA tras ${(now - lastAt) / 1000} s sin datos · $estado")
            receiving = true
        }
        lastAt = now
        if (minuteStart == 0L) minuteStart = now
        count++
        if (seq != null) {
            // El contador volvió atrás: el teléfono abrió la app de nuevo. Se empieza a medir de cero
            if (window.isNotEmpty() && seq < window.last().second - 50) {
                window.clear()
                minSeq = Long.MAX_VALUE
                maxSeq = Long.MIN_VALUE
            }
            window.addLast(now to seq)
            minSeq = minOf(minSeq, seq)
            maxSeq = maxOf(maxSeq, seq)
        }
        update(now, estado)
    }

    /** Se llama cada poco aunque no lleguen paquetes: así se detecta el corte. */
    fun tick(now: Long, estado: String) {
        if (receiving && now - lastAt > TelemetryReceiver.STALE_MS) {
            log("SEÑAL PERDIDA: no llega nada del teléfono hace ${(now - lastAt) / 1000} s · último estado: $estado")
            receiving = false
        }
        update(now, estado)
    }

    private fun update(now: Long, estado: String) {
        while (window.isNotEmpty() && window.first().first < now - WINDOW_MS) window.removeFirst()
        quality = if (window.size < 5) null else {
            val esperados = window.maxOf { it.second } - window.minOf { it.second } + 1
            (window.size * 100 / esperados).toInt().coerceIn(0, 100)
        }
        if (minuteStart == 0L || now - minuteStart < 60_000) return
        val esperados = if (maxSeq >= minSeq) (maxSeq - minSeq + 1).toInt() else count
        val perdidos = (esperados - count).coerceAtLeast(0)
        log("Último minuto: $count paquetes recibidos, $perdidos perdidos (${if (esperados > 0) perdidos * 100 / esperados else 0} %) · $estado")
        // Sin señal no se escriben minutos vacíos: el corte ya quedó registrado
        minuteStart = if (receiving) now else 0L
        count = 0
        minSeq = Long.MAX_VALUE
        maxSeq = Long.MIN_VALUE
    }

    companion object {
        const val WINDOW_MS = 30_000L
    }
}
