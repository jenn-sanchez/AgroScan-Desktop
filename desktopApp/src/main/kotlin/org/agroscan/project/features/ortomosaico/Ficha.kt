package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.MiniJson
import org.agroscan.project.features.flightlog.SalidaInfo
import java.io.File
import java.util.Locale

/** Cómo está un ortomosaico. */
enum class EstadoOrtomosaico(val etiqueta: String) {
    PROCESANDO("Armando"),
    LISTO("Listo"),
    ERROR("Con error"),
    CANCELADO("Cancelado"),
    INTERRUMPIDO("Interrumpido")
}

/** Rectángulo del terreno que cubre una imagen, en grados (WGS84): oeste/este = longitud, sur/norte = latitud. */
data class Limites(val oeste: Double, val sur: Double, val este: Double, val norte: Double) {
    val validos: Boolean
        get() = listOf(oeste, sur, este, norte).all { it.isFinite() } &&
            oeste < este && sur < norte && oeste in -180.0..180.0 && este in -180.0..180.0 && sur in -90.0..90.0 && norte in -90.0..90.0

    /** `POLYGON((lon lat, …))` cerrado, como lo pide PostGIS. */
    fun wkt(): String = String.format(
        Locale.ROOT, "POLYGON((%.8f %.8f, %.8f %.8f, %.8f %.8f, %.8f %.8f, %.8f %.8f))",
        oeste, sur, este, sur, este, norte, oeste, norte, oeste, sur
    )

    companion object {
        /** Lee `{"oeste", "sur", "este", "norte"}`; null si falta algo o el rectángulo no es válido. */
        fun desde(m: Map<*, *>?): Limites? {
            if (m == null) return null
            val oeste = m["oeste"] as? Double
            val sur = m["sur"] as? Double
            val este = m["este"] as? Double
            val norte = m["norte"] as? Double
            return if (oeste != null && sur != null && este != null && norte != null) Limites(oeste, sur, este, norte).takeIf { it.validos } else null
        }
    }
}

/** Nombres de los archivos de un ortomosaico, para que nadie los escriba a mano. */
object Archivos {
    const val FICHA = "ortomosaico.json"
    const val ENTRADA = "entrada.json"
    const val GEOTIFF = "ortomosaico.tif"
    const val VISTA = "vista.jpg"
    const val MINIATURA = "miniatura.jpg"
    const val COBERTURA = "cobertura.geojson"
    /** Dónde quedó el mosaico sobre el terreno (UTM exacto) y la cuadrícula de teselas del lote: lo lee el módulo al alinear fechas y la IA. */
    const val GEOREF = "georef.json"
    /** El mosaico a resolución completa en teselas JPG y niveles de zoom, para que el visor lo muestre nítido al acercar (ver [Piramide]). */
    const val PIRAMIDE = "piramide"
    const val PIRAMIDE_JSON = "piramide.json"
    const val TESELAS = "teselas"
    const val TESELAS_INDICE = "teselas.csv"
}

/**
 * Lo que se sabe de un ortomosaico (`ortomosaico.json`, junto a sus archivos). Es la verdad del ortomosaico: la base solo lo indexa,
 * así que el historial se puede ver aunque la base esté apagada. El historial de cada polígono se arma con [loteUuid] y se ordena por
 * [vueloInicioMs] (la fecha en que se voló, no en que se armó).
 */
data class Ficha(
    val estado: EstadoOrtomosaico,
    val vueloUuid: String,
    val loteUuid: String,
    val loteNombre: String,
    val misionNombre: String,
    /** Quién lo creó (el usuario con sesión en la PC); null si no se sabe. */
    val usuarioId: String?,
    val vueloInicioMs: Long,
    val creadoMs: Long,
    val terminadoMs: Long?,
    /** Con qué se armó (nombre y versión del motor). */
    val motor: String = "",
    /** `rapida`, `estandar` o `alta`. */
    val calidad: String = "",
    /** `originales` o `corregidas`: de qué imágenes se partió. */
    val imagenes: String = "",
    val imagenesUsadas: Int = 0,
    val imagenesTotal: Int = 0,
    val coberturaPct: Double? = null,
    val areaHa: Double? = null,
    val gsdCmPx: Double? = null,
    val crs: String? = null,
    val limites: Limites? = null,
    val errorEstimadoM: Double? = null,
    /** Por qué falló o se interrumpió, para mostrárselo al usuario. */
    val error: String? = null
) {
    fun toJson(): String {
        fun s(x: String?) = x?.let(MiniJson::quote) ?: "null"
        fun n(x: Double?) = x?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "null"
        fun l(x: Long?) = x?.toString() ?: "null"
        val lim = limites?.let {
            """{"oeste":${n(it.oeste)},"sur":${n(it.sur)},"este":${n(it.este)},"norte":${n(it.norte)}}"""
        } ?: "null"
        return """{"v":1,"estado":${s(estado.name)},"vuelo_uuid":${s(vueloUuid)},"lote_uuid":${s(loteUuid)},""" +
            """"lote":${s(loteNombre)},"mision":${s(misionNombre)},"usuario_id":${s(usuarioId)},""" +
            """"vuelo_inicio_ms":$vueloInicioMs,"creado_ms":$creadoMs,"terminado_ms":${l(terminadoMs)},""" +
            """"motor":${s(motor)},"calidad":${s(calidad)},"imagenes":${s(imagenes)},""" +
            """"imagenes_usadas":$imagenesUsadas,"imagenes_total":$imagenesTotal,"cobertura_pct":${n(coberturaPct)},""" +
            """"area_ha":${n(areaHa)},"gsd_cm_px":${n(gsdCmPx)},"crs":${s(crs)},"limites":$lim,""" +
            """"error_estimado_m":${n(errorEstimadoM)},"error":${s(error)}}"""
    }

    companion object {
        /**
         * Lee una ficha. null si falta algo imprescindible o algo no es válido: los UUID terminan en nombres de carpeta, así que solo se
         * aceptan en su forma canónica.
         */
        fun from(j: Map<*, *>): Ficha? {
            if ((j["v"] as? Double)?.toInt() != 1) return null
            fun str(k: String, max: Int = 300) = (j[k] as? String)?.take(max)
            fun num(k: String) = (j[k] as? Double)?.takeIf { it.isFinite() }
            fun lng(k: String) = num(k)?.toLong()
            val limites = Limites.desde(j["limites"] as? Map<*, *>)
            return Ficha(
                estado = EstadoOrtomosaico.entries.firstOrNull { it.name == j["estado"] } ?: return null,
                vueloUuid = SalidaInfo.canonicalUuid(j["vuelo_uuid"]) ?: return null,
                loteUuid = SalidaInfo.canonicalUuid(j["lote_uuid"]) ?: return null,
                loteNombre = str("lote")?.takeIf { it.isNotBlank() } ?: return null,
                misionNombre = str("mision").orEmpty(),
                usuarioId = SalidaInfo.canonicalUuid(j["usuario_id"]),
                vueloInicioMs = lng("vuelo_inicio_ms")?.takeIf { it > 0 } ?: return null,
                creadoMs = lng("creado_ms") ?: return null,
                terminadoMs = lng("terminado_ms"),
                motor = str("motor", 80).orEmpty(),
                calidad = str("calidad", 20).orEmpty(),
                imagenes = str("imagenes", 20).orEmpty(),
                imagenesUsadas = num("imagenes_usadas")?.toInt() ?: 0,
                imagenesTotal = num("imagenes_total")?.toInt() ?: 0,
                coberturaPct = num("cobertura_pct"),
                areaHa = num("area_ha"),
                gsdCmPx = num("gsd_cm_px"),
                crs = str("crs", 40),
                limites = limites,
                errorEstimadoM = num("error_estimado_m"),
                error = str("error", 500)
            )
        }
    }
}

/** Un ortomosaico guardado: su carpeta y su [ficha]. */
data class Entrada(val dir: File, val ficha: Ficha) {
    val vista: File get() = File(dir, Archivos.VISTA)
    val miniatura: File get() = File(dir, Archivos.MINIATURA)
    val geotiff: File get() = File(dir, Archivos.GEOTIFF)
    val tieneVista: Boolean get() = vista.isFile
    val listo: Boolean get() = ficha.estado == EstadoOrtomosaico.LISTO
}
