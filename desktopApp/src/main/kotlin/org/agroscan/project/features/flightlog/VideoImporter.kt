package org.agroscan.project.features.flightlog

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs

/**
 * Copia los videos de la microSD del dron a la carpeta de su salida (`salida-N\video\`), para que el módulo de procesamiento
 * los tenga junto al registro y las marcas. Se copian (no se mueven ni se referencian): la tarjeta se borra para el siguiente vuelo.
 *
 * Cada video se reparte solo, por la hora: la fecha del archivo es cuando terminó de grabarse y el teléfono guardó en cada salida
 * cuándo empezó y terminó de grabar (`video_inicio_ms`/`video_fin_ms`, la misma hora que pone el dron). Los videos largos el dron
 * los parte en varios archivos: todos caen dentro de la misma salida. Supone que el teléfono y la PC están en la misma zona horaria
 * (la fecha de un archivo de la microSD no la trae). Los videos que no son de ninguna salida (vuelos manuales) se ignoran.
 */
class VideoImporter(private val store: FlightLogStore) {

    data class Resultado(val copiados: Int, val yaEstaban: Int, val sinSalida: List<String>)

    /** Bloqueante (copia GB): llamar fuera del hilo de UI. [progreso] recibe un texto para mostrar. */
    fun importar(origen: File, progreso: (String) -> Unit = {}): Resultado {
        val salidas = store.salidas().filter { it.info.videoInicioMs != null && it.info.videoFinMs != null }
        val videos = origen.walk().maxDepth(4).filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }.sortedBy { it.name }.toList()
        var copiados = 0
        var yaEstaban = 0
        val sinSalida = mutableListOf<String>()
        videos.forEachIndexed { i, video ->
            val destino = match(video.lastModified(), salidas) ?: return@forEachIndexed run { sinSalida += video.name }
            val file = File(destino.videoDir, video.name)
            if (file.exists() && file.length() == video.length()) return@forEachIndexed run { yaEstaban++ }
            progreso("Copiando ${i + 1} de ${videos.size}: ${video.name} (%.1f GB)".format(video.length() / 1e9))
            file.parentFile.mkdirs()
            // Se copia aparte y se renombra al final: sacar la tarjeta a mitad nunca deja un video incompleto con el nombre final
            val part = File(file.parentFile, file.name + ".part")
            Files.copy(video.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            copiados++
        }
        return Resultado(copiados, yaEstaban, sinSalida)
    }

    companion object {
        /** Margen para relojes que no coinciden al segundo (teléfono, dron, fecha del archivo). Entre baterías pasan más que esto. */
        const val TOLERANCIA_MS = 2 * 60_000L

        /** Salida en cuya grabación cae un video que terminó en [finVideoMs]; si dos cuadran, la que terminó más cerca. */
        fun match(finVideoMs: Long, salidas: List<FlightLogStore.Stored>): FlightLogStore.Stored? =
            salidas.filter { s ->
                val inicio = s.info.videoInicioMs ?: return@filter false
                val fin = s.info.videoFinMs ?: return@filter false
                finVideoMs in (inicio - TOLERANCIA_MS)..(fin + TOLERANCIA_MS)
            }.minByOrNull { abs(it.info.videoFinMs!! - finVideoMs) }
    }
}
