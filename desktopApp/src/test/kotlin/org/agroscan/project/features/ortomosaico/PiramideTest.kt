package org.agroscan.project.features.ortomosaico

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiramideTest {

    private val raiz: File = Files.createTempDirectory("piramide-prueba").toFile()

    @AfterTest fun limpiar() {
        raiz.deleteRecursively()
    }

    // El mosaico de Prueba casa en calidad alta: 8489 × 9129 px, con dos niveles (2 cm y 4 cm por píxel)
    private val p = Piramide(raiz, lado = 512, anchoPx = 8489, altoPx = 9129, niveles = 2)

    @Test fun seLeeLaPiramideQueEscribeElModulo() {
        File(raiz, Archivos.PIRAMIDE_JSON).writeText("""{"v": 1, "lado": 512, "ancho_px": 8489, "alto_px": 9129, "gsd_m": 0.0203776, "niveles": 2}""")
        val leida = assertNotNull(Piramide.leer(raiz))
        assertEquals(512, leida.lado)
        assertEquals(8489, leida.anchoPx)
        assertEquals(9129, leida.altoPx)
        assertEquals(2, leida.niveles)
    }

    @Test fun sinPiramideOConUnaIlegibleNoSeInventaNada() {
        assertNull(Piramide.leer(raiz))                                                           // los ortomosaicos armados antes no la tienen
        File(raiz, Archivos.PIRAMIDE_JSON).writeText("no es json")
        assertNull(Piramide.leer(raiz))
        File(raiz, Archivos.PIRAMIDE_JSON).writeText("""{"lado": 512, "ancho_px": 100, "alto_px": 100}""")      // falta algo
        assertNull(Piramide.leer(raiz))
        File(raiz, Archivos.PIRAMIDE_JSON).writeText("""{"lado": 0, "ancho_px": 100, "alto_px": 100, "niveles": 2}""")
        assertNull(Piramide.leer(raiz))
    }

    @Test fun elNivelEsElMasGruesoQueNoAgrandaLaImagen() {
        assertEquals(0, p.nivelPara(0.2))        // acercado más allá de la resolución real: el nivel completo
        assertEquals(0, p.nivelPara(1.0))
        assertEquals(0, p.nivelPara(1.9))        // un píxel de pantalla abarca casi 2 del mosaico: el siguiente nivel sería más grueso que la pantalla
        assertEquals(1, p.nivelPara(2.0))
        assertEquals(1, p.nivelPara(3.9))
        assertNull(p.nivelPara(4.0))             // desde aquí la vista (que ya es de ~3 px por píxel) alcanza
        assertNull(p.nivelPara(25.0))
        assertNull(p.nivelPara(Double.NaN))
    }

    @Test fun deLejosNoSePideNadaYDeCercaSoloLoQueSeVe() {
        // El mosaico entero cabe en la pantalla: la vista basta
        assertTrue(p.visibles(0.0, 0.0, 1000.0, 1075.0, 1200.0, 1100.0).isEmpty())
        // Acercado al 100 %: el mosaico mide 8489 px en pantalla, y la pantalla de 1200 × 800 ve la esquina noroeste
        val v = p.visibles(0.0, 0.0, 8489.0, 9129.0, 1200.0, 800.0)
        assertTrue(v.all { it.posicion.nivel == 0 })
        assertEquals(3 * 2, v.size)                                      // 1200 px = 3 teselas de ancho (512 + 512 + 176), 800 px = 2 de alto
        assertEquals(setOf(0, 1, 2), v.map { it.posicion.columna }.toSet())
        assertEquals(setOf(0, 1), v.map { it.posicion.fila }.toSet())
    }

    @Test fun lasTeselasCubrenExactamenteLoVisibleSinHuecosNiSolapes() {
        val v = p.visibles(-3000.0, -5000.0, 8489.0, 9129.0, 1200.0, 800.0)      // desplazado: se ve una zona del medio
        assertTrue(v.isNotEmpty())
        val porFila = v.groupBy { it.posicion.fila }.values
        porFila.forEach { fila ->
            val ordenadas = fila.sortedBy { it.x0 }
            ordenadas.zipWithNext().forEach { (a, b) -> assertEquals(a.x1, b.x0, 1e-12) }      // una empieza donde acaba la otra
        }
        // Lo que cubren incluye lo visible: la pantalla va de la fracción (3000/8489) a (4200/8489) del ancho
        assertTrue(v.minOf { it.x0 } <= 3000.0 / 8489 && v.maxOf { it.x1 } >= 4200.0 / 8489)
        assertTrue(v.minOf { it.y0 } <= 5000.0 / 9129 && v.maxOf { it.y1 } >= 5800.0 / 9129)
    }

    @Test fun laUltimaColumnaYFilaQuedanRecortadasAlTamanoQueSobra() {
        // Mosaico a la mitad de su resolución en pantalla (4245 × 4565 px): nivel 1, de 9 × 9 teselas de 512; se mira la esquina sureste
        val mitadAncho = 8489 / 2.0
        val mitadAlto = 9129 / 2.0
        val esquina = p.visibles(-(mitadAncho - 600), -(mitadAlto - 600), mitadAncho, mitadAlto, 600.0, 600.0)
        assertTrue(esquina.all { it.posicion.nivel == 1 })
        val ultima = esquina.single { it.posicion.fila == 8 && it.posicion.columna == 8 }
        assertEquals(1.0, ultima.x1, 1e-12)
        assertEquals(1.0, ultima.y1, 1e-12)
        assertEquals((4245 - 8 * 512).toDouble() / 4245, ultima.x1 - ultima.x0, 1e-9)      // 149 px de 4245: lo que queda tras 8 teselas completas
        // Y una pantalla gigante que pediría muchísimas teselas no pide nada en vez de cargar medio mosaico (aquí serían 40 × 40 = 1600)
        val enorme = Piramide(raiz, 512, 40000, 40000, 2)
        assertTrue(enorme.visibles(0.0, 0.0, 20000.0, 20000.0, 100000.0, 100000.0).isEmpty())
        assertEquals(81, p.visibles(0.0, 0.0, 8489 / 2.0, 9129 / 2.0, 100000.0, 100000.0).size)       // el nivel entero de este mosaico (9 × 9) sí cabe en el máximo
    }

    @Test fun unMosaicoFueraDeLaPantallaNoPideNada() {
        assertTrue(p.visibles(5000.0, 0.0, 8489.0, 9129.0, 1200.0, 800.0).isEmpty())      // empieza a la derecha de la pantalla
        assertTrue(p.visibles(0.0, 0.0, 0.0, 100.0, 1200.0, 800.0).isEmpty())
    }

    @Test fun elArchivoDeCadaTeselaSigueLaMismaRutaQueEscribeElModulo() {
        assertEquals(File(raiz, "piramide/1/3_5.jpg"), p.archivo(Piramide.Posicion(1, 3, 5)))
    }
}
