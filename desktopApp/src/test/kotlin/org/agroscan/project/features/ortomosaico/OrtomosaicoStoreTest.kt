package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.MiniJson
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val LOTE_A = "1a2b3c4d-1111-4111-8111-111111111111"
internal const val LOTE_B = "5e6f7a8b-2222-4222-8222-222222222222"
internal const val VUELO_1 = "9f8e7d6c-3333-4333-8333-333333333333"
internal const val VUELO_2 = "0a1b2c3d-4444-4444-8444-444444444444"
internal const val VUELO_3 = "aabbccdd-5555-4555-8555-555555555555"
internal const val USUARIO = "77777777-7777-4777-8777-777777777777"

/** 26 oct 2026 al mediodía UTC: el día es el mismo en cualquier zona horaria. */
internal const val OCT_26 = 1_793_016_000_000L
internal const val DIA = 86_400_000L

internal fun ficha(
    vuelo: String = VUELO_1, lote: String = LOTE_A, nombre: String = "Finca Sánchez", inicio: Long = OCT_26,
    estado: EstadoOrtomosaico = EstadoOrtomosaico.LISTO, usuario: String? = USUARIO
) = Ficha(
    estado = estado, vueloUuid = vuelo, loteUuid = lote, loteNombre = nombre, misionNombre = "Sector 1", usuarioId = usuario,
    vueloInicioMs = inicio, creadoMs = inicio + 3_600_000, terminadoMs = if (estado == EstadoOrtomosaico.PROCESANDO) null else inicio + 4_000_000,
    motor = "prueba 1.0", calidad = "estandar", imagenes = "originales", imagenesUsadas = 141, imagenesTotal = 146,
    coberturaPct = 96.0, areaHa = 1.2, gsdCmPx = 4.0, crs = "EPSG:32717", limites = Limites(-79.488, -0.300, -79.487, -0.299),
    errorEstimadoM = 2.0
)

class OrtomosaicoStoreTest {

    private val raiz: File = Files.createTempDirectory("ortomosaicos-prueba").toFile()
    private val store = OrtomosaicoStore(raiz)

    @AfterTest fun limpiar() {
        raiz.deleteRecursively()
    }

    /** Crea un ortomosaico en disco con unos archivos de relleno, como lo dejaría el módulo. */
    private fun crear(f: Ficha, bytes: Int = 1000): Entrada {
        val dir = store.carpetaDe(f)
        store.guardarFicha(dir, f)
        File(dir, Archivos.VISTA).writeBytes(ByteArray(bytes))
        File(dir, "${Archivos.TESELAS}/t1.jpg").apply { parentFile.mkdirs() }.writeBytes(ByteArray(bytes))
        return assertNotNull(store.leer(dir))
    }

    @Test fun laFichaSobreviveAEscribirseYLeerse() {
        val f = ficha().copy(error = "ninguno \"con comillas\" y\nsalto")
        val leida = Ficha.from(MiniJson.parse(f.toJson()) as Map<*, *>)
        assertEquals(f, leida)
    }

    @Test fun unaFichaSinLimitesNiMetricasSeLeeTambien() {
        val f = ficha(estado = EstadoOrtomosaico.PROCESANDO).copy(
            limites = null, coberturaPct = null, areaHa = null, gsdCmPx = null, crs = null, errorEstimadoM = null, usuarioId = null
        )
        assertEquals(f, Ficha.from(MiniJson.parse(f.toJson()) as Map<*, *>))
    }

    @Test fun unaFichaRaraSeRechaza() {
        fun lee(json: String) = Ficha.from(MiniJson.parse(json) as Map<*, *>)
        val buena = ficha().toJson()
        assertNotNull(lee(buena))
        assertNull(lee(buena.replace("\"v\":1", "\"v\":2")))                        // otra versión del formato
        assertNull(lee(buena.replace("\"estado\":\"LISTO\"", "\"estado\":\"QUIZAS\"")))
        assertNull(lee(buena.replace(VUELO_1, "../../x")))                          // un UUID que terminaría en una ruta
        assertNull(lee(buena.replace(LOTE_A, "1-1-1-1-1")))
        assertNull(lee(buena.replace("\"lote\":\"Finca Sánchez\"", "\"lote\":\"  \"")))
        // límites al revés: la ficha vale, pero sin límites (no se dibujaría bien)
        assertNull(lee(buena.replace("\"oeste\":-79.488000", "\"oeste\":-79.0"))?.limites)
    }

    @Test fun laCarpetaSeDecideSolaYSigueAlLoteSiSeRenombra() {
        val dir = store.carpetaDe(ficha())
        assertEquals("Finca-Sanchez_1a2b3c4d", dir.parentFile.name)
        assertTrue(dir.name.endsWith("_9f8e7d6c"))
        crear(ficha())
        // Se renombra el lote: el nuevo ortomosaico de ese mismo lote cae en la carpeta que ya existe
        val otro = store.carpetaDe(ficha(vuelo = VUELO_2, nombre = "Finca Sánchez Norte", inicio = OCT_26 + 30 * DIA))
        assertEquals(dir.parentFile, otro.parentFile)
        // Y un lote distinto va a su propia carpeta
        assertTrue(store.carpetaDe(ficha(lote = LOTE_B, nombre = "Lote Sur")).parentFile != dir.parentFile)
    }

    @Test fun listarDevuelveLosValidosYNoMiraElTrabajoAMedias() {
        crear(ficha(vuelo = VUELO_1))
        crear(ficha(vuelo = VUELO_2, inicio = OCT_26 + 30 * DIA))
        crear(ficha(vuelo = VUELO_3, lote = LOTE_B, nombre = "Lote Sur"))
        File(store.carpetaTemporal(ficha(vuelo = VUELO_3, lote = LOTE_B)), Archivos.FICHA).apply { parentFile.mkdirs() }.writeText(ficha(vuelo = VUELO_3, lote = LOTE_B).toJson())
        File(raiz, "carpeta-suelta/otra").mkdirs()
        File(raiz, "carpeta-suelta/otra/${Archivos.FICHA}").writeText("{esto no es json")
        assertEquals(setOf(VUELO_1, VUELO_2, VUELO_3), store.listar().map { it.ficha.vueloUuid }.toSet())
        assertEquals(VUELO_2, store.porVuelo(VUELO_2)?.ficha?.vueloUuid)
        assertNull(store.porVuelo("00000000-0000-4000-8000-000000000000"))
    }

    @Test fun publicarReemplazaAlAnteriorSoloCuandoElNuevoEstaCompleto() {
        crear(ficha(), bytes = 10)
        val nueva = ficha().copy(imagenesUsadas = 999)
        val temporal = store.carpetaTemporal(nueva)
        store.guardarFicha(temporal, nueva)
        File(temporal, Archivos.VISTA).writeBytes(ByteArray(50))

        val destino = store.publicar(temporal, nueva).getOrThrow()
        assertEquals(store.carpetaDe(nueva), destino)
        assertFalse(temporal.exists())
        assertEquals(999, store.porVuelo(VUELO_1)?.ficha?.imagenesUsadas)
        assertEquals(50, File(destino, Archivos.VISTA).length())
        assertEquals(1, store.listar().size)
        assertTrue(destino.parentFile.listFiles()!!.none { it.name.startsWith(".") })   // sin restos
    }

    @Test fun siPublicarFallaElAnteriorNoSePierde() {
        val vieja = crear(ficha(), bytes = 10)
        val nueva = ficha().copy(imagenesUsadas = 5)
        val noExiste = store.carpetaTemporal(nueva)          // el trabajo nuevo nunca llegó a crearse
        assertTrue(store.publicar(noExiste, nueva).isFailure)
        assertEquals(141, store.porVuelo(VUELO_1)?.ficha?.imagenesUsadas)
        assertTrue(vieja.vista.isFile)
        assertTrue(vieja.dir.parentFile.listFiles()!!.none { it.name.startsWith(".previo_") })
    }

    @Test fun eliminarBorraLosArchivosDelOrtomosaicoYDevuelveLoLiberado() {
        val a = crear(ficha(vuelo = VUELO_1), bytes = 1000)
        val b = crear(ficha(vuelo = VUELO_2, inicio = OCT_26 + 30 * DIA), bytes = 500)
        val liberado = store.eliminar(a).getOrThrow()
        assertTrue(liberado >= 2000, "liberó $liberado")
        assertFalse(a.dir.exists())
        assertTrue(b.dir.exists())                                     // el otro del mismo lote sigue
        assertEquals(listOf(VUELO_2), store.listar().map { it.ficha.vueloUuid })
        // al borrar el último del lote, la carpeta del lote también desaparece
        store.eliminar(b).getOrThrow()
        assertFalse(b.dir.parentFile.exists())
        assertTrue(raiz.exists())
        assertTrue(raiz.listFiles()!!.isEmpty())
    }

    @Test fun noSePuedeEliminarUnoQueSeEstaArmando() {
        val armando = crear(ficha(estado = EstadoOrtomosaico.PROCESANDO))
        val r = store.eliminar(armando)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("cancélalo"))
        assertTrue(armando.dir.exists())
    }

    @Test fun elMantenimientoMarcaInterrumpidosYLimpiaLosRestos() {
        crear(ficha(vuelo = VUELO_1, estado = EstadoOrtomosaico.PROCESANDO))
        crear(ficha(vuelo = VUELO_2, estado = EstadoOrtomosaico.PROCESANDO, inicio = OCT_26 + 30 * DIA))
        crear(ficha(vuelo = VUELO_3, estado = EstadoOrtomosaico.LISTO, inicio = OCT_26 + 60 * DIA))
        val lote = store.carpetaDe(ficha()).parentFile
        File(lote, ".tmp_resto").mkdirs()
        File(lote, ".borrando_resto").mkdirs()

        // Se está armando el vuelo 2 de verdad: ese no se toca, ni su carpeta de trabajo
        assertEquals(1, store.mantenimiento(activos = setOf(VUELO_2)))
        assertEquals(EstadoOrtomosaico.INTERRUMPIDO, store.porVuelo(VUELO_1)?.ficha?.estado)
        assertNotNull(store.porVuelo(VUELO_1)?.ficha?.error)
        assertEquals(EstadoOrtomosaico.PROCESANDO, store.porVuelo(VUELO_2)?.ficha?.estado)
        assertEquals(EstadoOrtomosaico.LISTO, store.porVuelo(VUELO_3)?.ficha?.estado)
        assertTrue(File(lote, ".tmp_resto").exists())
        assertFalse(File(lote, ".borrando_resto").exists())

        // Sin nada armándose, el trabajo a medias sobra
        store.mantenimiento(activos = emptySet())
        assertFalse(File(lote, ".tmp_resto").exists())
        assertEquals(EstadoOrtomosaico.INTERRUMPIDO, store.porVuelo(VUELO_2)?.ficha?.estado)
    }

    @Test fun elTamanoSumaTodoLoQueHayDentro() {
        val e = crear(ficha(), bytes = 1234)
        assertTrue(OrtomosaicoStore.tamano(e.dir) >= 2468)
    }
}
