package org.agroscan.project.features.telemetry

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryReceiverTest {

    @AfterTest fun limpiar() = TelemetryReceiver.reset()

    // Paquete tal como lo arma la app del dron (ver TelemetryPacket en el proyecto del dron)
    private fun tel(tMs: Long, fase: String = "FLYING", volando: Boolean = true, lat: Double = -0.2994723, lon: Double = -79.4881712) =
        """{"v":1,"tipo":"tel","seq":1,"t_ms":$tMs,"lat":$lat,"lon":$lon,"alt_m":25.0,"vel_ms":1.77,"rumbo":212.0,"bat":78,"sat":29,
            |"enlace":92,"fase":"$fase","volando":$volando,"wp":3,"wp_total":6,"avance":0.412,"dist_m":97.2,"grabando":true,"fotos":0,
            |"t_vuelo_s":131}""".trimMargin()

    // ---- MiniJson

    @Test fun leeObjetosListasYEscapes() {
        val j = MiniJson.parse("""{"a":1.5,"b":[1,-2e1,3],"c":"x\"y\/zé","d":true,"e":null,"f":{}}""") as Map<*, *>
        assertEquals(1.5, j["a"])
        assertEquals(listOf(1.0, -20.0, 3.0), j["b"])
        assertEquals("x\"y/zé", j["c"])
        assertEquals(true, j["d"])
        assertNull(j["e"])
        assertEquals(emptyMap<String, Any?>(), j["f"])
    }

    @Test fun rechazaLoQueNoEsJson() {
        listOf("", "{", """{"a":}""", """{"a":1,}""", "[1,2", """{"a":1} basura""", "nada", """{"a":"sin cerrar}""").forEach {
            assertFailsWith<IllegalArgumentException>(it) { MiniJson.parse(it) }
        }
    }

    @Test fun rechazaAnidadoExcesivo() {
        assertFailsWith<IllegalArgumentException> { MiniJson.parse("[".repeat(50) + "]".repeat(50)) }
    }

    // ---- Receptor

    @Test fun telemetriaValidaQuedaDisponible() {
        TelemetryReceiver.handle(tel(1000), now = 5000)
        val t = assertNotNull(TelemetryReceiver.live.value)
        assertEquals(78, t.bat)
        assertEquals(25.0, t.altM)
        assertEquals(212.0, t.rumbo)
        assertEquals(5000, t.recibidoMs)
        assertEquals(131, t.tVueloS)
    }

    @Test fun elDronSeDaPorConectadoSiElPaqueteNoLoDice() {
        TelemetryReceiver.handle("""{"tipo":"tel","t_ms":1}""", 1)
        assertEquals(true, TelemetryReceiver.live.value?.dron)
        TelemetryReceiver.handle("""{"tipo":"tel","t_ms":2,"dron":false,"bat":0}""", 2)
        assertEquals(false, TelemetryReceiver.live.value?.dron)
    }

    @Test fun paquetesBasuraSeIgnoran() {
        listOf("hola", "{", """{"tipo":"otra"}""", """{"tipo":"tel"}""", "[1,2]").forEach { TelemetryReceiver.handle(it, 1) }
        assertNull(TelemetryReceiver.live.value)
    }

    @Test fun unPaqueteViejoNoPisaAlMasNuevo() {
        TelemetryReceiver.handle(tel(2000), now = 1)
        TelemetryReceiver.handle(tel(1000, lat = 9.0), now = 2)   // llegó tarde
        TelemetryReceiver.handle(tel(2000, lat = 9.0), now = 3)   // repetido
        assertEquals(-0.2994723, TelemetryReceiver.live.value?.lat)
    }

    @Test fun faltaDeDatoQuedaNulo() {
        TelemetryReceiver.handle("""{"tipo":"tel","t_ms":1,"bat":50}""", 1)
        val t = assertNotNull(TelemetryReceiver.live.value)
        assertNull(t.lat)
        assertNull(t.rumbo)
        assertEquals(50, t.bat)
    }

    @Test fun laEstelaSoloAgregaPuntosQueSeMovieron() {
        TelemetryReceiver.handle(tel(1000, lon = -79.48817), 1)
        TelemetryReceiver.handle(tel(1500, lon = -79.48817), 2)   // quieto
        TelemetryReceiver.handle(tel(2000, lon = -79.48807), 3)   // ~11 m al este
        assertEquals(2, TelemetryReceiver.trail.value.size)
    }

    @Test fun laEstelaSeVaciaAlEmpezarOtraSalida() {
        TelemetryReceiver.handle(tel(1000, fase = "FLYING", lon = -79.4880), 1)
        TelemetryReceiver.handle(tel(2000, fase = "FLYING", lon = -79.4879), 2)
        assertTrue(TelemetryReceiver.trail.value.size >= 2)
        TelemetryReceiver.handle(tel(3000, fase = "FINISHED", volando = false, lon = -79.4878), 3)
        TelemetryReceiver.handle(tel(4000, fase = "PREPARING", volando = false, lon = -79.4878), 4)   // nueva salida
        assertEquals(1, TelemetryReceiver.trail.value.size)   // solo el punto de partida de la nueva salida
    }

    @Test fun elPlanTraeNombreYRuta() {
        TelemetryReceiver.handle("""{"tipo":"plan","t_ms":1,"mision":"Lote \"Norte\" 1","ruta":[-0.1,-79.1,-0.2,-79.2]}""", 100)
        val p = assertNotNull(TelemetryReceiver.plan.value)
        assertEquals("Lote \"Norte\" 1", p.mision)
        assertEquals(listOf(-0.1 to -79.1, -0.2 to -79.2), p.ruta)
    }

    @Test fun llegaPorUdpDeVerdadYSueltaElPuertoAlApagarLaRedLocal() {
        // La prueba hace de "Red Local": mientras devuelve una IP el receptor escucha ahí; con null, la red está apagada
        val loopback = InetAddress.getLoopbackAddress()
        var redLocal: InetAddress? = loopback
        TelemetryReceiver.start { redLocal }
        Thread.sleep(300)   // da tiempo a abrir el puerto
        if (TelemetryReceiver.error.value != null) return   // otra copia de la app tiene el puerto: no se puede probar aquí
        DatagramSocket().use { s ->
            val bytes = tel(5000).toByteArray()
            s.send(DatagramPacket(bytes, bytes.size, loopback, TelemetryReceiver.PORT))
        }
        val t0 = System.currentTimeMillis()
        while (TelemetryReceiver.live.value == null && System.currentTimeMillis() - t0 < 3_000) Thread.sleep(50)
        assertEquals(78, TelemetryReceiver.live.value?.bat)

        // Se apaga la Red Local: en unos segundos el puerto queda libre (otro programa puede abrirlo)
        redLocal = null
        val t1 = System.currentTimeMillis()
        var libre = false
        while (!libre && System.currentTimeMillis() - t1 < 6_000) {
            libre = runCatching { DatagramSocket(InetSocketAddress(loopback, TelemetryReceiver.PORT)).close() }.isSuccess
            if (!libre) Thread.sleep(200)
        }
        assertTrue(libre, "el receptor no soltó el puerto al apagarse la Red Local")
    }

    @Test fun laReglaDelFirewallEsEstrechaYEscapaLaRuta() {
        val script = TelemetryFirewall.ruleScript("""C:\Users\O'Brien\AgroScan\AgroScan.exe""")
        assertTrue("-Protocol UDP" in script)
        assertTrue("-LocalPort ${TelemetryReceiver.PORT}" in script)
        assertTrue("-LocalAddress 192.168.137.1" in script)
        assertTrue("-RemoteAddress 192.168.137.0/24" in script)
        assertTrue("""-Program 'C:\Users\O''Brien\AgroScan\AgroScan.exe'""" in script)   // comilla escapada: no rompe el script
        assertTrue("Remove-NetFirewallRule" in script)   // las reglas viejas de AgroScan se borran antes
    }

    @Test fun elNombreDeLaReglaDependeDelProgramaSinImportarMayusculas() {
        val a = TelemetryFirewall.ruleName("""C:\Users\ASUS\AgroScan\AgroScan.exe""")
        assertTrue(a.matches(Regex("AgroScan telemetria [0-9a-f]{8}")), a)
        assertEquals(a, TelemetryFirewall.ruleName("""c:\users\asus\agroscan\agroscan.exe"""))   // Windows guarda la ruta en minúsculas
        assertTrue(a != TelemetryFirewall.ruleName("""C:\Program Files\AgroScan\AgroScan.exe"""))  // otro lugar → otra regla
    }

    @Test fun elPlanTraeSalidaAlturaModoYOrigen() {
        TelemetryReceiver.handle(
            """{"tipo":"plan","t_ms":1,"mision":"M","salida":2,"salidas":3,"altura_m":25,"modo":"video","home":[-0.3,-79.3],"ruta":[-0.1,-79.1]}""", 1
        )
        val p = assertNotNull(TelemetryReceiver.plan.value)
        assertEquals(2, p.salida)
        assertEquals(3, p.salidas)
        assertEquals(25.0, p.alturaM)
        assertEquals("video", p.modo)
        assertEquals(-0.3 to -79.3, p.home)
    }

    @Test fun unOrigenMalFormadoNoTiraElPlan() {
        TelemetryReceiver.handle("""{"tipo":"plan","t_ms":1,"mision":"M","home":["x"],"ruta":[-0.1,-79.1]}""", 1)
        val p = assertNotNull(TelemetryReceiver.plan.value)
        assertNull(p.home)
        assertEquals(1, p.ruta.size)
    }

    @Test fun elLoteTraeElPerimetro() {
        TelemetryReceiver.handle("""{"tipo":"lote","t_ms":1,"perimetro":[-0.1,-79.1,-0.2,-79.2,-0.3,-79.0]}""", 7)
        val l = assertNotNull(TelemetryReceiver.lote.value)
        assertEquals(3, l.perimetro.size)
        assertEquals(-0.3 to -79.0, l.perimetro.last())
        assertEquals(7, l.recibidoMs)
    }

    @Test fun unLoteSinPerimetroSeIgnora() {
        TelemetryReceiver.handle("""{"tipo":"lote","t_ms":1}""", 1)
        assertNull(TelemetryReceiver.lote.value)
    }

    @Test fun soloSeConfiaEnLaZonaMovilYEnEstaPc() {
        fun trusted(ip: String) = TelemetryReceiver.isTrustedSource(InetAddress.getByName(ip))
        assertEquals(true, trusted("192.168.137.23"))   // el teléfono en la zona móvil
        assertEquals(true, trusted("127.0.0.1"))        // solo llega en las pruebas (la app escucha en 192.168.137.1)
        assertEquals(false, trusted("192.168.1.50"))    // otra red local
        assertEquals(false, trusted("10.0.0.7"))
        assertEquals(false, trusted("192.168.13.7"))    // parecida pero no es
        assertEquals(false, trusted("8.8.8.8"))
        assertEquals(false, trusted("fe80::1"))         // IPv6 que no sea loopback
    }

    @Test fun elPlanViejoDejaDeContar() {
        TelemetryReceiver.handle("""{"tipo":"plan","t_ms":1,"mision":"M","ruta":[]}""", 1_000)
        assertNotNull(TelemetryReceiver.currentPlan(now = 10_000))
        assertNull(TelemetryReceiver.currentPlan(now = 20_000))
    }

    @Test fun rutaConUnValorQueNoEsNumeroSeDescarta() {
        TelemetryReceiver.handle("""{"tipo":"plan","t_ms":1,"mision":"M","ruta":[-0.1,"x"]}""", 1)
        assertNull(TelemetryReceiver.plan.value)
    }
}
