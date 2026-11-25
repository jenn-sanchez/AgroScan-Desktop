package org.agroscan.project.features.ortomosaico

import org.agroscan.project.core.EventLog
import org.agroscan.project.core.MiniJson
import org.agroscan.project.features.flightlog.FlightLogStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId

/**
 * Guarda y lee los ortomosaicos en disco, una carpeta por lote y dentro una por vuelo:
 * ```
 * <root>\Finca-Sanchez_1a2b3c4d\          ← el lote (nombre legible + inicio de su UUID)
 *   2026-10-26_9f8e7d6c\                   ← el vuelo (fecha del vuelo + inicio de su UUID)
 *     ortomosaico.json  ortomosaico.tif  vista.jpg  miniatura.jpg  teselas\ …
 * ```
 * Cada carpeta se reconoce por el inicio del UUID, no por el nombre: si el lote se renombra, sus ortomosaicos siguen en su sitio.
 * Hay un solo ortomosaico por vuelo: armar otra vez lo reemplaza, pero solo cuando el nuevo ya está completo ([publicar]).
 * Las carpetas que empiezan con punto (`.tmp_`, `.previo_`, `.borrando_`) son trabajo a medias: nunca se listan.
 */
class OrtomosaicoStore(private val root: File) {

    /** Todos los ortomosaicos guardados (los de cualquier estado), sin orden. Una carpeta ilegible se ignora y se anota en el registro. */
    fun listar(): List<Entrada> =
        dirs(root).flatMap { lote -> dirs(lote).filterNot { it.name.startsWith(".") } }.mapNotNull { leer(it) }

    /** Lee la ficha de una carpeta; null si no hay o no es válida. */
    fun leer(dir: File): Entrada? {
        val archivo = File(dir, Archivos.FICHA)
        if (!archivo.isFile) return null
        val ficha = runCatching { Ficha.from(MiniJson.parse(archivo.readText(Charsets.UTF_8)) as Map<*, *>) }.getOrNull()
        if (ficha == null) EventLog.log("ortomosaico", "Ficha ilegible, se ignora: ${archivo.path}")
        return ficha?.let { Entrada(dir, it) }
    }

    fun porVuelo(vueloUuid: String): Entrada? = listar().firstOrNull { it.ficha.vueloUuid == vueloUuid }

    /** Dónde queda (o ya está) el ortomosaico de esta ficha. */
    fun carpetaDe(ficha: Ficha): File {
        val lote = dirs(root).firstOrNull { it.name.endsWith("_" + ficha.loteUuid.take(8)) }
            ?: File(root, "${FlightLogStore.slug(ficha.loteNombre)}_${ficha.loteUuid.take(8)}")
        val vuelo8 = "_" + ficha.vueloUuid.take(8)
        return dirs(lote).firstOrNull { !it.name.startsWith(".") && it.name.endsWith(vuelo8) }
            ?: File(lote, fecha(ficha.vueloInicioMs) + vuelo8)
    }

    /** Carpeta de trabajo donde se arma (junto a la final, para que mover una a la otra sea un renombrado y no una copia). */
    fun carpetaTemporal(ficha: Ficha): File = carpetaDe(ficha).let { File(it.parentFile, ".tmp_" + it.name) }

    /** Escribe la ficha de [dir] de una vez: un corte a mitad nunca deja una ficha a medias. */
    fun guardarFicha(dir: File, ficha: Ficha) {
        dir.mkdirs()
        val destino = File(dir, Archivos.FICHA)
        val tmp = File(dir, Archivos.FICHA + ".tmp")
        tmp.writeText(ficha.toJson(), Charsets.UTF_8)
        Files.move(tmp.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Pone [temporal] (ya armado, con su ficha) en el sitio del ortomosaico de ese vuelo. Si ya había uno, se aparta mientras tanto y
     * solo se borra cuando el nuevo ya está en su sitio; si algo falla, el anterior vuelve y no se pierde nada.
     */
    fun publicar(temporal: File, ficha: Ficha): Result<File> = runCatching {
        val destino = carpetaDe(ficha)
        destino.parentFile.mkdirs()
        val previo = File(destino.parentFile, ".previo_" + destino.name)
        previo.deleteRecursively()
        val habiaPrevio = destino.exists()
        if (habiaPrevio) Files.move(destino.toPath(), previo.toPath())
        try {
            Files.move(temporal.toPath(), destino.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            if (habiaPrevio) Files.move(previo.toPath(), destino.toPath())
            throw e
        }
        previo.deleteRecursively()
        destino
    }

    /**
     * Borra el ortomosaico y devuelve cuántos bytes se liberaron. No toca los videos ni los registros del vuelo. No se puede borrar
     * uno que se está armando. Primero se aparta (así deja de aparecer en el acto) y después se borra; si algo queda abierto por otro
     * programa, lo que sobre se limpia solo la próxima vez ([mantenimiento]).
     */
    fun eliminar(entrada: Entrada): Result<Long> = runCatching {
        check(entrada.ficha.estado != EstadoOrtomosaico.PROCESANDO) { "Se está armando: cancélalo antes de eliminarlo." }
        val liberado = tamano(entrada.dir)
        val apartada = File(entrada.dir.parentFile, ".borrando_" + entrada.dir.name)
        apartada.deleteRecursively()
        Files.move(entrada.dir.toPath(), apartada.toPath())
        if (!apartada.deleteRecursively()) {
            error("No se pudo borrar todo: algún programa está usando sus archivos (el Explorador o una vista previa). Ciérralos y vuelve a abrir esta pantalla para terminar de limpiar.")
        }
        entrada.dir.parentFile.takeIf { it != root && it.listFiles().isNullOrEmpty() }?.delete()
        EventLog.log("ortomosaico", "Eliminado ${entrada.dir.name} (${entrada.ficha.loteNombre}): $liberado bytes")
        liberado
    }

    /**
     * Pone en orden lo que dejó una interrupción: los ortomosaicos que figuran como «armando» sin proceso vivo (la app se cerró) pasan a
     * interrumpidos, y se borran los restos de trabajos a medias. [activos] son los vuelos que se están armando ahora mismo: esos no
     * se tocan. Devuelve cuántos se marcaron como interrumpidos.
     */
    fun mantenimiento(activos: Set<String>): Int {
        var marcados = 0
        dirs(root).forEach { lote ->
            dirs(lote).forEach { dir ->
                when {
                    dir.name.startsWith(".borrando_") || dir.name.startsWith(".previo_") -> dir.deleteRecursively()
                    dir.name.startsWith(".tmp_") -> if (activos.isEmpty()) dir.deleteRecursively()
                    else -> leer(dir)?.takeIf { it.ficha.estado == EstadoOrtomosaico.PROCESANDO && it.ficha.vueloUuid !in activos }?.let {
                        guardarFicha(
                            dir, it.ficha.copy(
                                estado = EstadoOrtomosaico.INTERRUMPIDO, terminadoMs = System.currentTimeMillis(),
                                error = "La app se cerró mientras se armaba. Vuelve a armarlo."
                            )
                        )
                        marcados++
                    }
                }
            }
        }
        return marcados
    }

    companion object {
        /** Lo que ocupa una carpeta con todo lo que tiene dentro. */
        fun tamano(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        /** Bytes libres en el disco de [carpeta] (aunque todavía no exista: se mira la primera carpeta existente hacia arriba). */
        fun espacioLibre(carpeta: File): Long =
            generateSequence(carpeta.absoluteFile) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L

        /** `AAAA-MM-DD` del día en que se voló, en la zona horaria de esta PC (igual que las carpetas de los vuelos). */
        internal fun fecha(vueloInicioMs: Long): String =
            Instant.ofEpochMilli(vueloInicioMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

        private fun dirs(parent: File): List<File> = parent.listFiles()?.filter { it.isDirectory }.orEmpty()
    }
}
