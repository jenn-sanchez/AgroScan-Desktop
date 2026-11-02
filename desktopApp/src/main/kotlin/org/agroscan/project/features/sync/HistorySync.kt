package org.agroscan.project.features.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.agroscan.project.core.Database
import org.agroscan.project.features.flightlog.SalidaInfo
import java.sql.Connection
import java.sql.Types
import java.util.Locale
import java.util.UUID

/**
 * Guarda en la base de datos el historial que manda el teléfono: lotes con su perímetro, misiones con sus parámetros, vuelos y
 * salidas. Llega por `POST /sync` ([org.agroscan.project.features.flightlog.FlightLogServer]); formato en docs/PROTOCOLO_PC.md del
 * proyecto del dron y explicación en docs/BASE_DE_DATOS.md.
 *
 * - El teléfono manda todo cada vez: cada fila se inserta o se actualiza por su UUID, así que repetir no duplica nada.
 * - Una sola transacción: si algo no es válido o falla, no queda nada a medias.
 * - El teléfono no tiene cuentas: lo nuevo queda a nombre de quien tiene la sesión en la PC; lo que ya estaba conserva a su dueño.
 * - Lo que se borra en el teléfono no se borra aquí (para eso está `eliminado_en`, cuando se decida cómo manejarlo).
 */
object HistorySync {

    private const val MAX_TEXTO = 500

    private val _recibido = MutableStateFlow(0L)
    /** Cambia cada vez que se guarda un historial (para refrescar el Inicio). */
    val recibido: StateFlow<Long> = _recibido

    data class Resultado(val lotes: Int, val misiones: Int, val vuelos: Int, val salidas: Int)

    /** Guarda [json] (ya leído) a nombre de [usuario], todo o nada. IllegalArgumentException si el historial no es válido. */
    fun guardar(json: Map<*, *>, usuario: UUID): Resultado = Database.connect().use { c ->
        c.autoCommit = false
        try {
            guardar(c, json, usuario).also {
                c.commit()
                _recibido.value = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            c.rollback()
            throw e
        }
    }

    /** Lo mismo sobre una conexión ya abierta y sin confirmar la transacción (las pruebas la deshacen). */
    internal fun guardar(c: Connection, json: Map<*, *>, usuario: UUID): Resultado {
        require(json["v"] == 1.0) { "versión no soportada (${json["v"]})" }
        val lotes = lista(json, "lotes")
        val misiones = lista(json, "misiones")
        val vuelos = lista(json, "vuelos")

        // Padres antes que hijos: lote → vértices → parámetros → misión → vuelo → salida
        for (l in lotes) {
            val id = l.uuid("uuid")
            val v = vertices(l["vertices"])
            c.ejecutar(SQL_LOTE, id, usuario, l.nombre(), l.num("area_ha"), l.ms("creado_ms"), poligono(v))
            c.ejecutar("DELETE FROM vertice WHERE lote_id = ?", id)   // no tienen UUID: se reemplazan
            v.forEachIndexed { i, (lat, lon) -> c.ejecutar("INSERT INTO vertice (lote_id, orden, latitud, longitud) VALUES (?, ?, ?, ?)", id, i, lat, lon) }
        }
        for (m in misiones) {
            val id = m.uuid("uuid")
            val p = m["parametros"] as? Map<*, *> ?: throw IllegalArgumentException("misión sin parámetros")
            // Los parámetros no tienen UUID en el teléfono y son uno por misión: llevan el de su misión
            c.ejecutar(
                SQL_PARAMETROS, id, p.num("altura_m"), p.num("traslape_lateral_pct"), p.num("traslape_frontal_pct"),
                p.num("margen_interno_m"), p.num("altura_copa_m"), p.texto("modo")
            )
            c.ejecutar(
                SQL_MISION, id, m.uuid("lote"), id, usuario, m.nombre(), m.texto("estado"), m.num("duracion_min"), m.entero("fotos"),
                m.json("ruta"), m.ms("creado_ms"), m.ms("actualizado_ms")
            )
        }
        var salidas = 0
        for (v in vuelos) {
            val id = v.uuid("uuid")
            c.ejecutar(
                SQL_VUELO, id, v.uuid("mision"), usuario, v.texto("estado"), v.texto("modo"), v.json("perimetro"), v.json("ruta"),
                v.entero("total_wp") ?: 0, v.entero("siguiente_wp") ?: 0, v.ms("inicio_ms"), v.ms("fin_ms")
            )
            for (s in lista(v, "salidas")) {
                c.ejecutar(
                    SQL_SALIDA, s.uuid("uuid"), id, usuario, s.entero("indice") ?: throw IllegalArgumentException("salida sin índice"),
                    s.entero("desde_wp"), s.entero("hasta_wp"), s.texto("estado"), s.texto("motivo"), s.ms("inicio_ms"), s.ms("fin_ms"),
                    s.entero("bateria_inicio"), s.entero("bateria_fin"), s.num("distancia_m"), s.ms("video_inicio_ms"), s.ms("video_fin_ms")
                )
                salidas++
            }
        }
        return Resultado(lotes.size, misiones.size, vuelos.size, salidas)
    }

    /** Vértices `[[lat, lon], …]` del teléfono, validados. */
    internal fun vertices(valor: Any?): List<Pair<Double, Double>> = (valor as? List<*>).orEmpty().map { p ->
        val par = p as? List<*>
        val lat = par?.getOrNull(0) as? Double
        val lon = par?.getOrNull(1) as? Double
        require(par?.size == 2 && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) { "vértice no válido: $p" }
        lat to lon
    }

    /** Polígono WKT para PostGIS (`lon lat`, con el primer vértice repetido al final); null si no hay al menos 3 vértices. */
    internal fun poligono(v: List<Pair<Double, Double>>): String? =
        if (v.size < 3) null else (v + v.first()).joinToString(", ", "POLYGON((", "))") { (lat, lon) -> "%.8f %.8f".format(Locale.ROOT, lon, lat) }

    // El área sale del perímetro (PostGIS, en metros reales); si no hay polígono, la que calculó el teléfono
    private const val SQL_LOTE = """
        INSERT INTO lote (id, creado_por, nombre, area_ha, creado_en, perimetro)
        SELECT ?, ?, ?, coalesce(ST_Area(g::geography) / 10000, ?), coalesce(to_timestamp(? / 1000.0), now()), g
        FROM (SELECT ST_GeomFromText(?, 4326) AS g) AS p
        ON CONFLICT (id) DO UPDATE SET nombre = EXCLUDED.nombre, area_ha = EXCLUDED.area_ha, perimetro = EXCLUDED.perimetro,
            actualizado_en = now()"""

    private const val SQL_PARAMETROS = """
        INSERT INTO parametros_vuelo (id, altura_m, traslape_lateral_pct, traslape_frontal_pct, margen_interno_m, altura_copa_m, modo_captura)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (id) DO UPDATE SET altura_m = EXCLUDED.altura_m, traslape_lateral_pct = EXCLUDED.traslape_lateral_pct,
            traslape_frontal_pct = EXCLUDED.traslape_frontal_pct, margen_interno_m = EXCLUDED.margen_interno_m,
            altura_copa_m = EXCLUDED.altura_copa_m, modo_captura = EXCLUDED.modo_captura"""

    private const val SQL_MISION = """
        INSERT INTO mision (id, lote_id, parametros_id, creado_por, nombre, estado, duracion_estimada_min, fotos_estimadas, ruta_waypoints,
            creado_en, actualizado_en)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), coalesce(to_timestamp(? / 1000.0), now()), to_timestamp(? / 1000.0))
        ON CONFLICT (id) DO UPDATE SET lote_id = EXCLUDED.lote_id, nombre = EXCLUDED.nombre, estado = EXCLUDED.estado,
            duracion_estimada_min = EXCLUDED.duracion_estimada_min, fotos_estimadas = EXCLUDED.fotos_estimadas,
            ruta_waypoints = EXCLUDED.ruta_waypoints, actualizado_en = EXCLUDED.actualizado_en"""

    private const val SQL_VUELO = """
        INSERT INTO vuelo (id, mision_id, iniciado_por, estado, modo_captura, perimetro_volado, ruta_volada, total_waypoints,
            siguiente_waypoint, inicio, fin, actualizado_en)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, to_timestamp(? / 1000.0), to_timestamp(? / 1000.0), now())
        ON CONFLICT (id) DO UPDATE SET estado = EXCLUDED.estado, perimetro_volado = EXCLUDED.perimetro_volado,
            ruta_volada = EXCLUDED.ruta_volada, total_waypoints = EXCLUDED.total_waypoints,
            siguiente_waypoint = EXCLUDED.siguiente_waypoint, fin = EXCLUDED.fin, actualizado_en = now()"""

    private const val SQL_SALIDA = """
        INSERT INTO salida (id, vuelo_id, operador_id, indice, desde_wp, hasta_wp, estado, motivo, inicio_ms, fin_ms, bateria_inicio,
            bateria_fin, distancia_m, video_inicio_ms, video_fin_ms, actualizado_en)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (id) DO UPDATE SET desde_wp = EXCLUDED.desde_wp, hasta_wp = EXCLUDED.hasta_wp, estado = EXCLUDED.estado,
            motivo = EXCLUDED.motivo, fin_ms = EXCLUDED.fin_ms, bateria_fin = EXCLUDED.bateria_fin, distancia_m = EXCLUDED.distancia_m,
            video_inicio_ms = EXCLUDED.video_inicio_ms, video_fin_ms = EXCLUDED.video_fin_ms, actualizado_en = now()"""

    /** Ejecuta [sql] con [valores] como parámetros (nunca pegados en el SQL). */
    private fun Connection.ejecutar(sql: String, vararg valores: Any?) = prepareStatement(sql).use { st ->
        valores.forEachIndexed { i, v -> if (v == null) st.setNull(i + 1, Types.OTHER) else st.setObject(i + 1, v) }
        st.executeUpdate()
    }

    private fun lista(m: Map<*, *>, k: String): List<Map<*, *>> =
        (m[k] as? List<*> ?: throw IllegalArgumentException("falta \"$k\"")).map { it as? Map<*, *> ?: throw IllegalArgumentException("\"$k\" tiene algo que no es un objeto") }

    private fun Map<*, *>.uuid(k: String): UUID =
        SalidaInfo.canonicalUuid(this[k])?.let(UUID::fromString) ?: throw IllegalArgumentException("\"$k\" no es un UUID válido")

    private fun Map<*, *>.texto(k: String): String? = (this[k] as? String)?.take(MAX_TEXTO)
    private fun Map<*, *>.nombre(): String = texto("nombre")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("falta el nombre")
    private fun Map<*, *>.num(k: String): Double? = (this[k] as? Double)?.takeIf { it.isFinite() }
    private fun Map<*, *>.ms(k: String): Long? = num(k)?.toLong()
    private fun Map<*, *>.entero(k: String): Int? = num(k)?.toInt()
    /** Rutas y perímetros: JSON que el teléfono manda como texto; PostgreSQL rechaza (y deshace todo) si no es JSON. */
    private fun Map<*, *>.json(k: String): String? = this[k] as? String
}
