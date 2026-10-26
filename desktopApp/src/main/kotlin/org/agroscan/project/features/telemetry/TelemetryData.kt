package org.agroscan.project.features.telemetry

import org.agroscan.project.core.Database
import java.util.UUID

/** Consultas de la pantalla de Telemetría. Usa la conexión de [Database]; aquí solo va el SQL y su lectura. */
object TelemetryData {
    /** "Lote - Misión" del vuelo EN_CURSO más reciente de [usuarioId], o null si no hay ninguno. Bloqueante. */
    fun misionEnCurso(usuarioId: UUID): Result<String?> = runCatching {
        Database.connect().use { c ->
            c.prepareStatement(
                """
                SELECT l.nombre || ' - ' || m.nombre FROM vuelo v
                JOIN mision m ON m.id = v.mision_id JOIN lote l ON l.id = m.lote_id
                WHERE m.creado_por = ? AND v.estado = 'EN_CURSO' ORDER BY v.inicio DESC NULLS LAST LIMIT 1
                """
            ).apply { setObject(1, usuarioId) }.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }
}
