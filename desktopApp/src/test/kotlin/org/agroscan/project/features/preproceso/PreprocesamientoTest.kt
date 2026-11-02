package org.agroscan.project.features.preproceso

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreprocesamientoTest {

    // Líneas tal como las escribe el módulo de preprocesamiento con --json (ver su README)
    @Test fun leeLasLineasDelModulo() {
        assertEquals(
            Preprocesamiento.Evento.Progreso(35, "Extrayendo imágenes"),
            Preprocesamiento.evento("""{"tipo": "progreso", "porcentaje": 35, "etapa": "Extrayendo im\u00e1genes"}""")
        )
        assertEquals(
            Preprocesamiento.Evento.Error("No existe el video: x.mp4"),
            Preprocesamiento.evento("""{"tipo": "error", "mensaje": "No existe el video: x.mp4"}""")
        )
        val fin = Preprocesamiento.evento(
            """{"tipo": "fin", "porcentaje": 100, "resumen": {"video": "DJI_0103.MP4", "imagenes_originales": 73, "imagenes_finales": 73,
               "modificadas": 2, "con_pendientes": 1, "con_advertencias": 7, "plan_b": false, "sincronizacion": null}}""".replace("\n", "")
        )
        assertEquals(Preprocesamiento.Evento.Fin(Preprocesamiento.Resumen(73, 2, 7, 1, 0)), fin)
    }

    @Test fun loQueNoEsDelContratoSeIgnora() {
        assertNull(Preprocesamiento.evento("[ 35%] Extrayendo imágenes"))     // salida de texto, sin --json
        assertNull(Preprocesamiento.evento("""{"tipo": "otro"}"""))
        assertNull(Preprocesamiento.evento(""))
    }

    @Test fun sinRecorridoSeCuenta() {
        val e = Preprocesamiento.evento("""{"tipo": "fin", "resumen": {"imagenes_finales": 0, "plan_b": true}}""") as Preprocesamiento.Evento.Fin
        assertEquals(1, e.resumen.sinRecorrido)
    }

    @Test fun elAvanceEsDeTodosLosVideos() {
        assertEquals(50, Preprocesamiento.total(0, 1, 50))
        assertEquals(25, Preprocesamiento.total(0, 2, 50))    // mitad del primero de dos
        assertEquals(75, Preprocesamiento.total(1, 2, 50))    // mitad del segundo de dos
        assertEquals(100, Preprocesamiento.total(1, 2, 100))
    }

    @Test fun losResumenesDeVariosVideosSeSuman() {
        val a = Preprocesamiento.Resumen(73, 2, 7, 1, 0)
        val b = Preprocesamiento.Resumen(40, 0, 3, 0, 1)
        assertEquals(Preprocesamiento.Resumen(113, 2, 10, 1, 1), a + b)
    }

    @Test fun cuentaLasImagenesListasDeTodosLosVideos() {
        val dir = Files.createTempDirectory("preproceso").toFile()
        try {
            File(dir, "DJI_0001/imagenes_procesadas").mkdirs()
            File(dir, "DJI_0002/imagenes_procesadas").mkdirs()
            repeat(3) { File(dir, "DJI_0001/imagenes_procesadas/i$it.jpg").writeText("x") }
            repeat(2) { File(dir, "DJI_0002/imagenes_procesadas/i$it.jpg").writeText("x") }
            File(dir, "DJI_0001/frames_originales").mkdirs()
            File(dir, "DJI_0001/frames_originales/f.jpg").writeText("x")   // los originales no cuentan
            assertEquals(5, Preprocesamiento.imagenesListas(dir))
            assertEquals(0, Preprocesamiento.imagenesListas(File(dir, "no-existe")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun elComandoNoEnviaANingunServidor() {
        val cmd = PreprocessModule.command(File("C:/m"), File("C:/v/DJI_0001.MP4"), File("C:/s"), "vuelo_salida-1")
        assertEquals(listOf("main.py", "--video", File("C:/v/DJI_0001.MP4").path, "--salida", File("C:/s").path, "--mision", "vuelo_salida-1", "--json"), cmd.drop(1))
        assertEquals(false, cmd.contains("--servidor"))
    }
}
