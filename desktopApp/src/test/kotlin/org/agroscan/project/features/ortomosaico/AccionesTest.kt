package org.agroscan.project.features.ortomosaico

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccionesTest {

    private val raiz: File = Files.createTempDirectory("ortomosaicos-acciones").toFile()
    private val store = OrtomosaicoStore(raiz)

    @AfterTest fun limpiar() {
        raiz.deleteRecursively()
    }

    private fun crear(f: Ficha): Entrada {
        val dir = store.carpetaDe(f)
        store.guardarFicha(dir, f)
        File(dir, Archivos.VISTA).writeBytes(ByteArray(2000))
        return assertNotNull(store.leer(dir))
    }

    @Test fun eliminarBorraLosArchivosYQuitaLaFilaDeLaBase() {
        val e = crear(ficha())
        val quitados = mutableListOf<String>()
        val r = OrtomosaicoAcciones.eliminar(e, store) { quitados += it; Result.success(Unit) }.getOrThrow()
        assertFalse(e.dir.exists())
        assertEquals(listOf(VUELO_1), quitados)
        assertTrue(r.liberadoBytes >= 2000)
        assertNull(r.avisoBase)
        assertTrue("eliminado" in r.mensaje(e.ficha))
    }

    @Test fun siLaBaseFallaLosArchivosSeBorranIgualYSeAvisa() {
        val e = crear(ficha())
        val r = OrtomosaicoAcciones.eliminar(e, store) { Result.failure(IllegalStateException("sin conexión")) }.getOrThrow()
        assertFalse(e.dir.exists())
        assertNotNull(r.avisoBase)
        assertTrue("base de datos" in r.mensaje(e.ficha))
    }

    @Test fun noSeEliminaLoQueSeEstaArmandoNiSeTocaLaBase() {
        val e = crear(ficha(estado = EstadoOrtomosaico.PROCESANDO))
        var llamadas = 0
        val r = OrtomosaicoAcciones.eliminar(e, store) { llamadas++; Result.success(Unit) }
        assertTrue(r.isFailure)
        assertTrue(e.dir.exists())
        assertEquals(0, llamadas)
    }

    @Test fun compararPoneLaFechaMasAntiguaAntesSeaCualSeaElOrden() {
        val viejo = crear(ficha(vuelo = VUELO_1, inicio = OCT_26))
        val nuevo = crear(ficha(vuelo = VUELO_2, inicio = OCT_26 + 30 * DIA))
        assertEquals(viejo to nuevo, Comparacion.ordenar(viejo, nuevo))
        assertEquals(viejo to nuevo, Comparacion.ordenar(nuevo, viejo))
    }

    private val caja = Limites(-79.488, -0.300, -79.487, -0.299)

    @Test fun elSolapeEsCompletoParcialONulo() {
        assertEquals(100.0, Comparacion.solapePct(caja, caja), 1e-9)
        // Una caja que cubre solo la mitad oeste de la otra
        val mitad = Limites(-79.488, -0.300, -79.4875, -0.299)
        assertEquals(100.0, Comparacion.solapePct(mitad, caja), 1e-9) // se mide contra la más pequeña
        val corrida = Limites(-79.4875, -0.300, -79.4865, -0.299)
        assertEquals(50.0, Comparacion.solapePct(caja, corrida), 1e-6)
        assertEquals(0.0, Comparacion.solapePct(caja, Limites(-70.0, 10.0, -69.0, 11.0)), 0.0)
    }

    @Test fun losAvisosDicenSoloLoQueImporta() {
        val a = ficha()
        assertTrue(Comparacion.avisos(a, a.copy(vueloUuid = VUELO_2)).isEmpty())

        val otraResolucion = Comparacion.avisos(a, a.copy(gsdCmPx = 8.0))
        assertEquals(1, otraResolucion.size)
        assertTrue("resoluciones" in otraResolucion.single())

        val otraZona = Comparacion.avisos(a, a.copy(limites = Limites(-79.4875, -0.300, -79.4865, -0.299)))
        assertTrue(otraZona.single().startsWith("Solo "))

        val imprecisa = Comparacion.avisos(a, a.copy(errorEstimadoM = 12.0))
        assertTrue("±12" in imprecisa.single())

        // Sin datos no se inventan avisos
        assertTrue(Comparacion.avisos(a.copy(limites = null, gsdCmPx = null, errorEstimadoM = null), a.copy(limites = null, gsdCmPx = null, errorEstimadoM = null)).isEmpty())
    }
}
