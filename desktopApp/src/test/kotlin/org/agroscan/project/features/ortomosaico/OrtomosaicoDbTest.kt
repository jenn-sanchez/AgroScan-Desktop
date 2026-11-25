package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.Database
import org.agroscan.project.core.MiniJson
import org.agroscan.project.features.sync.HistorySync
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrtomosaicoDbTest {

    @Test fun losMotivosDeFalloSeEscribenParaUnaPersona() {
        assertTrue("falta configurar" in OrtomosaicoDb.motivo(IllegalStateException("Falta la variable AGROSCAN_DB_URL en esta PC")))
        assertTrue("002_ortomosaico.sql" in OrtomosaicoDb.motivo(RuntimeException("ERROR: relation \"ortomosaico\" does not exist\n  Position: 13")))
        assertTrue("no está encendida" in OrtomosaicoDb.motivo(RuntimeException("Connection to localhost:5433 refused. Check that the hostname")))
        assertEquals("ERROR: algo raro", OrtomosaicoDb.motivo(RuntimeException("\nERROR: algo raro\n  Detail: muchas líneas")))
    }

    // Como lo manda el teléfono: un lote con cuatro vértices, su misión y un vuelo
    private fun historial(lote: String, mision: String, vuelo: String) = MiniJson.parse(
        """{"v":1,
            "lotes":[{"uuid":"$lote","nombre":"Finca Sánchez","area_ha":1.5,"creado_ms":1793195800000,
                      "vertices":[[-0.2990,-79.4880],[-0.2990,-79.4870],[-0.3000,-79.4870],[-0.3000,-79.4880]]}],
            "misiones":[{"uuid":"$mision","lote":"$lote","nombre":"Sector 1","estado":"COMPLETADO","duracion_min":12.5,"fotos":80,
                         "ruta":"{}","creado_ms":1793195800000,"actualizado_ms":1793195900000,
                         "parametros":{"altura_m":25,"traslape_lateral_pct":70,"traslape_frontal_pct":75,"margen_interno_m":3,
                                       "altura_copa_m":4,"modo":"video"}}],
            "vuelos":[{"uuid":"$vuelo","mision":"$mision","estado":"COMPLETADO","inicio_ms":1793195900084,"fin_ms":1793196057094,
                       "modo":"video","perimetro":"[]","ruta":"{}","total_wp":6,"siguiente_wp":6,"salidas":[]}]}"""
    ) as Map<*, *>

    private fun <T> Connection.uno(sql: String, vararg v: Any?): T = prepareStatement(sql).use { st ->
        v.forEachIndexed { i, x -> st.setObject(i + 1, x) }
        st.executeQuery().use { it.next(); @Suppress("UNCHECKED_CAST") (it.getObject(1) as T) }
    }

    // Contra la base local de esta PC, dentro de una transacción que siempre se deshace: no queda nada guardado.
    // Sin base configurada (otra PC, CI) o sin la tabla (falta aplicar 002_ortomosaico.sql) no se prueba.
    @Test fun registrarConsultarYEliminarContraLaBase() {
        val c = runCatching { Database.connect() }.getOrNull() ?: return println("OrtomosaicoDbTest: sin base de datos en esta PC, no se prueba")
        c.use {
            val hayTabla = c.uno<Boolean>("SELECT to_regclass('public.ortomosaico') IS NOT NULL")
            if (!hayTabla) return println("OrtomosaicoDbTest: la base no tiene la tabla ortomosaico (aplica database/002_ortomosaico.sql), no se prueba")
            c.autoCommit = false
            try {
                val usuario = UUID.randomUUID()
                c.prepareStatement("INSERT INTO usuario (id, cedula, nombre, password_hash) VALUES (?, ?, 'Prueba', 'x')").use {
                    it.setObject(1, usuario); it.setString(2, "prueba-" + usuario.toString().take(8)); it.executeUpdate()
                }
                HistorySync.guardar(c, historial(LOTE_A, "22222222-2222-4222-8222-222222222222", VUELO_1), usuario)

                // Los lotes del usuario, con su área calculada por PostGIS desde el contorno
                val lotes = OrtomosaicoDb.lotes(c, usuario)
                assertEquals(listOf(LOTE_A), lotes.map { it.uuid })
                assertEquals("Finca Sánchez", lotes.single().nombre)
                assertTrue(lotes.single().areaHa!! in 1.1..1.3)
                // Otro usuario no ve ese lote
                assertEquals(emptyList(), OrtomosaicoDb.lotes(c, UUID.randomUUID()))
                // El contorno, en el orden en que se levantó, como (latitud, longitud)
                assertEquals(
                    listOf(-0.2990 to -79.4880, -0.2990 to -79.4870, -0.3000 to -79.4870, -0.3000 to -79.4880),
                    OrtomosaicoDb.contorno(c, LOTE_A)
                )

                // Registrar crea la fila; registrar otra vez la actualiza (una por vuelo)
                val f = ficha(vuelo = VUELO_1, lote = LOTE_A, usuario = usuario.toString())
                OrtomosaicoDb.registrar(c, f, usuario, tamanoBytes = 480_000_000)
                OrtomosaicoDb.registrar(c, f.copy(estado = EstadoOrtomosaico.ERROR, error = "falló", limites = null, terminadoMs = null), usuario, tamanoBytes = null)
                assertEquals(1L, c.uno<Long>("SELECT count(*) FROM ortomosaico WHERE vuelo_id = ?", UUID.fromString(VUELO_1)))
                assertEquals("ERROR", c.uno<String>("SELECT estado FROM ortomosaico WHERE vuelo_id = ?", UUID.fromString(VUELO_1)))
                assertEquals("falló", c.uno<String>("SELECT error FROM ortomosaico WHERE vuelo_id = ?", UUID.fromString(VUELO_1)))
                assertEquals(null, c.uno<Any?>("SELECT limites FROM ortomosaico WHERE vuelo_id = ?", UUID.fromString(VUELO_1)))

                // Con límites, quedan como polígono de PostGIS; las fechas son las del vuelo y del armado
                OrtomosaicoDb.registrar(c, f, usuario, tamanoBytes = 480_000_000)
                assertEquals("POLYGON", c.uno<String>("SELECT GeometryType(limites) FROM ortomosaico WHERE vuelo_id = ?", UUID.fromString(VUELO_1)))
                assertEquals(480_000_000L, c.uno<Long>("SELECT tamano_bytes FROM ortomosaico WHERE vuelo_id = ?", UUID.fromString(VUELO_1)))
                assertEquals(
                    f.creadoMs, c.uno<java.sql.Timestamp>("SELECT creado_en FROM ortomosaico WHERE vuelo_id = ?", UUID.fromString(VUELO_1)).time
                )

                // El historial de un polígono sale por lote_id, ordenado por la fecha del vuelo
                assertEquals(1L, c.uno<Long>("SELECT count(*) FROM ortomosaico WHERE lote_id = ?", UUID.fromString(LOTE_A)))

                // Eliminar quita la fila; eliminar lo que no está no falla
                OrtomosaicoDb.eliminar(c, VUELO_1)
                OrtomosaicoDb.eliminar(c, VUELO_1)
                assertEquals(0L, c.uno<Long>("SELECT count(*) FROM ortomosaico WHERE vuelo_id = ?", UUID.fromString(VUELO_1)))

                // La base se defiende: un vuelo o un lote que no existen se rechazan (cada intento en su punto de guardado)
                for (malo in listOf(
                    f.copy(vueloUuid = "00000000-0000-4000-8000-000000000001"),
                    f.copy(loteUuid = "00000000-0000-4000-8000-000000000002")
                )) {
                    val punto = c.setSavepoint()
                    assertFailsWith<java.sql.SQLException> { OrtomosaicoDb.registrar(c, malo, usuario, null) }
                    c.rollback(punto)
                }
            } finally {
                c.rollback()
            }
        }
    }
}
