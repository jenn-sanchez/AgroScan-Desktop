package org.agroscan.project.features.ortomosaico

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparacionTest {

    private val GB = 1_000_000_000L
    private val lista = SalidaArmado(indice = 0, registro = true, videos = 1, imagenes = 73)
    private val otraLista = SalidaArmado(indice = 1, registro = true, videos = 1, imagenes = 73)

    private fun evaluar(
        salidas: List<SalidaArmado> = listOf(lista),
        incluidas: Set<Int> = salidas.map { it.indice }.toSet(),
        espacio: Long = 50 * GB, ocupado: Boolean = false, sinModulo: String? = null, existente: Entrada? = null
    ) = Preparacion.evaluar(salidas, incluidas, espacio, ocupado, sinModulo, existente)

    private fun Evaluacion.nivelDe(titulo: String) = requisitos.firstOrNull { it.titulo.startsWith(titulo) }?.nivel

    @Test fun conTodoListoSePuedeArmarYSeVeQueEstaBien() {
        val e = evaluar()
        assertTrue(e.puedeArmar)
        assertNull(e.motivoBloqueo)
        assertEquals(Nivel.OK, e.nivelDe("Registro e imágenes listos"))
        assertEquals(Nivel.OK, e.nivelDe("Espacio en el disco suficiente"))
        assertTrue(e.requisitos.none { it.nivel != Nivel.OK })
    }

    @Test fun sinNingunaSalidaElegidaNoSePuedeArmar() {
        val e = evaluar(incluidas = emptySet())
        assertFalse(e.puedeArmar)
        assertEquals("Elige al menos una salida", e.motivoBloqueo?.titulo)
    }

    @Test fun faltaElRegistroYSeExplicaComoConseguirlo() {
        val e = evaluar(salidas = listOf(lista.copy(registro = false)))
        assertFalse(e.puedeArmar)
        val motivo = e.motivoBloqueo!!
        assertEquals("Salida 1: falta el registro de vuelo", motivo.titulo)
        assertTrue(motivo.detalle!!.contains("Enviar vuelos pendientes"))
    }

    @Test fun faltaPreprocesarLlevaAProcesamiento() {
        val sinVideo = evaluar(salidas = listOf(lista.copy(imagenes = 0, videos = 0))).motivoBloqueo!!
        assertTrue(sinVideo.detalle!!.contains("importa su video"))
        val conVideo = evaluar(salidas = listOf(lista.copy(imagenes = 0))).motivoBloqueo!!
        assertTrue(conVideo.detalle!!.contains("Preprocesa"))
        assertEquals(Accion.IR_A_PROCESAMIENTO, conVideo.accion)
    }

    @Test fun unaSalidaSinIncluirSoloAvisa() {
        val e = evaluar(salidas = listOf(lista, otraLista), incluidas = setOf(0))
        assertTrue(e.puedeArmar)
        assertEquals(Nivel.AVISO, e.nivelDe("Hay salidas sin incluir"))
        assertTrue(e.requisitos.first { it.nivel == Nivel.AVISO }.detalle!!.contains("salidas 2"))
    }

    @Test fun unaSalidaSinPreprocesarNoAvisaDeSalidasSinIncluirPorqueNoPodriaIncluirse() {
        val e = evaluar(salidas = listOf(lista, otraLista.copy(imagenes = 0)), incluidas = setOf(0))
        assertTrue(e.puedeArmar)
        assertNull(e.nivelDe("Hay salidas sin incluir"))
    }

    @Test fun elEspacioBloqueaAvisaOTranquiliza() {
        assertEquals(Nivel.BLOQUEO, evaluar(espacio = 1 * GB).nivelDe("Poco espacio"))
        assertEquals(Nivel.AVISO, evaluar(espacio = 6 * GB).nivelDe("Espacio justo"))
        assertEquals(Nivel.OK, evaluar(espacio = 40 * GB).nivelDe("Espacio en el disco suficiente"))
        assertFalse(evaluar(espacio = 1 * GB).puedeArmar)
        assertTrue(evaluar(espacio = 6 * GB).puedeArmar)
    }

    @Test fun conOtroProcesoEnMarchaNoSePuede() {
        val e = evaluar(ocupado = true)
        assertFalse(e.puedeArmar)
        assertEquals("Hay otro proceso en marcha", e.motivoBloqueo?.titulo)
    }

    @Test fun siElVueloYaTieneOrtomosaicoAvisaQueSeReemplazaSoloAlTerminarBien() {
        val dir = File("x")
        val e = evaluar(existente = Entrada(dir, ficha()))
        assertTrue(e.puedeArmar)
        val aviso = e.requisitos.first { it.nivel == Nivel.AVISO }
        assertTrue(aviso.titulo.contains("ya tiene un ortomosaico"))
        assertTrue(aviso.detalle!!.contains("solo cuando el nuevo termine bien"))
        // pero si se está armando ahora mismo, no
        val armando = evaluar(existente = Entrada(dir, ficha(estado = EstadoOrtomosaico.PROCESANDO)))
        assertFalse(armando.puedeArmar)
        assertEquals("Este vuelo ya se está armando", armando.motivoBloqueo?.titulo)
    }

    @Test fun sinElModuloDeArmadoNoSeOfreceArmarYSeDiceElMotivo() {
        val e = evaluar(sinModulo = "Falta el entorno de Python del módulo de ortomosaicos: ejecuta ./gradlew prepararOrtomosaico (ver README)")
        assertFalse(e.puedeArmar)
        assertEquals("El armado no está disponible en esta PC", e.motivoBloqueo?.titulo)
        assertTrue("prepararOrtomosaico" in e.motivoBloqueo?.detalle.orEmpty())
        // y lo demás se sigue revisando: el usuario ve que su vuelo está listo
        assertEquals(Nivel.OK, e.nivelDe("Registro e imágenes listos"))
    }

    @Test fun elPrimerBloqueoEsElMasUtilParaDecirJuntoAlBoton() {
        val e = evaluar(salidas = listOf(lista.copy(registro = false)), espacio = 1 * GB, ocupado = true, sinModulo = "falta el módulo")
        assertEquals("Salida 1: falta el registro de vuelo", e.motivoBloqueo?.titulo)
        assertTrue(e.requisitos.count { it.nivel == Nivel.BLOQUEO } >= 4)
    }
}
