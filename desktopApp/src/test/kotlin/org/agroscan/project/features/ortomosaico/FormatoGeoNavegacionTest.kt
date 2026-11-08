package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.Navegacion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatoTest {
    @Test fun lasMedidasSeEscribenEnEspanolConSusUnidades() {
        assertEquals("35 KB", Formato.bytes(35_000))
        assertEquals("480 MB", Formato.bytes(480_000_000))
        assertEquals("1,2 GB", Formato.bytes(1_240_000_000))
        assertEquals("999 B", Formato.bytes(999))
        assertEquals("1,20 ha", Formato.hectareas(1.2))
        assertEquals("4,0 cm/px", Formato.resolucion(4.0))
        assertEquals("96 %", Formato.porcentaje(96.0))
    }

    @Test fun elSingularYElPluralDeOrtomosaicos() {
        assertEquals("1 ortomosaico", Formato.ortomosaicos(1))
        assertEquals("3 ortomosaicos", Formato.ortomosaicos(3))
        assertEquals("0 ortomosaicos", Formato.ortomosaicos(0))
    }

    @Test fun losDiasEntreDosFechas() {
        assertEquals("mismo día", Formato.diasEntre(OCT_26, OCT_26 + 3_600_000))
        assertEquals("1 día", Formato.diasEntre(OCT_26, OCT_26 + DIA))
        assertEquals("42 días", Formato.diasEntre(OCT_26 + 42 * DIA, OCT_26))      // el orden no importa
    }

    @Test fun laFechaSeEscribeConElDiaElMesYElAnio() {
        val f = Formato.fecha(OCT_26)
        assertTrue("2026" in f && f.startsWith("26"), f)
    }
}

class GeoTest {
    private val lote = Limites(-79.4880, -0.3000, -79.4870, -0.2990)     // ~110 m × ~111 m cerca del ecuador

    @Test fun elTamanoEnMetrosEsElDelTerreno() {
        val p = Proyeccion(lote)
        assertTrue(abs(p.anchoM - 111) < 3, "ancho ${p.anchoM}")
        assertTrue(abs(p.altoM - 111) < 3, "alto ${p.altoM}")
    }

    @Test fun enUnaLatitudAltaLosGradosDeLongitudMidenMenos() {
        val ecuador = Proyeccion(Limites(0.0, 0.0, 0.001, 0.001))
        val alta = Proyeccion(Limites(0.0, 60.0, 0.001, 60.001))
        assertTrue(abs(alta.anchoM / ecuador.anchoM - 0.5) < 0.01)        // cos(60°) = 0,5
    }

    @Test fun laYCreceHaciaElSurComoLaPantalla() {
        val p = Proyeccion(lote)
        assertEquals(0.0, p.y(lote.norte), 1e-6)
        assertTrue(p.y(lote.sur) > p.y(lote.norte))
        assertEquals(0.0, p.x(lote.oeste), 1e-6)
        assertTrue(p.x(lote.este) > 0)
    }

    @Test fun laUnionContieneAAmbosYCadaCapaCaeEnSuSitio() {
        val corrida = Limites(-79.4875, -0.3005, -79.4865, -0.2995)            // la misma zona, corrida hacia el sureste
        val u = Proyeccion.union(lote, corrida)
        assertEquals(Limites(-79.4880, -0.3005, -79.4865, -0.2990), u)
        val p = Proyeccion(u)
        val r1 = p.rect(lote)
        val r2 = p.rect(corrida)
        assertEquals(0.0, r1.x, 1e-6)
        assertEquals(0.0, r1.y, 1e-6)
        assertTrue(r2.x > r1.x && r2.y > r1.y)                                  // corrida al este y al sur
        assertTrue(abs(r1.ancho - r2.ancho) < 1e-6)                             // del mismo tamaño
    }

    @Test fun laEscalaParaAjustarRespetaElLadoMasLimitante() {
        val ancho = Proyeccion.escalaParaAjustar(100.0, 50.0, 1000f, 1000f, margen = 0f)
        assertEquals(10f, ancho, 1e-4f)                                         // manda el ancho
        val alto = Proyeccion.escalaParaAjustar(50.0, 100.0, 1000f, 500f, margen = 0f)
        assertEquals(5f, alto, 1e-4f)                                           // manda el alto
        assertEquals(1f, Proyeccion.escalaParaAjustar(0.0, 10.0, 100f, 100f))   // sin tamaño no revienta
    }

    @Test fun laBarraDeEscalaEsUnNumeroRedondo() {
        assertEquals(50.0, Proyeccion.largoBarraM(pxPorMetro = 3f, maxPx = 160f))      // caben 53 m → 50
        assertEquals(20.0, Proyeccion.largoBarraM(pxPorMetro = 5f, maxPx = 150f))      // caben 30 m → 20
        assertEquals(100.0, Proyeccion.largoBarraM(pxPorMetro = 1.2f, maxPx = 150f))   // caben 125 m → 100
        assertEquals(0.5, Proyeccion.largoBarraM(pxPorMetro = 200f, maxPx = 150f))     // caben 0,75 m → 0,5
    }

    @Test fun losLimitesInvalidosSeDetectan() {
        assertTrue(lote.validos)
        assertTrue(!Limites(-79.0, -0.3, -80.0, -0.2).validos)                 // oeste al este del este
        assertTrue(!Limites(-79.0, -0.3, -78.0, 95.0).validos)                 // latitud imposible
        assertTrue(!Limites(Double.NaN, -0.3, -78.0, -0.2).validos)
        assertTrue(lote.wkt().startsWith("POLYGON((-79.48800000 -0.30000000,"))
        assertTrue(lote.wkt().let { it.substringAfter("((").substringBefore(",") == it.substringAfterLast(", ").substringBefore("))") })   // cerrado
    }
}

class NavegacionTest {
    @Test fun crearUnOrtomosaicoLlevaALaPestanaCrearConEseVuelo() {
        Navegacion.ir(Navegacion.PROCESAMIENTO)
        Navegacion.crearOrtomosaico(VUELO_1)
        assertEquals(Navegacion.ORTOMOSAICOS, Navegacion.pantalla.value)
        assertEquals(Navegacion.Pestana.CREAR, Navegacion.ortomosaicos.value.pestana)
        assertEquals(VUELO_1, Navegacion.ortomosaicos.value.vueloUuid)
    }

    @Test fun verLosOrtomosaicosConservaElLoteQueYaEstabaElegidoSiNoSeDiceOtro() {
        Navegacion.elegirLote(LOTE_A)
        Navegacion.verOrtomosaicos()
        assertEquals(Navegacion.Pestana.CREADOS, Navegacion.ortomosaicos.value.pestana)
        assertEquals(LOTE_A, Navegacion.ortomosaicos.value.loteUuid)
        Navegacion.verOrtomosaicos(LOTE_B)
        assertEquals(LOTE_B, Navegacion.ortomosaicos.value.loteUuid)
    }

    @Test fun laEleccionSobreviveAIrseYVolver() {
        Navegacion.elegirPestana(Navegacion.Pestana.CREAR)
        Navegacion.elegirVuelo(VUELO_2)
        Navegacion.ir(Navegacion.INICIO)
        Navegacion.ir(Navegacion.ORTOMOSAICOS)
        assertEquals(Navegacion.Pestana.CREAR, Navegacion.ortomosaicos.value.pestana)
        assertEquals(VUELO_2, Navegacion.ortomosaicos.value.vueloUuid)
    }
}
