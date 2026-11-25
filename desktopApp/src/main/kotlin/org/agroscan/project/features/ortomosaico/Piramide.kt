package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.MiniJson
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * La pirámide de teselas del mosaico (`piramide/<nivel>/<fila>_<columna>.jpg` y `piramide.json`, que escribe el módulo de ortomosaicos): el mosaico a
 * resolución completa partido en teselas JPG de [lado] px, y niveles de la mitad de resolución cada uno. Así el visor muestra el detalle real al acercar
 * sin cargar un mosaico de cientos de millones de píxeles: pide solo las teselas del nivel y la zona que se están mirando.
 *
 * `vista.jpg` sigue siendo el mosaico entero en pequeño; la pirámide solo guarda los niveles **más finos que ella**, y el visor la dibuja encima.
 * Esta clase es solo la cuenta de qué pedir; no lee imágenes.
 */
class Piramide(val carpeta: File, val lado: Int, val anchoPx: Int, val altoPx: Int, val niveles: Int) {

    data class Posicion(val nivel: Int, val fila: Int, val columna: Int)

    /** Una tesela y el pedazo del mosaico entero que cubre, como fracciones de su ancho y su alto (0–1). */
    data class Tesela(val posicion: Posicion, val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    fun archivo(p: Posicion): File = File(carpeta, "${Archivos.PIRAMIDE}/${p.nivel}/${p.fila}_${p.columna}.jpg")

    fun anchoNivel(nivel: Int): Int = ceil(anchoPx / 2.0.pow(nivel)).toInt()
    fun altoNivel(nivel: Int): Int = ceil(altoPx / 2.0.pow(nivel)).toInt()

    /**
     * El nivel con el que se ve nítido cuando un píxel de pantalla abarca [pxCompletosPorPixelPantalla] píxeles del mosaico a resolución completa: el
     * más grueso cuyo píxel no es mayor que el de la pantalla (así nunca se agranda una imagen, y se lee la menor cantidad de teselas). null si la
     * vista ya alcanza (se mira tan de lejos que ni el nivel más fino de la pirámide aporta).
     */
    fun nivelPara(pxCompletosPorPixelPantalla: Double): Int? {
        if (!pxCompletosPorPixelPantalla.isFinite() || pxCompletosPorPixelPantalla <= 0) return null
        val k = if (pxCompletosPorPixelPantalla <= 1.0) 0 else floor(ln(pxCompletosPorPixelPantalla) / ln(2.0)).toInt()
        return k.takeIf { it < niveles }
    }

    /**
     * Las teselas que hay que dibujar para lo que se ve. El mosaico entero ocupa en pantalla el rectángulo ([x], [y], [ancho], [alto]) (puede salirse
     * de la pantalla) y la pantalla mide [pantallaAncho] × [pantallaAlto]. Vacío si la vista alcanza o el mosaico no se ve.
     */
    fun visibles(x: Double, y: Double, ancho: Double, alto: Double, pantallaAncho: Double, pantallaAlto: Double): List<Tesela> {
        if (ancho <= 0 || alto <= 0) return emptyList()
        val nivel = nivelPara(anchoPx / ancho) ?: return emptyList()
        val fx0 = ((0 - x) / ancho).coerceIn(0.0, 1.0)
        val fx1 = ((pantallaAncho - x) / ancho).coerceIn(0.0, 1.0)
        val fy0 = ((0 - y) / alto).coerceIn(0.0, 1.0)
        val fy1 = ((pantallaAlto - y) / alto).coerceIn(0.0, 1.0)
        if (fx1 <= fx0 || fy1 <= fy0) return emptyList()
        val w = anchoNivel(nivel)
        val h = altoNivel(nivel)
        val columnas = ceil(w / lado.toDouble()).toInt()
        val filas = ceil(h / lado.toDouble()).toInt()
        val c0 = floor(fx0 * w / lado).toInt().coerceIn(0, columnas - 1)
        val c1 = (ceil(fx1 * w / lado).toInt() - 1).coerceIn(c0, columnas - 1)
        val f0 = floor(fy0 * h / lado).toInt().coerceIn(0, filas - 1)
        val f1 = (ceil(fy1 * h / lado).toInt() - 1).coerceIn(f0, filas - 1)
        if ((c1 - c0 + 1).toLong() * (f1 - f0 + 1) > MAX_TESELAS) return emptyList()      // una pantalla enorme o un error: no se pide medio mosaico
        return buildList {
            for (f in f0..f1) for (c in c0..c1) {
                add(Tesela(Posicion(nivel, f, c), c * lado / w.toDouble(), f * lado / h.toDouble(), min((c + 1) * lado, w) / w.toDouble(), min((f + 1) * lado, h) / h.toDouble()))
            }
        }
    }

    companion object {
        /** Las teselas que se piden a la vez como mucho. */
        const val MAX_TESELAS = 100

        /** La pirámide de la carpeta de un ortomosaico, o null si no tiene (los armados antes de existir, o los pequeños que la vista ya muestra completos) o es ilegible. */
        fun leer(carpeta: File): Piramide? = runCatching {
            val j = MiniJson.parse(File(carpeta, Archivos.PIRAMIDE_JSON).readText(Charsets.UTF_8)) as Map<*, *>
            fun entero(k: String) = (j[k] as? Double)?.toInt()
            val p = Piramide(carpeta, entero("lado") ?: return null, entero("ancho_px") ?: return null, entero("alto_px") ?: return null, entero("niveles") ?: return null)
            p.takeIf { it.lado in 16..4096 && it.anchoPx > 0 && it.altoPx > 0 && it.niveles in 1..16 && max(it.anchoPx, it.altoPx) < 1_000_000 }
        }.getOrNull()
    }
}
