package org.agroscan.project.features.ortomosaico

import org.agroscan.project.features.flightlog.FlightLogStore
import org.agroscan.project.features.flightlog.SalidaInfo
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BibliotecaTest {

    private fun entrada(f: Ficha) = Entrada(File("no-importa-${f.vueloUuid}"), f)
    private val otroUsuario = "88888888-8888-4888-8888-888888888888"

    @Test fun elHistorialDeCadaLoteVaDelMasRecienteAlMasAntiguo() {
        val b = Bibliotecas.construir(
            listOf(
                entrada(ficha(vuelo = VUELO_1, inicio = OCT_26)),
                entrada(ficha(vuelo = VUELO_2, inicio = OCT_26 + 60 * DIA)),
                entrada(ficha(vuelo = VUELO_3, inicio = OCT_26 + 30 * DIA))
            ),
            lotesBase = null, usuarioId = USUARIO
        )
        assertEquals(1, b.lotes.size)
        assertEquals(listOf(VUELO_2, VUELO_3, VUELO_1), b.lotes.single().ortomosaicos.map { it.ficha.vueloUuid })
        assertEquals(VUELO_2, b.lotes.single().ultimo?.ficha?.vueloUuid)
    }

    @Test fun cadaPoligonoTieneSuPropioHistorial() {
        val b = Bibliotecas.construir(
            listOf(
                entrada(ficha(vuelo = VUELO_1, lote = LOTE_A)),
                entrada(ficha(vuelo = VUELO_2, lote = LOTE_B, nombre = "Lote Sur", inicio = OCT_26 + DIA)),
                entrada(ficha(vuelo = VUELO_3, lote = LOTE_A, inicio = OCT_26 + 10 * DIA))
            ),
            null, USUARIO
        )
        assertEquals(setOf(LOTE_A, LOTE_B), b.lotes.map { it.uuid }.toSet())
        assertEquals(2, b.lotes.first { it.uuid == LOTE_A }.ortomosaicos.size)
        assertEquals(1, b.lotes.first { it.uuid == LOTE_B }.ortomosaicos.size)
        // Primero el lote con el ortomosaico más reciente
        assertEquals(LOTE_A, b.lotes.first().uuid)
    }

    @Test fun soloSeVeLoDelUsuarioQueEntro() {
        val b = Bibliotecas.construir(
            listOf(
                entrada(ficha(vuelo = VUELO_1, usuario = USUARIO)),
                entrada(ficha(vuelo = VUELO_2, usuario = otroUsuario)),
                entrada(ficha(vuelo = VUELO_3, usuario = null))          // sin dueño conocido: lo ve cualquiera
            ),
            null, USUARIO
        )
        assertEquals(setOf(VUELO_1, VUELO_3), b.lotes.single().ortomosaicos.map { it.ficha.vueloUuid }.toSet())
    }

    @Test fun losLotesSinOrtomosaicosTambienSalenSiLaBaseRespondio() {
        val base = listOf(LoteBase(LOTE_A, "Finca Sánchez", 1.2), LoteBase(LOTE_B, "Alfa", 0.8))
        val b = Bibliotecas.construir(listOf(entrada(ficha(lote = LOTE_A))), base, USUARIO)
        assertEquals(listOf(LOTE_A, LOTE_B), b.lotes.map { it.uuid })               // el que tiene ortomosaicos primero
        assertTrue(b.lotes.last().ortomosaicos.isEmpty())
        assertEquals(1.2, b.lotes.first().areaHa)
        // con la base apagada solo salen los que ya tienen alguno
        assertEquals(listOf(LOTE_A), Bibliotecas.construir(listOf(entrada(ficha(lote = LOTE_A))), null, USUARIO, aviso = "sin base").lotes.map { it.uuid })
        assertEquals("sin base", Bibliotecas.construir(emptyList(), null, USUARIO, aviso = "sin base").aviso)
    }

    @Test fun losListosSonLosQueSePuedenVerYCompararEnOrden() {
        val b = Bibliotecas.construir(
            listOf(
                entrada(ficha(vuelo = VUELO_1, estado = EstadoOrtomosaico.LISTO)),
                entrada(ficha(vuelo = VUELO_2, estado = EstadoOrtomosaico.ERROR, inicio = OCT_26 + DIA)),
                entrada(ficha(vuelo = VUELO_3, estado = EstadoOrtomosaico.LISTO, inicio = OCT_26 + 2 * DIA))
            ),
            null, USUARIO
        )
        val lote = b.lotes.single()
        assertEquals(3, lote.ortomosaicos.size)
        assertEquals(listOf(VUELO_3, VUELO_1), lote.listos.map { it.ficha.vueloUuid })
    }
}

class VuelosParaArmarTest {

    private val raiz: File = Files.createTempDirectory("vuelos-prueba").toFile()
    private val store = FlightLogStore(File(raiz, "vuelos"))

    @AfterTest fun limpiar() {
        raiz.deleteRecursively()
    }

    private fun info(indice: Int, vuelo: String = VUELO_1, inicio: Long = OCT_26, salidaUuid: String) = SalidaInfo(
        uuid = salidaUuid, indice = indice, estado = "COMPLETADA", inicioMs = inicio + 1000, finMs = inicio + 900_000,
        videoInicioMs = inicio + 5000, videoFinMs = inicio + 890_000, vueloUuid = vuelo, vueloInicioMs = inicio, modo = "video",
        mision = "Sector 1", lote = "Finca Sánchez"
    )

    private fun guardar(i: SalidaInfo): FlightLogStore.Stored {
        store.save(i, "t_ms,lat,lon\n", "")
        return store.salidas().first { it.info.uuid == i.uuid }
    }

    private fun conVideo(s: FlightLogStore.Stored) = File(s.videoDir, "DJI_0001.MP4").apply { parentFile.mkdirs() }.writeBytes(ByteArray(10))

    private fun preprocesada(s: FlightLogStore.Stored, imagenes: Int) {
        repeat(imagenes) { File(s.dir, "preproceso/DJI_0001/imagenes_procesadas/i$it.jpg").apply { parentFile.mkdirs() }.writeBytes(ByteArray(1)) }
    }

    @Test fun cadaVueloMuestraSusSalidasConLoQueTieneCadaUna() {
        val s1 = guardar(info(0, salidaUuid = "a1111111-1111-4111-8111-111111111111"))
        val s2 = guardar(info(1, salidaUuid = "a2222222-2222-4222-8222-222222222222"))
        conVideo(s1); preprocesada(s1, 3)
        conVideo(s2)

        val vuelo = VuelosParaArmar.leer(store, emptyList()).single()
        assertEquals(VUELO_1, vuelo.uuid)
        assertEquals("Finca Sánchez", vuelo.lote)
        assertEquals("Sector 1", vuelo.mision)
        assertEquals(listOf(0, 1), vuelo.salidas.map { it.indice })
        assertEquals(listOf(3, 0), vuelo.salidas.map { it.imagenes })
        assertEquals(listOf(1, 1), vuelo.salidas.map { it.videos })
        assertTrue(vuelo.salidas.all { it.registro })
        assertEquals(setOf(0), vuelo.incluidasPorDefecto)                     // solo la que ya está lista
        assertEquals(EstadoVuelo.LISTO, vuelo.estado)
    }

    @Test fun unVueloSinPreprocesarNoEstaListo() {
        guardar(info(0, salidaUuid = "a1111111-1111-4111-8111-111111111111"))
        val vuelo = VuelosParaArmar.leer(store, emptyList()).single()
        assertEquals(EstadoVuelo.SIN_PREPROCESAR, vuelo.estado)
        assertTrue(vuelo.incluidasPorDefecto.isEmpty())
    }

    @Test fun losVuelosSalenDelMasRecienteAlMasAntiguoYSeRelacionanConSuOrtomosaico() {
        val s1 = guardar(info(0, VUELO_1, OCT_26, "a1111111-1111-4111-8111-111111111111"))
        val s2 = guardar(info(0, VUELO_2, OCT_26 + 30 * DIA, "a2222222-2222-4222-8222-222222222222"))
        val s3 = guardar(info(0, VUELO_3, OCT_26 + 60 * DIA, "a3333333-3333-4333-8333-333333333333"))
        listOf(s1, s2, s3).forEach { preprocesada(it, 1) }
        val creado = Entrada(File("a"), ficha(vuelo = VUELO_1, estado = EstadoOrtomosaico.LISTO))
        val armando = Entrada(File("b"), ficha(vuelo = VUELO_2, estado = EstadoOrtomosaico.PROCESANDO, inicio = OCT_26 + 30 * DIA))

        val vuelos = VuelosParaArmar.leer(store, listOf(creado, armando))
        assertEquals(listOf(VUELO_3, VUELO_2, VUELO_1), vuelos.map { it.uuid })
        assertEquals(listOf(EstadoVuelo.LISTO, EstadoVuelo.ARMANDO, EstadoVuelo.CREADO), vuelos.map { it.estado })
        assertEquals(creado, vuelos.last().existente)
    }

    @Test fun sinVuelosRecibidosLaListaEstaVacia() {
        assertTrue(VuelosParaArmar.leer(store, emptyList()).isEmpty())
    }
}
