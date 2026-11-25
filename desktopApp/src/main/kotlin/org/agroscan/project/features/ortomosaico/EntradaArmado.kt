package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.MiniJson
import org.agroscan.project.features.flightlog.FlightLogStore
import org.agroscan.project.features.preproceso.Preprocesamiento
import java.io.File
import java.util.Locale

/**
 * La orden que la app le da al módulo de ortomosaicos (`entrada.json`, formato en `ortomosaico/src/entrada.py`): qué salidas del vuelo,
 * con qué imágenes y qué ajustes. Todas las rutas son absolutas. También queda guardada junto al ortomosaico, para saber con qué se armó.
 */
object EntradaArmado {

    /** Un video de la salida con las imágenes que sacó el preprocesamiento. El video puede ya no estar: solo se usa para saber cuánto dura. */
    internal data class VideoConImagenes(val nombre: String, val video: File?, val imagenes: File)

    /**
     * Los videos de [salida] que ya tienen imágenes preprocesadas, en orden. Se parte de las carpetas del preprocesamiento (no de los
     * videos) para que siga valiendo aunque se hayan borrado los videos para liberar espacio.
     */
    internal fun videos(salida: FlightLogStore.Stored, origen: OrigenImagenes): List<VideoConImagenes> {
        val carpetaImagenes = if (origen == OrigenImagenes.CORREGIDAS) Preprocesamiento.CORREGIDAS else Preprocesamiento.ORIGINALES
        val importados = salida.videoDir.listFiles { f -> f.extension.equals("mp4", ignoreCase = true) }.orEmpty()
        return Preprocesamiento.carpeta(salida).listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { dir ->
            val imagenes = File(dir, carpetaImagenes)
            if (imagenes.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }.isNullOrEmpty()) null
            else VideoConImagenes(dir.name, importados.firstOrNull { it.nameWithoutExtension == dir.name }, imagenes)
        }
    }

    /**
     * La fecha con la que se alinea un ortomosaico nuevo: la **primera** del lote, que es el ancla (no se mueve). Sin referencia si este es el
     * primero, si la primera quedó sin vista o sin georreferencia (se armó con una versión anterior), o si no está lista.
     */
    fun referencia(delLote: List<Entrada>, vueloUuid: String, vueloInicioMs: Long): Entrada? =
        delLote.filter { it.listo && it.ficha.vueloUuid != vueloUuid && it.ficha.vueloInicioMs < vueloInicioMs }
            .minByOrNull { it.ficha.vueloInicioMs }
            ?.takeIf { File(it.dir, Archivos.GEOREF).isFile && it.tieneVista }

    /**
     * El `entrada.json` de [solicitud]: las salidas elegidas de [salidas] (las de ese vuelo), más lo que la base sabe del lote y, si la hay, la
     * fecha de [referencia] con la que alinear.
     */
    fun json(solicitud: Solicitud, salidas: List<FlightLogStore.Stored>, contexto: OrtomosaicoDb.ContextoVuelo, referencia: Entrada? = null): String {
        fun num(x: Double) = String.format(Locale.ROOT, "%.8f", x)
        val elegidas = salidas.filter { it.info.indice in solicitud.salidas }.sortedBy { it.info.indice }
        val salidasJson = elegidas.joinToString(",") { s ->
            val videos = videos(s, solicitud.imagenes).joinToString(",") { v ->
                """{"nombre":${MiniJson.quote(v.nombre)},"archivo":${v.video?.let { MiniJson.quote(it.path) } ?: "null"},"imagenes":${MiniJson.quote(v.imagenes.path)}}"""
            }
            """{"indice":${s.info.indice},"registro":${MiniJson.quote(s.registro.path)},"eventos":${MiniJson.quote(s.eventos.path)},""" +
                """"video_inicio_ms":${s.info.videoInicioMs ?: "null"},"videos":[$videos]}"""
        }
        val lote = contexto.contorno.joinToString(",") { "[${num(it.first)},${num(it.second)}]" }
        return """{"v":1,"vuelo":${MiniJson.quote(solicitud.vueloUuid)},"calidad":${MiniJson.quote(solicitud.calidad.clave)},""" +
            """"origen":${MiniJson.quote(solicitud.imagenes.clave)},"altura_copa_m":${contexto.alturaCopaM?.let(::num) ?: "null"},""" +
            """"lote":[$lote],""" +
            (referencia?.let { """"referencia":{"carpeta":${MiniJson.quote(it.dir.path)},"vuelo":${MiniJson.quote(it.ficha.vueloUuid)}},""" } ?: "") +
            """"salidas":[$salidasJson]}"""
    }
}
