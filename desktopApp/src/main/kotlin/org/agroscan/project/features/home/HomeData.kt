package org.agroscan.project.features.home

import org.agroscan.project.core.Database
import java.time.OffsetDateTime
import java.util.UUID

/** Consultas de la pantalla de Inicio. Usa la conexión de [Database]; aquí solo va el SQL y su lectura. */
object HomeData {
    data class MisionFila(val fecha: OffsetDateTime, val lote: String, val mision: String, val modoCaptura: String?, val estado: String?)

    /** [hectareas] es null si todavía no hay lotes con un vuelo completado. */
    data class Resumen(val hectareas: Double?, val vuelosMes: Int, val vuelosMesAnterior: Int, val misiones: List<MisionFila>)

    /** Datos de la pantalla de Inicio, solo de lo creado por [usuarioId]. Bloqueante: llamar fuera del hilo de UI. */
    fun resumen(usuarioId: UUID): Result<Resumen> = runCatching {
        Database.connect().use { c ->
            fun query(sql: String) = c.prepareStatement(sql).apply { setObject(1, usuarioId) }.executeQuery()

            val hectareas = query(
                """
                SELECT sum(l.area_ha) FROM lote l
                WHERE l.creado_por = ? AND l.eliminado_en IS NULL AND EXISTS (
                    SELECT 1 FROM mision m JOIN vuelo v ON v.mision_id = m.id
                    WHERE m.lote_id = l.id AND v.estado = 'COMPLETADO')
                """
            ).use { it.next(); it.getDouble(1).takeUnless { _ -> it.wasNull() } }

            val (mes, anterior) = query(
                """
                SELECT count(*) FILTER (WHERE v.fin >= date_trunc('month', now())),
                       count(*) FILTER (WHERE v.fin >= date_trunc('month', now()) - interval '1 month'
                                          AND v.fin <  date_trunc('month', now()))
                FROM vuelo v JOIN mision m ON m.id = v.mision_id
                WHERE m.creado_por = ? AND v.estado = 'COMPLETADO'
                """
            ).use { it.next(); it.getInt(1) to it.getInt(2) }

            // Fecha = último vuelo de la misión; si nunca voló, cuándo se creó.
            val misiones = query(
                """
                SELECT coalesce(max(v.inicio), m.creado_en), l.nombre, m.nombre, p.modo_captura, m.estado
                FROM mision m
                JOIN lote l ON l.id = m.lote_id
                JOIN parametros_vuelo p ON p.id = m.parametros_id
                LEFT JOIN vuelo v ON v.mision_id = m.id
                WHERE m.creado_por = ? AND m.eliminado_en IS NULL
                GROUP BY m.id, l.nombre, p.modo_captura
                ORDER BY 1 DESC
                LIMIT 5
                """
            ).use { rs ->
                buildList {
                    while (rs.next()) add(
                        MisionFila(rs.getObject(1, OffsetDateTime::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))
                    )
                }
            }
            Resumen(hectareas, mes, anterior, misiones)
        }
    }
}
