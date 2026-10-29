package org.agroscan.project.features.flightlog

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.math.abs

/**
 * Copia los videos de la microSD del dron a la carpeta de su salida (`salida-N\video\`), para que el módulo de procesamiento
 * los tenga junto al registro y las marcas. Se copian (no se mueven ni se referencian): la tarjeta se borra para el siguiente vuelo.
 * La copia es idéntica byte a byte: no hay compresión ni pérdida de calidad.
 *
 * Cada video se reparte solo, por la hora: la fecha del archivo es cuando terminó de grabarse y el teléfono guardó en cada salida
 * cuándo empezó y terminó de grabar (`video_inicio_ms`/`video_fin_ms`, la misma hora que pone el dron). Los videos largos el dron
 * los parte en varios archivos: todos caen dentro de la misma salida. Supone que el teléfono y la PC están en la misma zona horaria
 * (la fecha de un archivo de la microSD no la trae). Los videos que no son de ninguna salida (vuelos manuales) se ignoran.
 */
class VideoImporter(private val store: FlightLogStore) {

    data class Resultado(val copiados: Int, val yaEstaban: Int, val sinSalida: List<String>, val cancelado: Boolean = false)

    /** Avance de la copia: qué archivo va y cuántos bytes lleva de **todos** los que hay que copiar. */
    data class Progreso(
        val archivo: Int,
        val archivos: Int,
        val nombre: String,
        val copiados: Long,
        val total: Long,
        /** Velocidad reciente, en bytes por segundo (0 mientras no hay medida). */
        val bytesPorSegundo: Long
    ) {
        val fraccion: Float get() = if (total > 0) (copiados.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

        /** Segundos que faltan al ritmo actual; null si todavía no hay velocidad. */
        val segundosRestantes: Long? get() = if (bytesPorSegundo > 0) (total - copiados).coerceAtLeast(0) / bytesPorSegundo else null
    }

    /**
     * Bloqueante (copia GB): llamar fuera del hilo de UI. [progreso] se llama en cada trozo copiado; [cancelado] se consulta entre
     * trozos: si responde true se borra el archivo a medias y se devuelve lo copiado hasta ahí con `cancelado = true`.
     */
    fun importar(origen: File, progreso: (Progreso) -> Unit = {}, cancelado: () -> Boolean = { false }): Resultado {
        val salidas = store.salidas().filter { it.info.videoInicioMs != null && it.info.videoFinMs != null }
        val videos = origen.walk().maxDepth(4).filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }.sortedBy { it.name }.toList()
        var yaEstaban = 0
        val sinSalida = mutableListOf<String>()
        // Primero se decide qué hay que copiar: así el porcentaje es sobre el total y no sobre cada archivo
        val pendientes = mutableListOf<Pair<File, File>>()
        videos.forEach { video ->
            val destino = match(video.lastModified(), salidas) ?: return@forEach run { sinSalida += video.name }
            val file = File(destino.videoDir, video.name)
            if (file.exists() && file.length() == video.length()) yaEstaban++ else pendientes += video to file
        }
        val total = pendientes.sumOf { it.first.length() }
        val velocidad = Velocidad()
        var hecho = 0L
        var copiados = 0
        pendientes.forEachIndexed { i, (video, file) ->
            file.parentFile.mkdirs()
            // Se copia aparte y se renombra al final: sacar la tarjeta a mitad nunca deja un video incompleto con el nombre final
            val part = File(file.parentFile, file.name + ".part")
            val completo = copiar(video, part, cancelado) { n ->
                hecho += n
                progreso(Progreso(i + 1, pendientes.size, video.name, hecho, total, velocidad.actualizar(hecho)))
            }
            if (!completo) return Resultado(copiados, yaEstaban, sinSalida, cancelado = true)
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            copiados++
        }
        return Resultado(copiados, yaEstaban, sinSalida)
    }

    /**
     * Copia por trozos grandes (los lectores USB rinden mejor con lecturas grandes) avisando cada trozo. Deja la misma fecha del
     * original: el importador la usa para saber a qué salida pertenece. false si se canceló (y borra el archivo a medias).
     */
    private fun copiar(origen: File, destino: File, cancelado: () -> Boolean, avance: (Long) -> Unit): Boolean {
        var completo = true
        try {
            FileChannel.open(origen.toPath(), StandardOpenOption.READ).use { entrada ->
                FileChannel.open(destino.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { salida ->
                    val buffer = ByteBuffer.allocateDirect(TROZO_BYTES)
                    while (true) {
                        if (cancelado()) {
                            completo = false
                            break
                        }
                        buffer.clear()
                        val leidos = entrada.read(buffer)
                        if (leidos < 0) break
                        buffer.flip()
                        while (buffer.hasRemaining()) salida.write(buffer)
                        avance(leidos.toLong())
                    }
                }
            }
        } catch (e: Exception) {
            destino.delete()   // tarjeta sacada, disco lleno...: no queda un .part a medias
            throw e
        }
        // Ya con los archivos cerrados (en Windows no se borra uno abierto)
        if (!completo) {
            destino.delete()
            return false
        }
        Files.setLastModifiedTime(destino.toPath(), Files.getLastModifiedTime(origen.toPath()))
        return true
    }

    /**
     * Velocidad de copia suavizada: mide cada medio segundo y mezcla con lo anterior para que el número no salte.
     * El reloj entra por parámetro para probarla.
     */
    class Velocidad(private val ahoraMs: () -> Long = System::currentTimeMillis) {
        private var tUltimo = ahoraMs()
        private var bytesUltimo = 0L
        private var suave = 0.0

        /** [bytesTotales] copiados hasta ahora; devuelve bytes por segundo. */
        fun actualizar(bytesTotales: Long): Long {
            val ahora = ahoraMs()
            val dt = ahora - tUltimo
            if (dt >= VENTANA_MS) {
                val instante = (bytesTotales - bytesUltimo) * 1000.0 / dt
                suave = if (suave == 0.0) instante else suave * 0.7 + instante * 0.3
                tUltimo = ahora
                bytesUltimo = bytesTotales
            }
            return suave.toLong()
        }

        private companion object {
            const val VENTANA_MS = 500L
        }
    }

    companion object {
        /** Margen para relojes que no coinciden al segundo (teléfono, dron, fecha del archivo). Entre baterías pasan más que esto. */
        const val TOLERANCIA_MS = 2 * 60_000L

        private const val TROZO_BYTES = 8 * 1024 * 1024

        /** Salida en cuya grabación cae un video que terminó en [finVideoMs]; si dos cuadran, la que terminó más cerca. */
        fun match(finVideoMs: Long, salidas: List<FlightLogStore.Stored>): FlightLogStore.Stored? =
            salidas.filter { s ->
                val inicio = s.info.videoInicioMs ?: return@filter false
                val fin = s.info.videoFinMs ?: return@filter false
                finVideoMs in (inicio - TOLERANCIA_MS)..(fin + TOLERANCIA_MS)
            }.minByOrNull { abs(it.info.videoFinMs!! - finVideoMs) }
    }
}
