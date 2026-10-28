package org.agroscan.project.features.flightlog

import org.agroscan.project.core.MiniJson
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlightLogTest {

    private val root: File = Files.createTempDirectory("agroscan-vuelos").toFile()

    @AfterTest fun limpiar() {
        root.deleteRecursively()
    }

    private val vuelo = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val salida1 = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val salida2 = "16fd2706-8baf-433b-82eb-8c7fada847da"

    // Cuerpo tal como lo arma la app del dron (FlightLogSync.body en el proyecto del dron)
    private fun body(salida: String = salida1, indice: Int = 0, videoInicio: Long? = 1_000_000L, videoFin: Long? = 1_600_000L, csv: String = "t_ms,lat\n1,2\n") =
        """{"v":1,"salida":{"uuid":"$salida","indice":$indice,"estado":"COMPLETADA","inicio_ms":900000,"fin_ms":1700000,""" +
            """"video_inicio_ms":${videoInicio ?: "null"},"video_fin_ms":${videoFin ?: "null"}},""" +
            """"vuelo":{"uuid":"$vuelo","inicio_ms":1793022406491,"modo":"video"},"mision":"Sector 1 / Norte","lote":"Finca Sánchez",""" +
            """"registro_csv":${MiniJson.quote(csv)},"eventos_jsonl":${MiniJson.quote("{\"t_ms\":5,\"evento\":\"marca\",\"n\":1}\n{\"t_ms\":6,\"evento\":\"marca\",\"n\":2}\n{\"t_ms\":7,\"evento\":\"fin\"}\n")}}"""

    private fun info(text: String) = assertNotNull(SalidaInfo.from(MiniJson.parse(text) as Map<*, *>))

    // ---- SalidaInfo

    @Test fun leeLoQueMandaElTelefonoYLoVuelveAEscribirIgual() {
        val i = info(body())
        assertEquals(salida1, i.uuid)
        assertEquals(0, i.indice)
        assertEquals(1_000_000L, i.videoInicioMs)
        assertEquals("Finca Sánchez", i.lote)
        assertEquals(i, info(i.toJson()))   // salida.json se lee con el mismo código
    }

    @Test fun rechazaUuidsQueNoSonCanonicos() {
        listOf("../../Windows", "1-1-1-1-1", "", "7C9E6679742540DE944BE07FC1F90AE7").forEach {
            assertNull(SalidaInfo.from(MiniJson.parse(body(salida = it)) as Map<*, *>), it)
        }
        assertEquals(salida1, SalidaInfo.canonicalUuid(salida1.uppercase()))   // mayúsculas sí: es el mismo UUID
    }

    // ---- FlightLogStore

    @Test fun guardaEnLaCarpetaDelVueloYUnaSolaVez() {
        val store = FlightLogStore(root)
        assertTrue(store.save(info(body()), "original", "{\"evento\":\"marca\"}\n"))
        assertFalse(store.save(info(body()), "falso", "falso"))   // ya estaba: no se pisa
        val s = store.salidas().single()
        assertEquals("original", s.registro.readText())
        assertTrue(s.dir.parentFile.name.endsWith("_Finca-Sanchez_Sector-1-Norte_0f8fad5b"), s.dir.parentFile.name)
        assertEquals("salida-1", s.dir.name)
        assertEquals(setOf(salida1), store.received())
        assertTrue(root.walk().none { it.name.endsWith(".tmp") })
    }

    @Test fun dosSalidasDelMismoVueloVanALaMismaCarpeta() {
        val store = FlightLogStore(root)
        store.save(info(body()), "a", "")
        store.save(info(body(salida = salida2, indice = 1)), "b", "")
        assertEquals(1, root.listFiles()!!.size)
        assertEquals(listOf("salida-1", "salida-2"), store.salidas().map { it.dir.name }.sorted())
    }

    @Test fun cuentaLasMarcas() {
        FlightLogStore(root).save(info(body()), "a", "{\"t_ms\":5,\"evento\":\"marca\"}\n{\"t_ms\":6,\"evento\":\"fin\"}\n{\"t_ms\":7,\"evento\":\"marca\"}\n")
        assertEquals(2, FlightLogStore.marcas(FlightLogStore(root).salidas().single().eventos))
    }

    @Test fun nombresDeCarpetaSinCaracteresRaros() {
        assertEquals("Finca-Sanchez-Lote-N-1", FlightLogStore.slug("Finca Sánchez / Lote Nº 1"))
        assertEquals("sin-nombre", FlightLogStore.slug("..\\..//"))
    }

    // ---- VideoImporter

    @Test fun cadaVideoVaASuSalidaPorLaHora() {
        val store = FlightLogStore(root)
        store.save(info(body(videoInicio = 1_000_000L, videoFin = 1_600_000L)), "a", "")
        store.save(info(body(salida = salida2, indice = 1, videoInicio = 3_000_000L, videoFin = 3_600_000L)), "b", "")
        val salidas = store.salidas()
        assertEquals("salida-1", VideoImporter.match(1_300_000L, salidas)?.dir?.name)     // un trozo a mitad de la grabación
        assertEquals("salida-1", VideoImporter.match(1_650_000L, salidas)?.dir?.name)     // dentro del margen
        assertEquals("salida-2", VideoImporter.match(3_600_000L, salidas)?.dir?.name)
        assertNull(VideoImporter.match(2_300_000L, salidas))                               // entre baterías: vuelo manual
    }

    @Test fun copiaLosVideosDeLaTarjetaUnaSolaVez() {
        val store = FlightLogStore(root)
        store.save(info(body(videoInicio = 1_000_000L, videoFin = 1_600_000L)), "a", "")
        val sd = Files.createTempDirectory("agroscan-sd").toFile()
        try {
            val dcim = File(sd, "DCIM/100MEDIA").apply { mkdirs() }
            File(dcim, "DJI_0001.MP4").apply { writeBytes(ByteArray(1000) { 7 }); setLastModified(1_500_000L) }
            File(dcim, "DJI_0002.MP4").apply { writeBytes(ByteArray(10)); setLastModified(9_000_000L) }   // vuelo manual
            File(dcim, "DJI_0001.LRF").apply { writeBytes(ByteArray(10)); setLastModified(1_500_000L) }   // vista previa: se ignora

            val r1 = VideoImporter(store).importar(sd)
            assertEquals(1, r1.copiados)
            assertEquals(listOf("DJI_0002.MP4"), r1.sinSalida)
            val copiado = File(store.salidas().single().videoDir, "DJI_0001.MP4")
            assertEquals(1000, copiado.length())

            val r2 = VideoImporter(store).importar(sd)   // importar otra vez la misma tarjeta no copia de nuevo
            assertEquals(0, r2.copiados)
            assertEquals(1, r2.yaEstaban)
        } finally {
            sd.deleteRecursively()
        }
    }

    // ---- FlightLogServer (HTTP de verdad)

    @Test fun recibePorHttpYSoloDevuelveLoQueFalta() {
        FlightLogServer.start({ InetAddress.getLoopbackAddress() }, { FlightLogStore(root) })
        val base = "http://127.0.0.1:${FlightLogServer.PORT}"
        fun request(method: String, path: String, json: String? = null): Pair<Int, String> {
            val c = URL(base + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            if (json != null) {
                c.doOutput = true
                c.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = c.responseCode
            return code to ((if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readAllBytes()) } ?: "")
        }
        // El servidor revisa cada 2 s si la Red Local está encendida: se espera a que abra
        val t0 = System.currentTimeMillis()
        while (runCatching { request("GET", "/salidas") }.isFailure && System.currentTimeMillis() - t0 < 6_000) Thread.sleep(200)
        if (FlightLogServer.error.value != null) return   // otra copia de la app tiene el puerto: no se puede probar aquí

        assertEquals(200 to """{"salidas":[]}""", request("GET", "/salidas"))
        assertEquals(200 to """{"ok":true,"nuevo":true}""", request("POST", "/salidas/$salida1", body()))
        assertEquals(200 to """{"ok":true,"nuevo":false}""", request("POST", "/salidas/$salida1", body()))   // reintento
        assertEquals(200 to """{"salidas":["$salida1"]}""", request("GET", "/salidas"))
        assertEquals(400, request("POST", "/salidas/$salida2", body()).first)                // dirección y cuerpo no coinciden
        assertEquals(400, request("POST", "/salidas/$salida2", "no es json").first)
        assertEquals(404, request("GET", "/otra-cosa").first)
        assertEquals(2, FlightLogStore.marcas(FlightLogStore(root).salidas().single().eventos))
    }
}
