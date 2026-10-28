package org.agroscan.project.features.flightlog

import org.agroscan.project.core.MiniJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Lo que el teléfono dice de una salida al enviar su registro (formato en docs/PROTOCOLO_PC.md del proyecto del dron).
 * [indice] empieza en 0; los tiempos son epoch UTC en ms, la misma escala que la fecha de los archivos de video de la microSD.
 */
data class SalidaInfo(
    val uuid: String,
    val indice: Int,
    val estado: String,
    val inicioMs: Long,
    val finMs: Long?,
    val videoInicioMs: Long?,
    val videoFinMs: Long?,
    val vueloUuid: String,
    val vueloInicioMs: Long,
    val modo: String,
    val mision: String,
    val lote: String
) {
    /** Mismo formato que manda el teléfono, sin los archivos: así `salida.json` se lee con [from]. */
    fun toJson(): String {
        fun n(x: Long?) = x?.toString() ?: "null"
        return """{"v":1,"salida":{"uuid":"$uuid","indice":$indice,"estado":${MiniJson.quote(estado)},"inicio_ms":$inicioMs,""" +
            """"fin_ms":${n(finMs)},"video_inicio_ms":${n(videoInicioMs)},"video_fin_ms":${n(videoFinMs)}},""" +
            """"vuelo":{"uuid":"$vueloUuid","inicio_ms":$vueloInicioMs,"modo":${MiniJson.quote(modo)}},""" +
            """"mision":${MiniJson.quote(mision)},"lote":${MiniJson.quote(lote)}}"""
    }

    companion object {
        /**
         * Lee y valida lo que mandó el teléfono (o un `salida.json` guardado). null si falta algo o algo no es válido: los UUID
         * terminan en nombres de carpeta, así que solo se aceptan en su forma canónica.
         */
        fun from(j: Map<*, *>): SalidaInfo? {
            val s = j["salida"] as? Map<*, *> ?: return null
            val v = j["vuelo"] as? Map<*, *> ?: return null
            fun long(m: Map<*, *>, k: String) = (m[k] as? Double)?.toLong()
            return SalidaInfo(
                uuid = canonicalUuid(s["uuid"]) ?: return null,
                indice = (s["indice"] as? Double)?.toInt()?.takeIf { it in 0..999 } ?: return null,
                estado = (s["estado"] as? String)?.take(40) ?: "",
                inicioMs = long(s, "inicio_ms")?.takeIf { it > 0 } ?: return null,
                finMs = long(s, "fin_ms"),
                videoInicioMs = long(s, "video_inicio_ms"),
                videoFinMs = long(s, "video_fin_ms"),
                vueloUuid = canonicalUuid(v["uuid"]) ?: return null,
                vueloInicioMs = long(v, "inicio_ms")?.takeIf { it > 0 } ?: return null,
                modo = (v["modo"] as? String)?.take(20) ?: "",
                mision = (j["mision"] as? String)?.take(200) ?: "",
                lote = (j["lote"] as? String)?.take(200) ?: ""
            )
        }

        /** El UUID en minúsculas si es uno válido escrito de forma canónica; null si no (p. ej. "../x" o "1-1-1-1-1"). */
        fun canonicalUuid(value: Any?): String? {
            val text = (value as? String)?.lowercase() ?: return null
            return runCatching { UUID.fromString(text).toString() }.getOrNull()?.takeIf { it == text }
        }
    }
}

/**
 * Guarda en disco lo que llega del teléfono, una carpeta por vuelo y una subcarpeta por salida:
 * ```
 * <root>\2026-10-27_Finca-Sanchez_Sector-1_a1b2c3d4\
 *   vuelo.json
 *   salida-1\  registro.csv  eventos.jsonl  salida.json  video\ (lo copia el importador de la microSD)
 * ```
 * Cada carpeta de vuelo se reconoce por su UUID (dentro de `vuelo.json`), no por el nombre, que es solo para leerlo.
 * Los registros se escriben **una sola vez**: si una salida ya estaba, no se toca (un reintento del teléfono no la cambia y un
 * envío falso no la pisa). `salida.json` se escribe al final, así que si existe la salida está completa.
 */
class FlightLogStore(private val root: File) {

    data class Stored(val dir: File, val info: SalidaInfo) {
        val registro: File get() = File(dir, REGISTRO)
        val eventos: File get() = File(dir, EVENTOS)
        val videoDir: File get() = File(dir, "video")
    }

    /** Guarda el registro de una salida. Devuelve true si era nueva, false si ya estaba (entonces no la toca). */
    fun save(info: SalidaInfo, registroCsv: String, eventosJsonl: String): Boolean = synchronized(LOCK) {
        val dir = salidaDir(vueloDir(info), info)
        val meta = File(dir, SALIDA_JSON)
        if (meta.exists()) return false
        dir.mkdirs()
        write(File(dir, REGISTRO), registroCsv)
        write(File(dir, EVENTOS), eventosJsonl)
        write(meta, info.toJson())   // al final: su presencia marca la salida como completa
        true
    }

    /** Todas las salidas completas guardadas. */
    fun salidas(): List<Stored> =
        dirs(root).flatMap { vuelo -> dirs(vuelo).mapNotNull { s -> readSalida(s)?.let { Stored(s, it) } } }

    /** UUID de las salidas que ya están (las que el teléfono no necesita volver a enviar). */
    fun received(): Set<String> = salidas().map { it.info.uuid }.toSet()

    internal fun vueloDir(info: SalidaInfo): File {
        val uuid8 = "_" + info.vueloUuid.take(8)
        dirs(root).firstOrNull { (it.name.endsWith(uuid8) || it.name.endsWith(info.vueloUuid)) && readVueloUuid(it) == info.vueloUuid }
            ?.let { return it }
        val fecha = Instant.ofEpochMilli(info.vueloInicioMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val name = "${fecha}_${slug(info.lote)}_${slug(info.mision)}$uuid8"
        // Si otro vuelo ya tiene ese nombre (misma huella de 8 caracteres), se usa el UUID completo
        val dir = File(root, name).takeUnless { it.exists() } ?: File(root, "${name.removeSuffix(uuid8)}_${info.vueloUuid}")
        dir.mkdirs()
        write(
            File(dir, VUELO_JSON),
            """{"uuid":"${info.vueloUuid}","inicio_ms":${info.vueloInicioMs},"mision":${MiniJson.quote(info.mision)},""" +
                """"lote":${MiniJson.quote(info.lote)},"modo":${MiniJson.quote(info.modo)}}"""
        )
        return dir
    }

    private fun salidaDir(vuelo: File, info: SalidaInfo): File {
        val base = File(vuelo, "salida-${info.indice + 1}")
        val existing = readSalida(base)
        return if (existing == null || existing.uuid == info.uuid) base else File(vuelo, "salida-${info.indice + 1}_${info.uuid.take(8)}")
    }

    private fun readSalida(dir: File): SalidaInfo? =
        runCatching { SalidaInfo.from(MiniJson.parse(File(dir, SALIDA_JSON).readText()) as Map<*, *>) }.getOrNull()

    private fun readVueloUuid(dir: File): String? =
        runCatching { (MiniJson.parse(File(dir, VUELO_JSON).readText()) as Map<*, *>)["uuid"] as? String }.getOrNull()

    companion object {
        const val REGISTRO = "registro.csv"
        const val EVENTOS = "eventos.jsonl"
        const val SALIDA_JSON = "salida.json"
        const val VUELO_JSON = "vuelo.json"
        private val LOCK = Any()

        /** Cuántas marcas del video tiene el archivo de eventos de una salida. */
        fun marcas(eventos: File): Int =
            runCatching { eventos.useLines { lines -> lines.count { "\"evento\":\"marca\"" in it } } }.getOrDefault(0)

        /** Texto apto para nombre de carpeta: sin tildes, solo letras, números y guiones. */
        internal fun slug(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                .replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "sin-nombre" }

        private fun dirs(parent: File): List<File> = parent.listFiles()?.filter { it.isDirectory }.orEmpty()

        // Se escribe aparte y se mueve: un corte a mitad nunca deja un archivo a medias con el nombre final
        private fun write(file: File, text: String) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
