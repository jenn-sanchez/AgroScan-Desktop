package org.agroscan.project.features.flightlog

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoImportTest {

    private val root: File = Files.createTempDirectory("agroscan-vuelos").toFile()
    private val sd: File = Files.createTempDirectory("agroscan-sd").toFile()

    @AfterTest fun limpiar() {
        root.deleteRecursively()
        sd.deleteRecursively()
    }

    private fun tienda(): FlightLogStore = FlightLogStore(root).also {
        it.save(
            SalidaInfo(
                uuid = "7c9e6679-7425-40de-944b-e07fc1f90ae7", indice = 0, estado = "COMPLETADA", inicioMs = 900_000, finMs = 1_700_000,
                videoInicioMs = 1_000_000, videoFinMs = 1_600_000, vueloUuid = "0f8fad5b-d9cb-469f-a165-70867728950e",
                vueloInicioMs = 1_793_022_406_491, modo = "video", mision = "Sector 1", lote = "Finca"
            ),
            "csv", ""
        )
    }

    /** Un "video" de la tarjeta con contenido que no se repite, para notar si algún trozo llega mal o fuera de orden. */
    private fun video(nombre: String, bytes: Int, fecha: Long = 1_500_000L): File {
        val f = File(File(sd, "DCIM/100MEDIA").apply { mkdirs() }, nombre)
        f.writeBytes(ByteArray(bytes) { (it * 31 + it / 251).toByte() })
        f.setLastModified(fecha)
        return f
    }

    // ---- Velocidad y textos

    @Test fun laVelocidadSeSuavizaYNoSaltaConCadaMedicion() {
        var t = 0L
        val v = VideoImporter.Velocidad { t }
        t = 100; assertEquals(0, v.actualizar(1_000_000))             // aún no pasó medio segundo: sin medida
        t = 500; assertEquals(20_000_000, v.actualizar(10_000_000))   // primera medida: 20 MB/s
        t = 1000; assertEquals(17_000_000, v.actualizar(15_000_000))  // 10 MB/s ahora, mezclado con lo anterior (70 % / 30 %)
    }

    @Test fun diceCuantoFaltaEnPalabras() {
        assertEquals("35 s", VideoImport.duracion(35))
        assertEquals("4 min", VideoImport.duracion(240))
        assertEquals("2 min", VideoImport.duracion(90))
        assertEquals("1 h 02 min", VideoImport.duracion(3_720))
    }

    @Test fun elProgresoSabePorcentajeYTiempoRestante() {
        val p = VideoImporter.Progreso(archivo = 1, archivos = 3, nombre = "DJI_0101.MP4", copiados = 400, total = 1000, bytesPorSegundo = 100)
        assertEquals(0.4f, p.fraccion)
        assertEquals(6L, p.segundosRestantes)
        assertNull(p.copy(bytesPorSegundo = 0).segundosRestantes)
        assertEquals("Copiando 1 de 3 · DJI_0101.MP4 · 40 % · 0.0 MB/s · faltan 6 s".replace("0.0", "%.1f".format(100 / 1e6)), VideoImport.texto(p))
    }

    // ---- Copia

    @Test fun copiaIdenticoAvisandoElAvanceHastaElCien() {
        val original = video("DJI_0001.MP4", bytes = 20 * 1024 * 1024 + 123)
        val avances = mutableListOf<VideoImporter.Progreso>()
        val r = VideoImporter(tienda()).importar(sd, progreso = { avances += it })
        assertEquals(1, r.copiados)
        assertTrue(avances.size >= 3, "debía avisar por cada trozo, avisó ${avances.size}")
        assertEquals(avances.sortedBy { it.copiados }, avances)    // siempre sube
        assertEquals(original.length(), avances.last().copiados)
        assertEquals(1f, avances.last().fraccion)
        val copia = File(tienda().salidas().single().videoDir, "DJI_0001.MP4")
        assertContentEquals(original.readBytes(), copia.readBytes())   // sin pérdida
        assertTrue(kotlin.math.abs(copia.lastModified() - original.lastModified()) < 2_000, "conserva la fecha del original")
        assertTrue(root.walk().none { it.name.endsWith(".part") })
    }

    @Test fun elPorcentajeEsSobreTodosLosVideosNoSobreCadaUno() {
        video("DJI_0001.MP4", bytes = 9 * 1024 * 1024)
        video("DJI_0002.MP4", bytes = 9 * 1024 * 1024)
        val avances = mutableListOf<VideoImporter.Progreso>()
        VideoImporter(tienda()).importar(sd, progreso = { avances += it })
        assertEquals(2, avances.last().archivos)
        assertTrue(avances.first { it.archivo == 2 }.fraccion > 0.5f)   // el segundo video empieza ya a más de la mitad del total
        assertEquals(2 * 9 * 1024 * 1024L, avances.last().total)
    }

    @Test fun cancelarDetieneYNoDejaVideoNiMedioVideo() {
        video("DJI_0001.MP4", bytes = 20 * 1024 * 1024)
        var trozos = 0
        val r = VideoImporter(tienda()).importar(sd, progreso = { trozos++ }, cancelado = { trozos >= 1 })
        assertTrue(r.cancelado)
        assertEquals(0, r.copiados)
        val dir = tienda().salidas().single().videoDir
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "quedó algo: ${dir.listFiles()?.map { it.name }}")
    }

    // ---- Tarjeta y estado global

    @Test fun detectaLaUnidadConDcimYDescartaLaDelSistema() {
        val sistema = Files.createTempDirectory("agroscan-c").toFile().apply { File(this, "DCIM").mkdirs() }
        val otra = Files.createTempDirectory("agroscan-d").toFile()
        try {
            assertEquals(sd, VideoImport.detectarTarjeta(arrayOf(otra, sd.also { File(it, "DCIM").mkdirs() }), unidadSistema = null))
            assertNull(VideoImport.detectarTarjeta(arrayOf(otra), unidadSistema = null))                          // ninguna con DCIM
            assertNull(VideoImport.detectarTarjeta(arrayOf(sd, sistema), unidadSistema = null))                  // dos: hay que preguntar
            assertEquals(sd, VideoImport.detectarTarjeta(arrayOf(sd, sistema), unidadSistema = sistema.path))    // la del sistema no cuenta
        } finally {
            sistema.deleteRecursively()
            otra.deleteRecursively()
        }
    }

    @Test fun laImportacionCorreEnSegundoPlanoYDejaSuResultado() {
        video("DJI_0001.MP4", bytes = 1000)
        VideoImport.iniciar(sd, tienda())
        // Cualquiera puede mirar el estado (la pantalla puede haberse cerrado y abierto): la copia no depende de ella
        val t0 = System.currentTimeMillis()
        while ((VideoImport.estado.value.activo || VideoImport.estado.value.mensaje == null) && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(50)
        assertFalse(VideoImport.estado.value.activo)
        assertTrue(assertNotNull(VideoImport.estado.value.mensaje).startsWith("1 video(s) copiado(s)"))
    }
}
