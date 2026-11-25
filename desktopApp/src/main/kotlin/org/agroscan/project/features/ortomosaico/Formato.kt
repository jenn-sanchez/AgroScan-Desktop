package org.agroscan.project.features.ortomosaico

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Cómo se escriben fechas, tamaños y medidas en las pantallas de ortomosaicos: en español y con las unidades a la vista. */
object Formato {

    private val es = Locale.forLanguageTag("es")
    private val diaMesAnio = DateTimeFormatter.ofPattern("d MMM yyyy", es)

    /** "26 oct 2026" (el día en que se voló). */
    fun fecha(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(diaMesAnio)

    /** "35 KB", "480 MB", "1,2 GB". */
    fun bytes(n: Long): String = when {
        n >= 1_000_000_000 -> String.format(es, "%.1f GB", n / 1e9)
        n >= 1_000_000 -> String.format(es, "%.0f MB", n / 1e6)
        n >= 1_000 -> String.format(es, "%.0f KB", n / 1e3)
        else -> "$n B"
    }

    /** "1,2 ha". */
    fun hectareas(ha: Double): String = String.format(es, "%.2f ha", ha)

    /** "4,0 cm/px". */
    fun resolucion(cmPorPixel: Double): String = String.format(es, "%.1f cm/px", cmPorPixel)

    /** "96 %". */
    fun porcentaje(pct: Double): String = String.format(es, "%.0f %%", pct)

    /** "1 ortomosaico", "3 ortomosaicos". */
    fun ortomosaicos(n: Int): String = if (n == 1) "1 ortomosaico" else "$n ortomosaicos"

    /** Los días entre dos fechas de vuelo: "42 días", "1 día", "mismo día". */
    fun diasEntre(desdeMs: Long, hastaMs: Long): String {
        val dias = kotlin.math.abs(hastaMs - desdeMs) / 86_400_000L
        return when (dias) {
            0L -> "mismo día"
            1L -> "1 día"
            else -> "$dias días"
        }
    }
}
