package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.Database
import java.sql.Connection
import java.sql.Types
import java.util.UUID

/**
 * Consultas de los ortomosaicos. La base solo los **indexa** (para el Inicio, los informes y el módulo de IA): la verdad de cada uno
 * es su carpeta ([OrtomosaicoStore]), así que todo esto es de mejor esfuerzo y nunca debe impedir ver, comparar ni borrar.
 * Usa la conexión de [Database]; aquí solo va el SQL y su lectura. Lo bloqueante se llama fuera del hilo de UI.
 */
object OrtomosaicoDb {

    /** Los lotes de [usuarioId] (los que creó), por nombre. */
    fun lotes(usuarioId: UUID): Result<List<LoteBase>> = runCatching {
        Database.connect().use { lotes(it, usuarioId) }
    }

    internal fun lotes(c: Connection, usuarioId: UUID): List<LoteBase> =
        c.prepareStatement("SELECT id, nombre, area_ha FROM lote WHERE creado_por = ? AND eliminado_en IS NULL ORDER BY lower(nombre)").use { st ->
            st.setObject(1, usuarioId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(LoteBase(rs.getObject(1, UUID::class.java).toString(), rs.getString(2), rs.getDouble(3).takeUnless { rs.wasNull() }))
                }
            }
        }

    /** El contorno del lote, como (latitud, longitud) en el orden en que se levantó. Vacío si no se encuentra. */
    fun contorno(loteUuid: String): Result<List<Pair<Double, Double>>> = runCatching {
        Database.connect().use { contorno(it, loteUuid) }
    }

    internal fun contorno(c: Connection, loteUuid: String): List<Pair<Double, Double>> =
        c.prepareStatement("SELECT latitud, longitud FROM vertice WHERE lote_id = ? ORDER BY orden").use { st ->
            st.setObject(1, UUID.fromString(loteUuid))
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getDouble(1) to rs.getDouble(2)) } }
        }

    /** A qué lote y misión pertenece un vuelo, con lo que el armado necesita saber de ellos. */
    data class ContextoVuelo(
        val loteUuid: String,
        val loteNombre: String,
        val misionNombre: String,
        /** Altura de los árboles (de los parámetros de la misión): el mosaico muestra la copa, no el suelo. */
        val alturaCopaM: Double?,
        val contorno: List<Pair<Double, Double>>
    )

    /** El contexto del vuelo según la base; null si ese vuelo aún no llegó a la base (el teléfono no envió su historial). */
    fun contextoDeVuelo(vueloUuid: String): Result<ContextoVuelo?> = runCatching {
        Database.connect().use { contextoDeVuelo(it, vueloUuid) }
    }

    internal fun contextoDeVuelo(c: Connection, vueloUuid: String): ContextoVuelo? {
        val fila = c.prepareStatement(
            """
            SELECT l.id, l.nombre, m.nombre, p.altura_copa_m
            FROM vuelo v JOIN mision m ON m.id = v.mision_id JOIN lote l ON l.id = m.lote_id
                 LEFT JOIN parametros_vuelo p ON p.id = m.parametros_id
            WHERE v.id = ?
            """
        ).use { st ->
            st.setObject(1, UUID.fromString(vueloUuid))
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                Triple(rs.getObject(1, UUID::class.java).toString(), rs.getString(2) to rs.getString(3), rs.getDouble(4).takeUnless { rs.wasNull() })
            }
        }
        return ContextoVuelo(fila.first, fila.second.first, fila.second.second, fila.third, contorno(c, fila.first))
    }

    /** Crea o actualiza la fila del ortomosaico de ese vuelo (hay una por vuelo). */
    fun registrar(ficha: Ficha, usuarioId: UUID, tamanoBytes: Long?): Result<Unit> = runCatching {
        Database.connect().use { registrar(it, ficha, usuarioId, tamanoBytes) }
    }

    internal fun registrar(c: Connection, f: Ficha, usuarioId: UUID, tamanoBytes: Long?) {
        c.prepareStatement(
            """
            INSERT INTO ortomosaico (vuelo_id, lote_id, creado_por, estado, motor, calidad, imagenes, imagenes_usadas, imagenes_total,
                                     cobertura_pct, area_ha, gsd_cm_px, crs, limites, error_estimado_m, tamano_bytes, error,
                                     creado_en, terminado_en)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 4326), ?, ?, ?,
                    to_timestamp(?::double precision / 1000.0), to_timestamp(?::double precision / 1000.0))
            ON CONFLICT (vuelo_id) DO UPDATE SET
                lote_id = EXCLUDED.lote_id, estado = EXCLUDED.estado, motor = EXCLUDED.motor, calidad = EXCLUDED.calidad,
                imagenes = EXCLUDED.imagenes, imagenes_usadas = EXCLUDED.imagenes_usadas, imagenes_total = EXCLUDED.imagenes_total,
                cobertura_pct = EXCLUDED.cobertura_pct, area_ha = EXCLUDED.area_ha, gsd_cm_px = EXCLUDED.gsd_cm_px, crs = EXCLUDED.crs,
                limites = EXCLUDED.limites, error_estimado_m = EXCLUDED.error_estimado_m, tamano_bytes = EXCLUDED.tamano_bytes,
                error = EXCLUDED.error, creado_en = EXCLUDED.creado_en, terminado_en = EXCLUDED.terminado_en
            """
        ).use { st ->
            st.setObject(1, UUID.fromString(f.vueloUuid))
            st.setObject(2, UUID.fromString(f.loteUuid))
            st.setObject(3, usuarioId)
            st.setString(4, f.estado.name)
            st.setString(5, f.motor.ifBlank { null })
            st.setString(6, f.calidad.ifBlank { null })
            st.setString(7, f.imagenes.ifBlank { null })
            st.setInt(8, f.imagenesUsadas)
            st.setInt(9, f.imagenesTotal)
            st.setDoubleOrNull(10, f.coberturaPct)
            st.setDoubleOrNull(11, f.areaHa)
            st.setDoubleOrNull(12, f.gsdCmPx)
            st.setString(13, f.crs)
            st.setString(14, f.limites?.wkt())
            st.setDoubleOrNull(15, f.errorEstimadoM)
            if (tamanoBytes != null) st.setLong(16, tamanoBytes) else st.setNull(16, Types.BIGINT)
            st.setString(17, f.error)
            st.setDouble(18, f.creadoMs.toDouble())
            st.setDoubleOrNull(19, f.terminadoMs?.toDouble())
            st.executeUpdate()
        }
    }

    /** Quita la fila del ortomosaico de ese vuelo (si no estaba, no hace nada). */
    fun eliminar(vueloUuid: String): Result<Unit> = runCatching {
        Database.connect().use { eliminar(it, vueloUuid) }
    }

    internal fun eliminar(c: Connection, vueloUuid: String) {
        c.prepareStatement("DELETE FROM ortomosaico WHERE vuelo_id = ?").use { st ->
            st.setObject(1, UUID.fromString(vueloUuid))
            st.executeUpdate()
        }
    }

    private fun java.sql.PreparedStatement.setDoubleOrNull(i: Int, v: Double?) {
        if (v != null && v.isFinite()) setDouble(i, v) else setNull(i, Types.DOUBLE)
    }

    /** El motivo de un fallo de la base en palabras de usuario: sin la pila ni el volcado del driver. */
    fun motivo(e: Throwable): String {
        val texto = e.message.orEmpty()
        return when {
            "AGROSCAN_DB_URL" in texto -> "falta configurar la conexión a la base de datos (ver docs/BASE_DE_DATOS.md)"
            "\"ortomosaico\" does not exist" in texto -> "la base no tiene la tabla de ortomosaicos: aplica database/002_ortomosaico.sql"
            "Connection" in texto && "refused" in texto -> "la base de datos no está encendida"
            else -> texto.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: e::class.simpleName.orEmpty()
        }
    }
}
