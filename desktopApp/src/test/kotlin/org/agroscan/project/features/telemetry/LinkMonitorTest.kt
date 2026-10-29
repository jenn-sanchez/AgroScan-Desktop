package org.agroscan.project.features.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkMonitorTest {

    private val lineas = mutableListOf<String>()
    private val m = LinkMonitor { lineas += it }

    @Test fun registraElCorteYLaRecuperacion() {
        m.packet(1_000, 1, "fase FLYING")
        assertTrue(lineas.last().startsWith("Llegó el primer paquete"))
        m.tick(3_500, "fase FLYING")                 // 2,5 s sin datos: todavía no es corte
        assertEquals(1, lineas.size)
        m.tick(5_000, "fase FLYING")                 // 4 s: corte
        assertTrue(lineas.last().startsWith("SEÑAL PERDIDA: no llega nada del teléfono hace 4 s"), lineas.last())
        m.tick(7_000, "fase FLYING")                 // el corte se anota una sola vez
        assertEquals(2, lineas.size)
        m.packet(12_000, 2, "fase FLYING")
        assertTrue(lineas.last().startsWith("SEÑAL RECUPERADA tras 11 s sin datos"), lineas.last())
    }

    @Test fun laSenalSaleDeLosNumerosDeSecuenciaQueFaltan() {
        // Números del 0 al 23 sin el 4, 9, 14 y 19: llegan 20 de 24 → 83 %
        (0L..23L).filter { it % 5 != 4L }.forEachIndexed { i, seq -> m.packet(1_000 + i * 500L, seq, "") }
        assertEquals(83, m.quality)
    }

    @Test fun conPocosPaquetesNoSeDiceNada() {
        m.packet(0, 0, ""); m.packet(500, 5, "")
        assertNull(m.quality)
    }

    @Test fun siElTelefonoReiniciaElContadorSeMideDeNuevo() {
        (0L..9L).forEach { m.packet(it * 500, 1000 + it, "") }
        (0L..9L).forEach { m.packet(5_000 + it * 500, it, "") }   // la app del teléfono se abrió de nuevo
        assertEquals(100, m.quality)
    }

    @Test fun cadaMinutoDejaUnResumenConLosPerdidos() {
        // 2 por segundo durante un minuto (números 0 al 120) sin los terminados en 5: faltan 12 de 121
        (0L..120L).filter { it % 10 != 5L }.forEach { m.packet(1_000 + it * 500, it, "fase FLYING") }
        val resumen = lineas.single { it.startsWith("Último minuto") }
        assertTrue(resumen.startsWith("Último minuto: 109 paquetes recibidos, 12 perdidos (9 %) · fase FLYING"), resumen)
    }
}
