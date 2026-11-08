package org.agroscan.project.features.ortomosaico

import kotlin.math.cos
import kotlin.math.min

/** Un rectángulo sobre el plano, en metros: esquina superior izquierda y tamaño. */
data class RectM(val x: Double, val y: Double, val ancho: Double, val alto: Double)

/**
 * Pasa coordenadas geográficas (grados) a metros sobre un plano, para dibujar ortomosaicos de lotes de unas hectáreas con su forma
 * real. A esa escala una proyección lineal con la corrección de la latitud es exacta de sobra. `x` crece hacia el este y `y` hacia el
 * sur (como la pantalla).
 */
class Proyeccion(val marco: Limites) {

    private val metrosPorGradoLon = M_POR_GRADO * cos(Math.toRadians((marco.sur + marco.norte) / 2))

    fun x(lon: Double): Double = (lon - marco.oeste) * metrosPorGradoLon
    fun y(lat: Double): Double = (marco.norte - lat) * M_POR_GRADO

    val anchoM: Double get() = (marco.este - marco.oeste) * metrosPorGradoLon
    val altoM: Double get() = (marco.norte - marco.sur) * M_POR_GRADO

    /** Dónde cae [l] dentro del marco. */
    fun rect(l: Limites) = RectM(x(l.oeste), y(l.norte), (l.este - l.oeste) * metrosPorGradoLon, (l.norte - l.sur) * M_POR_GRADO)

    companion object {
        private const val M_POR_GRADO = 111_000.0

        /** El rectángulo mínimo que contiene a todos. */
        fun union(vararg l: Limites): Limites =
            Limites(l.minOf { it.oeste }, l.minOf { it.sur }, l.maxOf { it.este }, l.maxOf { it.norte })

        /** Cuántos píxeles por metro hacen falta para que [anchoM] × [altoM] quepa en [anchoPx] × [altoPx] dejando [margen] (0–0,5) por lado. */
        fun escalaParaAjustar(anchoM: Double, altoM: Double, anchoPx: Float, altoPx: Float, margen: Float = 0.06f): Float {
            if (anchoM <= 0 || altoM <= 0 || anchoPx <= 0 || altoPx <= 0) return 1f
            val libre = 1f - 2 * margen
            return min(anchoPx * libre / anchoM.toFloat(), altoPx * libre / altoM.toFloat())
        }

        /** Largo "redondo" de la barra de escala: el mayor de 1, 2 o 5 × 10ⁿ metros que no mide más de [maxPx] píxeles. */
        fun largoBarraM(pxPorMetro: Float, maxPx: Float): Double {
            if (pxPorMetro <= 0f || maxPx <= 0f) return 1.0
            val maxM = maxPx / pxPorMetro
            var decada = 1.0
            while (decada > maxM) decada /= 10
            while (decada * 10 <= maxM) decada *= 10
            return listOf(5.0, 2.0, 1.0).map { it * decada }.first { it <= maxM }
        }
    }
}
