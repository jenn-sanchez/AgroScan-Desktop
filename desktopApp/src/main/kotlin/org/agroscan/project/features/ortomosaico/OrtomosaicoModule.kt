package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.MiniJson
import org.agroscan.project.core.ModuloPython
import java.io.File

/**
 * Cómo se ejecuta el módulo de ortomosaicos (Python, carpeta `ortomosaico/` del repositorio; ver [ModuloPython]) y qué dice por la
 * salida estándar con `--json`, una línea por evento:
 * ```
 * {"tipo": "progreso", "porcentaje": 42, "etapa": "…"}
 * {"tipo": "fin", "resumen": {…}}
 * {"tipo": "error", "mensaje": "…"}      ← ya redactado para el usuario: qué pasó y qué hacer
 * ```
 * El registro detallado sale por la salida de errores. El detalle del contrato está en `ortomosaico/README.md`.
 */
object OrtomosaicoModule {

    fun ubicar(): Result<ModuloPython> = ModuloPython.ubicar("ortomosaico", "agroscan-ortomosaico", "prepararOrtomosaico", "ortomosaicos")

    /** El proceso que arma un ortomosaico a partir de la orden [entrada] (`entrada.json`), dejando el resultado en [salida]. */
    fun proceso(modulo: ModuloPython, entrada: File, salida: File): ProcessBuilder =
        ProcessBuilder(modulo.programa + listOf("--entrada", entrada.path, "--salida", salida.path, "--json"))
            .directory(modulo.carpeta)
            .apply { environment()["PYTHONIOENCODING"] = "utf-8" }

    /** Lo que el módulo midió del resultado: con esto se escribe la ficha. */
    data class Resumen(
        val imagenesUsadas: Int,
        val imagenesTotal: Int,
        val coberturaPct: Double?,
        val areaHa: Double?,
        val gsdCmPx: Double?,
        val crs: String?,
        val limites: Limites?,
        val errorEstimadoM: Double?,
        val motor: String,
        /** Cosas que el usuario debería saber del resultado (resolución reducida, imágenes descartadas…). */
        val advertencias: List<String>,
        /** Las imágenes se ajustaron entre sí con puntos en común (si no, quedaron donde dice el GPS). */
        val ajustado: Boolean = false,
        /** Vuelo de la fecha de referencia con la que se alineó este, si se pudo. */
        val alineadoCon: String? = null,
        /** Cuánto desacuerdo medido quedó con esa fecha, en metros. */
        val desacuerdoM: Double? = null,
        /** Cuántas teselas para la IA se cortaron; null si no se pudieron cortar (sin contorno del lote). */
        val teselas: Int? = null
    )

    sealed interface Evento {
        data class Progreso(val porcentaje: Int, val etapa: String) : Evento
        data class Fin(val resumen: Resumen) : Evento
        data class Error(val mensaje: String) : Evento
    }

    /** Una línea de la salida del módulo; null si no es del contrato (se ignora). */
    internal fun evento(linea: String): Evento? {
        val j = runCatching { MiniJson.parse(linea) }.getOrNull() as? Map<*, *> ?: return null
        return when (j["tipo"]) {
            "progreso" -> Evento.Progreso(((j["porcentaje"] as? Double)?.toInt() ?: 0).coerceIn(0, 100), j["etapa"] as? String ?: "")
            "error" -> Evento.Error(j["mensaje"] as? String ?: "El módulo informó un error sin detalle.")
            "fin" -> (j["resumen"] as? Map<*, *>)?.let { Evento.Fin(resumen(it)) }
            else -> null
        }
    }

    private fun resumen(r: Map<*, *>): Resumen {
        fun num(k: String) = (r[k] as? Double)?.takeIf { it.isFinite() }
        return Resumen(
            imagenesUsadas = num("imagenes_usadas")?.toInt() ?: 0,
            imagenesTotal = num("imagenes_total")?.toInt() ?: 0,
            coberturaPct = num("cobertura_pct"),
            areaHa = num("area_ha"),
            gsdCmPx = num("gsd_cm_px"),
            crs = r["crs"] as? String,
            limites = Limites.desde(r["limites"] as? Map<*, *>),
            errorEstimadoM = num("error_estimado_m"),
            motor = r["motor"] as? String ?: "",
            advertencias = (r["advertencias"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
            ajustado = (r["ajuste"] as? Map<*, *>)?.get("aplicado") == true,
            alineadoCon = (r["alineado_con"] as? Map<*, *>)?.get("vuelo") as? String,
            desacuerdoM = ((r["alineado_con"] as? Map<*, *>)?.get("desacuerdo_m") as? Double)?.takeIf { it.isFinite() },
            teselas = ((r["teselas"] as? Map<*, *>)?.get("escritas") as? Double)?.toInt()
        )
    }
}
