package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.MiniJson
import org.agroscan.project.core.ModuloPython
import org.agroscan.project.features.flightlog.FlightLogStore
import org.agroscan.project.features.flightlog.SalidaInfo
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArmadoTest {

    private val raiz: File = Files.createTempDirectory("armado-prueba").toFile()
    private val store = OrtomosaicoStore(File(raiz, "ortomosaicos"))
    private val log = File(raiz, "modulo.log")

    @AfterTest fun limpiar() {
        raiz.deleteRecursively()
    }

    // ---------- el contrato con el módulo ----------

    @Test fun losEventosDelModuloSeLeen() {
        assertEquals(OrtomosaicoModule.Evento.Progreso(42, "Ubicando"), OrtomosaicoModule.evento("""{"tipo": "progreso", "porcentaje": 42, "etapa": "Ubicando"}"""))
        assertEquals(OrtomosaicoModule.Evento.Progreso(100, ""), OrtomosaicoModule.evento("""{"tipo":"progreso","porcentaje":250}"""))
        assertEquals(OrtomosaicoModule.Evento.Error("Faltan imágenes."), OrtomosaicoModule.evento("""{"tipo":"error","mensaje":"Faltan imágenes."}"""))
        val fin = OrtomosaicoModule.evento(RESUMEN) as OrtomosaicoModule.Evento.Fin
        assertEquals(141, fin.resumen.imagenesUsadas)
        assertEquals(96.0, fin.resumen.coberturaPct)
        assertEquals("EPSG:32717", fin.resumen.crs)
        assertEquals(Limites(-79.488, -0.3, -79.487, -0.299), fin.resumen.limites)
        assertEquals(listOf("Aviso uno"), fin.resumen.advertencias)
        assertTrue(fin.resumen.ajustado)
        assertEquals("0a1b2c3d-4444-4444-8444-444444444444", fin.resumen.alineadoCon)
        assertEquals(0.4, fin.resumen.desacuerdoM)
        assertEquals(12, fin.resumen.teselas)
    }

    @Test fun unResumenSinLosCamposDeLaParte3SeLeeIgual() {
        // lo que escribía la versión anterior del módulo, o un lote sin contorno (sin teselas) y sin referencia
        val fin = OrtomosaicoModule.evento("""{"tipo":"fin","resumen":{"imagenes_usadas":5,"imagenes_total":6,"alineado_con":null,"teselas":null,"ajuste":{"aplicado":false}}}""") as OrtomosaicoModule.Evento.Fin
        assertFalse(fin.resumen.ajustado)
        assertNull(fin.resumen.alineadoCon)
        assertNull(fin.resumen.desacuerdoM)
        assertNull(fin.resumen.teselas)
        assertNull((OrtomosaicoModule.evento("""{"tipo":"fin","resumen":{}}""") as OrtomosaicoModule.Evento.Fin).resumen.teselas)
    }

    @Test fun loQueNoEsDelContratoSeIgnora() {
        assertNull(OrtomosaicoModule.evento("Traceback (most recent call last):"))
        assertNull(OrtomosaicoModule.evento("""{"tipo":"otra-cosa"}"""))
        assertNull(OrtomosaicoModule.evento("""{"tipo":"fin"}"""))
        // límites al revés: la ficha se escribe igual, pero sin límites
        val raro = OrtomosaicoModule.evento(RESUMEN.replace("\"oeste\":-79.488", "\"oeste\":-70.0")) as OrtomosaicoModule.Evento.Fin
        assertNull(raro.resumen.limites)
    }

    @Test fun elComandoLlevaLaOrdenYLaCarpetaDeSalida() {
        val pb = OrtomosaicoModule.proceso(ModuloPython(listOf("python.exe", "main.py"), File("C:/m")), File("C:/t/entrada.json"), File("C:/t"))
        assertEquals(listOf("python.exe", "main.py", "--entrada", File("C:/t/entrada.json").path, "--salida", File("C:/t").path, "--json"), pb.command())
        assertEquals(File("C:/m"), pb.directory())
    }

    // ---------- la orden de armado ----------

    private fun salida(indice: Int): FlightLogStore.Stored {
        val dir = File(raiz, "vuelos/2026-10-26_Finca_Sector-1_9f8e7d6c/salida-${indice + 1}").apply { mkdirs() }
        val info = SalidaInfo(
            uuid = "00000000-0000-4000-8000-00000000000$indice", indice = indice, estado = "COMPLETADA", inicioMs = OCT_26, finMs = OCT_26 + 1000,
            videoInicioMs = OCT_26 + 7000, videoFinMs = null, vueloUuid = VUELO_1, vueloInicioMs = OCT_26, modo = "video", mision = "Sector 1", lote = "Finca Sánchez"
        )
        return FlightLogStore.Stored(dir, info)
    }

    private fun imagenes(s: FlightLogStore.Stored, video: String, vararg carpetas: String) = carpetas.forEach {
        File(s.dir, "preproceso/$video/$it").apply { mkdirs() }.resolve("a.jpg").writeText("x")
    }

    private val contexto = OrtomosaicoDb.ContextoVuelo(LOTE_A, "Finca Sánchez", "Sector 1", 4.0, listOf(-0.299 to -79.488, -0.299 to -79.487, -0.300 to -79.487))

    @Test fun laOrdenListaSoloLasSalidasElegidasConSusImagenesYAguantaVideosBorrados() {
        val s1 = salida(0)
        val s2 = salida(1)
        File(s1.videoDir, "DJI_0001.MP4").apply { parentFile.mkdirs() }.writeText("v")
        imagenes(s1, "DJI_0001", "frames_originales", "imagenes_procesadas")
        imagenes(s1, "DJI_0002", "frames_originales", "imagenes_procesadas")       // su video ya se borró
        File(s1.dir, "preproceso/DJI_0003/imagenes_procesadas").mkdirs()          // sin imágenes: no cuenta
        imagenes(s2, "DJI_0009", "imagenes_procesadas")

        val j = MiniJson.parse(EntradaArmado.json(Solicitud(VUELO_1, setOf(0), Calidad.ALTA, OrigenImagenes.CORREGIDAS), listOf(s2, s1), contexto)) as Map<*, *>
        assertEquals(1.0, j["v"])
        assertEquals("alta", j["calidad"])
        assertEquals("corregidas", j["origen"])
        assertEquals(4.0, j["altura_copa_m"])
        assertEquals(3, (j["lote"] as List<*>).size)
        val salidas = j["salidas"] as List<*>
        assertEquals(1, salidas.size)                                               // solo la salida elegida
        val a = salidas.single() as Map<*, *>
        assertEquals(s1.registro.path, a["registro"])
        assertEquals((OCT_26 + 7000).toDouble(), a["video_inicio_ms"])
        val videos = (a["videos"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("DJI_0001", "DJI_0002"), videos.map { it["nombre"] })
        assertEquals(File(s1.videoDir, "DJI_0001.MP4").path, videos[0]["archivo"])
        assertNull(videos[1]["archivo"])
        assertTrue((videos[0]["imagenes"] as String).endsWith("imagenes_procesadas"))
    }

    @Test fun conImagenesOriginalesSePideLaOtraCarpeta() {
        val s = salida(0)
        imagenes(s, "DJI_0001", "frames_originales")        // sin corregidas
        val pedir = { origen: OrigenImagenes -> EntradaArmado.videos(s, origen).map { it.imagenes.name } }
        assertEquals(listOf("frames_originales"), pedir(OrigenImagenes.ORIGINALES))
        assertEquals(emptyList(), pedir(OrigenImagenes.CORREGIDAS))
    }

    // ---------- con qué fecha se alinea ----------

    private fun conGeoref(f: Ficha, georef: Boolean = true, vista: Boolean = true): Entrada {
        val dir = store.carpetaDe(f)
        store.guardarFicha(dir, f)
        if (georef) File(dir, Archivos.GEOREF).writeText("{}")
        if (vista) File(dir, Archivos.VISTA).writeText("x")
        return assertNotNull(store.leer(dir))
    }

    @Test fun seAlineaConLaPrimeraFechaDelLoteYNuncaConUnaPosterior() {
        val primera = conGeoref(ficha(vuelo = VUELO_1, inicio = OCT_26))
        val segunda = conGeoref(ficha(vuelo = VUELO_2, inicio = OCT_26 + 30 * DIA))
        val todas = listOf(segunda, primera)
        // la tercera se alinea con la primera (el ancla), no con la segunda que es más reciente
        assertEquals(VUELO_1, EntradaArmado.referencia(todas, VUELO_3, OCT_26 + 60 * DIA)?.ficha?.vueloUuid)
        assertEquals(VUELO_1, EntradaArmado.referencia(todas, VUELO_2, OCT_26 + 30 * DIA)?.ficha?.vueloUuid)
        // la primera es el ancla: no se alinea con nada, ni siquiera al volver a armarla
        assertNull(EntradaArmado.referencia(todas, VUELO_1, OCT_26))
        // una fecha anterior a todas tampoco tiene con qué
        assertNull(EntradaArmado.referencia(todas, VUELO_3, OCT_26 - DIA))
    }

    @Test fun laReferenciaDebeEstarListaYTenerSuGeorreferenciaYSuVista() {
        val sinGeoref = conGeoref(ficha(vuelo = VUELO_1, inicio = OCT_26), georef = false)       // armada antes de existir georef.json
        assertNull(EntradaArmado.referencia(listOf(sinGeoref), VUELO_2, OCT_26 + DIA))
        store.eliminar(sinGeoref)
        val sinVista = conGeoref(ficha(vuelo = VUELO_1, inicio = OCT_26), vista = false)
        assertNull(EntradaArmado.referencia(listOf(sinVista), VUELO_2, OCT_26 + DIA))
        store.eliminar(sinVista)
        val conError = conGeoref(ficha(vuelo = VUELO_1, inicio = OCT_26, estado = EstadoOrtomosaico.ERROR))
        assertNull(EntradaArmado.referencia(listOf(conError), VUELO_2, OCT_26 + DIA))
    }

    @Test fun laOrdenLlevaLaReferenciaSoloSiLaHay() {
        val ref = conGeoref(ficha(vuelo = VUELO_1, inicio = OCT_26))
        val s = salida(0)
        val pedir = { r: Entrada? -> MiniJson.parse(EntradaArmado.json(Solicitud(VUELO_2, setOf(0), Calidad.ESTANDAR, OrigenImagenes.ORIGINALES), listOf(s), contexto, r)) as Map<*, *> }
        val con = pedir(ref)["referencia"] as Map<*, *>
        assertEquals(ref.dir.path, con["carpeta"])
        assertEquals(VUELO_1, con["vuelo"])
        assertNull(pedir(null)["referencia"])
    }

    @Test fun sinAlturaDeCopaNiLoteLaOrdenSigueSiendoValida() {
        val j = MiniJson.parse(EntradaArmado.json(Solicitud(VUELO_1, setOf(0), Calidad.RAPIDA, OrigenImagenes.ORIGINALES), listOf(salida(0)), contexto.copy(alturaCopaM = null, contorno = emptyList()))) as Map<*, *>
        assertNull(j["altura_copa_m"])
        assertEquals(emptyList<Any>(), j["lote"])
    }

    // ---------- el armado con un módulo de mentira ----------

    private fun modulo(cuerpo: String): (File, File) -> ProcessBuilder {
        val bat = File(raiz, "falso.bat").apply { writeText("@echo off\r\n$cuerpo\r\n") }
        return { entrada, salida -> ProcessBuilder("cmd", "/c", bat.path, "--entrada", entrada.path, "--salida", salida.path, "--json") }
    }

    private val crearArchivos = """
        echo x> "%~4\ortomosaico.tif"
        echo x> "%~4\vista.jpg"
        echo x> "%~4\miniatura.jpg"
        echo x> "%~4\georef.json"
    """.trimIndent()

    private class Banco {
        val fichas = mutableListOf<Ficha>()
        val progresos = mutableListOf<Int>()
        var cancelar = false
    }

    private fun armador(banco: Banco, lanzar: (File, File) -> ProcessBuilder, base: Result<Unit> = Result.success(Unit)) = Armador(
        store, lanzar, { f, _ -> banco.fichas += f; base }, { p, _ -> banco.progresos += p }, { banco.cancelar }, {}, log
    )

    private val base = ficha(estado = EstadoOrtomosaico.PROCESANDO)

    @Test fun unArmadoQueTerminaBienPublicaElOrtomosaicoYLoAnotaEnLaBase() {
        val b = Banco()
        val cierre = armador(b, modulo("echo {\"tipo\":\"progreso\",\"porcentaje\":30,\"etapa\":\"Ubicando\"}\r\n$crearArchivos\r\necho $FIN")).ejecutar(base, "{}", null)

        assertEquals(Cierre.Resultado.LISTO, cierre.resultado)
        assertEquals(LOTE_A, cierre.loteUuid)
        assertEquals(listOf("Aviso uno"), cierre.advertencias)
        assertTrue("1,20 ha" in cierre.texto && "96 %" in cierre.texto, cierre.texto)
        assertEquals(listOf(30), b.progresos)
        val publicado = assertNotNull(store.porVuelo(VUELO_1))
        assertEquals(EstadoOrtomosaico.LISTO, publicado.ficha.estado)
        assertEquals(141, publicado.ficha.imagenesUsadas)
        assertEquals("fake 1.0", publicado.ficha.motor)
        assertTrue(publicado.tieneVista && publicado.listo)
        assertTrue(File(publicado.dir, Archivos.ENTRADA).isFile)                    // queda la orden con la que se armó
        assertEquals(listOf(EstadoOrtomosaico.LISTO), b.fichas.map { it.estado })
        assertFalse(store.carpetaTemporal(base).exists())                           // no queda carpeta de trabajo
    }

    @Test fun alTerminarSeCuentaConQueFechaSeAlineoYCuantasTeselasSalieron() {
        val ref = conGeoref(ficha(vuelo = VUELO_2, inicio = OCT_26 - 30 * DIA))
        val b = Banco()
        val cierre = armador(b, modulo("$crearArchivos\r\necho $FIN")).ejecutar(base, "{}", null, ref)
        assertTrue("12 teselas para la IA" in cierre.texto, cierre.texto)
        assertTrue("alineado con el ortomosaico del ${Formato.fecha(ref.ficha.vueloInicioMs)} (desacuerdo medido 0.4 m)" in cierre.texto, cierre.texto)
        // sin referencia (o si el módulo no alineó) no se dice
        val sin = armador(Banco(), modulo("$crearArchivos\r\necho $FIN")).ejecutar(base.copy(vueloUuid = VUELO_3), "{}", null, null)
        assertFalse("alineado" in sin.texto)
    }

    @Test fun elUsuarioVeLaFichaArmandoMientrasCorreYSeReemplazaAlTerminar() {
        val b = Banco()
        // el módulo mira, mientras trabaja, qué hay en la carpeta final
        val destino = store.carpetaDe(base)
        val visto = File(raiz, "visto.txt")
        val cuerpo = "if exist \"${File(destino, Archivos.FICHA).path}\" (echo si> \"${visto.path}\")\r\n$crearArchivos\r\necho $FIN"
        armador(b, modulo(cuerpo)).ejecutar(base, "{}", null)
        assertTrue(visto.isFile, "la ficha «armando» debía existir mientras se armaba")
        assertEquals(EstadoOrtomosaico.LISTO, store.porVuelo(VUELO_1)?.ficha?.estado)
    }

    @Test fun siElModuloFallaYElVueloNoTeniaNadaQuedaLaFichaConElMotivo() {
        val b = Banco()
        val cierre = armador(b, modulo("echo {\"tipo\":\"error\",\"mensaje\":\"Faltan imágenes preprocesadas.\"}")).ejecutar(base, "{}", null)
        assertEquals(Cierre.Resultado.ERROR, cierre.resultado)
        assertEquals("Faltan imágenes preprocesadas.", cierre.texto)
        val f = assertNotNull(store.porVuelo(VUELO_1)).ficha
        assertEquals(EstadoOrtomosaico.ERROR, f.estado)
        assertEquals("Faltan imágenes preprocesadas.", f.error)
        assertEquals(listOf(EstadoOrtomosaico.ERROR), b.fichas.map { it.estado })
        assertFalse(store.carpetaTemporal(base).exists())
    }

    @Test fun siElVueloYaTeniaOrtomosaicoUnFalloNoLoEstropea() {
        val previo = ficha().copy(coberturaPct = 88.0)
        val dir = store.carpetaDe(previo)
        store.guardarFicha(dir, previo)
        File(dir, Archivos.VISTA).writeText("vieja")
        val b = Banco()
        val cierre = armador(b, modulo("echo {\"tipo\":\"error\",\"mensaje\":\"Se cayó.\"}")).ejecutar(base, "{}", store.porVuelo(VUELO_1))

        assertEquals(Cierre.Resultado.ERROR, cierre.resultado)
        assertTrue("Se conserva el ortomosaico anterior" in cierre.texto)
        val f = assertNotNull(store.porVuelo(VUELO_1)).ficha
        assertEquals(EstadoOrtomosaico.LISTO, f.estado)
        assertEquals(88.0, f.coberturaPct)
        assertEquals("vieja", File(dir, Archivos.VISTA).readText())
        assertTrue(b.fichas.isEmpty())                                              // y la base no se toca
    }

    @Test fun siSeReemplazaUnoViejoSoloCuandoElNuevoEstaListo() {
        val previo = ficha().copy(coberturaPct = 88.0)
        val dir = store.carpetaDe(previo)
        store.guardarFicha(dir, previo)
        File(dir, "restos.txt").writeText("del viejo")
        armador(Banco(), modulo("$crearArchivos\r\necho $FIN")).ejecutar(base, "{}", store.porVuelo(VUELO_1))
        val nuevo = assertNotNull(store.porVuelo(VUELO_1))
        assertEquals(96.0, nuevo.ficha.coberturaPct)
        assertFalse(File(dir, "restos.txt").exists())
        assertEquals(1, store.listar().size)
    }

    @Test fun cancelarDejaLaFichaCanceladaYBorraLoAvanzado() {
        val b = Banco().apply { cancelar = true }
        val cierre = armador(b, modulo("$crearArchivos\r\necho $FIN")).ejecutar(base, "{}", null)
        assertEquals(Cierre.Resultado.CANCELADO, cierre.resultado)
        assertEquals(EstadoOrtomosaico.CANCELADO, assertNotNull(store.porVuelo(VUELO_1)).ficha.estado)
        assertFalse(store.carpetaTemporal(base).exists())
    }

    @Test fun unModuloQueDiceTerminarSinDejarSusArchivosSeTrataComoError() {
        val cierre = armador(Banco(), modulo("echo $FIN")).ejecutar(base, "{}", null)
        assertEquals(Cierre.Resultado.ERROR, cierre.resultado)
        assertTrue("faltan sus archivos" in cierre.texto && "ortomosaico.tif" in cierre.texto, cierre.texto)
    }

    @Test fun unModuloQueMuereSinDecirNadaSeExplicaYApuntaAlRegistro() {
        val cierre = armador(Banco(), modulo("exit /b 3")).ejecutar(base, "{}", null)
        assertEquals(Cierre.Resultado.ERROR, cierre.resultado)
        assertTrue("código 3" in cierre.texto && "modulo.log" in cierre.texto, cierre.texto)
    }

    @Test fun unModuloQueNoSePuedeEjecutarSeDiceSinVolcarLaPila() {
        val cierre = armador(Banco(), { _, _ -> ProcessBuilder("programa-que-no-existe-agroscan.exe") }).ejecutar(base, "{}", null)
        assertEquals(Cierre.Resultado.ERROR, cierre.resultado)
        assertTrue(cierre.texto.startsWith("No se pudo ejecutar el módulo de ortomosaicos"))
    }

    @Test fun siLaBaseFallaElOrtomosaicoSeGuardaIgualYSeAvisa() {
        val cierre = armador(Banco(), modulo("$crearArchivos\r\necho $FIN"), base = Result.failure(RuntimeException("Connection to localhost:5433 refused"))).ejecutar(base, "{}", null)
        assertEquals(Cierre.Resultado.LISTO, cierre.resultado)
        assertEquals(EstadoOrtomosaico.LISTO, store.porVuelo(VUELO_1)?.ficha?.estado)
        assertTrue(cierre.advertencias.any { "no está encendida" in it })
    }

    @Test fun elArmadoNoMarcaComoInterrumpidoLoQueSigueCorriendo() {
        store.guardarFicha(store.carpetaDe(base), base)
        assertEquals(0, store.mantenimiento(setOf(VUELO_1)))
        assertEquals(1, store.mantenimiento(emptySet()))
    }

    // De punta a punta: el orquestador con el módulo de Python de verdad sobre un vuelo inventado (ortomosaico/tests/vuelo_sintetico.py).
    // Si esta PC no tiene preparado el entorno de Python del módulo (./gradlew prepararOrtomosaico) no se prueba.
    @Test fun conElModuloRealSeArmaUnVueloSinteticoYSePublicaConSuFicha() {
        val modulo = OrtomosaicoModule.ubicar().getOrNull() ?: return println("ArmadoTest: sin el módulo de ortomosaicos en esta PC, no se prueba")
        val vuelo = File(raiz, "sintetico")
        val gen = ProcessBuilder(
            modulo.programa.first(), "-I", "-c",
            "import sys, pathlib; sys.path.insert(0, '.'); from tests import vuelo_sintetico as vs; vs.crear(pathlib.Path(sys.argv[1]))", vuelo.path
        ).directory(modulo.carpeta).redirectErrorStream(true).start()
        val salidaGen = gen.inputStream.bufferedReader().readText()
        assertEquals(0, gen.waitFor(), salidaGen)

        val b = Banco()
        val cierre = Armador(
            store, { entrada, salida -> OrtomosaicoModule.proceso(modulo, entrada, salida) }, { f, _ -> b.fichas += f; Result.success(Unit) },
            { p, _ -> b.progresos += p }, { false }, {}, log
        ).ejecutar(base, File(vuelo, "entrada.json").readText(), null)

        assertEquals(Cierre.Resultado.LISTO, cierre.resultado, cierre.texto)
        val o = assertNotNull(store.porVuelo(VUELO_1))
        assertTrue(o.listo && o.tieneVista && o.geotiff.isFile && o.miniatura.isFile)
        // lo que consume la IA y la comparación entre fechas
        assertTrue(File(o.dir, Archivos.GEOREF).isFile && File(o.dir, Archivos.COBERTURA).isFile && File(o.dir, Archivos.TESELAS_INDICE).isFile)
        assertTrue(File(o.dir, Archivos.TESELAS).listFiles { f -> f.extension == "jpg" }.orEmpty().isNotEmpty())
        assertEquals(File(o.dir, Archivos.TESELAS).listFiles { f -> f.extension == "jpg" }.orEmpty().size, o.dir.resolve(Archivos.TESELAS_INDICE).readLines().size - 1)
        assertEquals("EPSG:32717", o.ficha.crs)
        assertTrue((o.ficha.coberturaPct ?: 0.0) > 95.0, "cobertura ${o.ficha.coberturaPct}")
        assertEquals(o.ficha.imagenesTotal, o.ficha.imagenesUsadas)
        val l = assertNotNull(o.ficha.limites)
        assertTrue(l.oeste <= -79.488 && l.norte >= -0.30)
        assertTrue(b.progresos.isNotEmpty() && b.progresos == b.progresos.sorted())
        assertTrue(o.ficha.motor.startsWith("directo-gps"))
    }

    private companion object {
        const val RESUMEN = """{"tipo":"fin","resumen":{"imagenes_usadas":141,"imagenes_total":146,"cobertura_pct":96.0,"area_ha":1.2,"gsd_cm_px":4.0,"crs":"EPSG:32717","limites":{"oeste":-79.488,"sur":-0.3,"este":-79.487,"norte":-0.299},"error_estimado_m":3.0,"motor":"fake 1.0","advertencias":["Aviso uno"],"ajuste":{"aplicado":true,"pares":40},"alineado_con":{"vuelo":"0a1b2c3d-4444-4444-8444-444444444444","este_m":-4.5,"norte_m":1.0,"desacuerdo_m":0.4},"teselas":{"escritas":12,"sin_imagen":1,"fuera_del_lote":3}}}"""
        const val FIN = RESUMEN
    }
}
