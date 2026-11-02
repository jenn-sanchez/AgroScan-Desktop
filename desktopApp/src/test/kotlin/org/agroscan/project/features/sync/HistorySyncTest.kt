package org.agroscan.project.features.sync

import org.agroscan.project.core.Database
import org.agroscan.project.core.MiniJson
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistorySyncTest {

    // Como lo arma el teléfono (HistorySync.body en el proyecto del dron): un lote, su misión, un vuelo con una salida
    private val historial = """
        {"v":1,
         "lotes":[{"uuid":"11111111-1111-4111-8111-111111111111","nombre":"Finca Sánchez","area_ha":1.5,"creado_ms":1793195800000,
                   "vertices":[[-0.2990,-79.4880],[-0.2990,-79.4870],[-0.3000,-79.4870],[-0.3000,-79.4880]]}],
         "misiones":[{"uuid":"22222222-2222-4222-8222-222222222222","lote":"11111111-1111-4111-8111-111111111111","nombre":"Sector 1",
                      "estado":"COMPLETADO","duracion_min":12.5,"fotos":80,"ruta":"{\"waypoints\":[]}","creado_ms":1793195800000,
                      "actualizado_ms":1793195900000,
                      "parametros":{"altura_m":25,"traslape_lateral_pct":70,"traslape_frontal_pct":75,"margen_interno_m":3,
                                    "altura_copa_m":4,"modo":"video"}}],
         "vuelos":[{"uuid":"33333333-3333-4333-8333-333333333333","mision":"22222222-2222-4222-8222-222222222222","estado":"COMPLETADO",
                    "inicio_ms":1793195900084,"fin_ms":1793196057094,"modo":"video","perimetro":"[[-0.299,-79.488]]","ruta":"{}",
                    "total_wp":6,"siguiente_wp":6,
                    "salidas":[{"uuid":"44444444-4444-4444-8444-444444444444","indice":0,"desde_wp":0,"hasta_wp":5,"estado":"COMPLETADA",
                                "motivo":null,"inicio_ms":1793195900187,"fin_ms":1793196057094,"bateria_inicio":98,"bateria_fin":null,
                                "video_inicio_ms":1793195944374,"video_fin_ms":1793196057057,"distancia_m":812.5}]}]}
    """.trimIndent()

    @Test fun elPerimetroSeCierraYVaComoLonLat() {
        val v = HistorySync.vertices(listOf(listOf(-0.29, -79.48), listOf(-0.30, -79.49), listOf(-0.31, -79.47)))
        assertEquals(
            "POLYGON((-79.48000000 -0.29000000, -79.49000000 -0.30000000, -79.47000000 -0.31000000, -79.48000000 -0.29000000))",
            HistorySync.poligono(v)
        )
        assertNull(HistorySync.poligono(v.take(2)))          // con 2 puntos no hay polígono: queda el área del teléfono
        assertEquals(emptyList(), HistorySync.vertices(null))
    }

    @Test fun unVerticeRaroRechazaElEnvio() {
        assertFailsWith<IllegalArgumentException> { HistorySync.vertices(listOf(listOf(-0.29))) }
        assertFailsWith<IllegalArgumentException> { HistorySync.vertices(listOf(listOf(200.0, -79.48))) }
        assertFailsWith<IllegalArgumentException> { HistorySync.vertices(listOf(listOf("x", -79.48))) }
    }

    // Contra la base local de esta PC, dentro de una transacción que siempre se deshace: no queda nada guardado.
    // Sin base configurada (otra PC, CI) no se prueba.
    @Test fun guardarDosVecesNoDuplicaYLlenaLoQueUsaElInicio() {
        val c = runCatching { Database.connect() }.getOrNull() ?: return println("HistorySyncTest: sin base de datos en esta PC, no se prueba el guardado")
        c.use {
            c.autoCommit = false
            try {
                val usuario = UUID.randomUUID()
                c.prepareStatement("INSERT INTO usuario (id, cedula, nombre, password_hash) VALUES (?, ?, 'Prueba', 'x')").use {
                    it.setObject(1, usuario)
                    it.setString(2, "prueba-" + usuario.toString().take(8))
                    it.executeUpdate()
                }
                val json = MiniJson.parse(historial) as Map<*, *>
                assertEquals(HistorySync.Resultado(1, 1, 1, 1), HistorySync.guardar(c, json, usuario))
                assertEquals(HistorySync.Resultado(1, 1, 1, 1), HistorySync.guardar(c, json, usuario))   // otra vez: actualiza

                fun uno(sql: String): Any? = c.prepareStatement(sql).use { st ->
                    st.setObject(1, usuario)
                    st.executeQuery().use { it.next(); it.getObject(1) }
                }
                assertEquals(1L, uno("SELECT count(*) FROM lote WHERE creado_por = ?"))
                assertEquals(4L, uno("SELECT count(*) FROM vertice v JOIN lote l ON l.id = v.lote_id WHERE l.creado_por = ?"))
                assertEquals(1L, uno("SELECT count(*) FROM salida WHERE operador_id = ?"))
                // ~110 m × 111 m: el área sale del polígono (PostGIS), no de la que calculó el teléfono (1,5)
                val ha = uno("SELECT area_ha FROM lote WHERE creado_por = ?") as Double
                assertTrue(ha in 1.1..1.3, "área $ha")
                // Lo que lee el Inicio: misión con su lote, parámetros y vuelo completado
                assertEquals(
                    "Finca Sánchez · Sector 1 · video · COMPLETADO",
                    uno(
                        """SELECT l.nombre || ' · ' || m.nombre || ' · ' || p.modo_captura || ' · ' || v.estado FROM mision m
                           JOIN lote l ON l.id = m.lote_id JOIN parametros_vuelo p ON p.id = m.parametros_id
                           JOIN vuelo v ON v.mision_id = m.id WHERE m.creado_por = ?"""
                    )
                )
                // Otra versión del formato se rechaza antes de tocar la base
                assertFailsWith<IllegalArgumentException> { HistorySync.guardar(c, mapOf("v" to 2.0), usuario) }
            } finally {
                c.rollback()
            }
        }
    }
}
