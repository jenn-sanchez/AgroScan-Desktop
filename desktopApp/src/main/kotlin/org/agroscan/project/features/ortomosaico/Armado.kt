package org.agroscan.project.features.ortomosaico

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.agroscan.project.core.DataFolder
import org.agroscan.project.core.EventLog
import org.agroscan.project.core.ModuloPython
import org.agroscan.project.features.flightlog.FlightLogStore
import org.agroscan.project.features.preproceso.Preprocesamiento
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Cómo terminó un armado, con el texto que ve el usuario. */
data class Cierre(
    val resultado: Resultado,
    val texto: String,
    /** Lote del ortomosaico, para ir a verlo. Solo si quedó listo. */
    val loteUuid: String? = null,
    val advertencias: List<String> = emptyList()
) {
    enum class Resultado { LISTO, ERROR, CANCELADO }
}

/**
 * El armado de ortomosaicos en marcha (o el último) y lo que la pantalla muestra de él. Ejecuta el módulo de Python
 * ([OrtomosaicoModule]) por detrás y lee su avance. Vive aquí y no en la pantalla: cambiar de pestaña no lo corta. Solo hay uno a la vez.
 *
 * El módulo arma en una carpeta de trabajo (`.tmp_…`) y el resultado se publica al terminar bien ([OrtomosaicoStore.publicar]): un armado
 * que falla o se cancela nunca estropea el ortomosaico que el vuelo ya tenía. Si el vuelo no tenía ninguno, queda una ficha «armando»
 * (y al final «con error» o «cancelado») para que el historial lo muestre.
 */
object Armado {

    data class Estado(
        val activo: Boolean = false,
        val vueloUuid: String? = null,
        /** Qué se arma, para mostrarlo ("Finca Sánchez · Sector 1"). */
        val titulo: String = "",
        val porcentaje: Int = 0,
        val etapa: String = "",
        /** Cómo terminó el último; se queda hasta que el usuario lo descarta. */
        val cierre: Cierre? = null
    )

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var proceso: Process? = null
    @Volatile private var cancelar = false

    init {
        // Si se cierra la app a mitad, el módulo no debe quedar trabajando solo
        Runtime.getRuntime().addShutdownHook(Thread { proceso?.destroyForcibly() })
    }

    /** Los vuelos que se están armando ahora mismo. */
    fun activos(): Set<String> = _estado.value.takeIf { it.activo }?.vueloUuid?.let(::setOf).orEmpty()

    /** Por qué esta PC no puede armar (falta el módulo o su entorno), o null si puede. */
    fun noDisponible(): String? = OrtomosaicoModule.ubicar().exceptionOrNull()?.message

    /**
     * Pide armar el ortomosaico. Aquí solo se revisa lo inmediato; lo demás (leer la base, armar) sigue por detrás y su resultado queda en
     * [estado]. Falla enseguida, con el motivo, si ya hay un armado, falta el módulo o el disco no alcanza.
     */
    fun iniciar(solicitud: Solicitud, usuarioId: UUID): Result<Unit> {
        if (_estado.value.activo) return Result.failure(IllegalStateException("Ya se está armando un ortomosaico: espera a que termine o cancélalo."))
        val modulo = OrtomosaicoModule.ubicar().getOrElse { return Result.failure(IllegalStateException(it.message)) }
        val libre = OrtomosaicoStore.espacioLibre(DataFolder.ortomosaicos)
        if (libre < Preparacion.ESPACIO_MINIMO_BYTES) {
            return Result.failure(IllegalStateException("Poco espacio en el disco: quedan ${Formato.bytes(libre)} y se necesitan al menos ${Formato.bytes(Preparacion.ESPACIO_MINIMO_BYTES)}."))
        }
        cancelar = false
        _estado.value = Estado(activo = true, vueloUuid = solicitud.vueloUuid, etapa = "Preparando")
        scope.launch {
            val cierre = runCatching { preparar(solicitud, usuarioId, modulo) }.getOrElse {
                EventLog.log("ortomosaico", "Falló antes de armar: ${it.stackTraceToString()}")
                Cierre(Cierre.Resultado.ERROR, "No se pudo empezar a armar el ortomosaico: ${it.message ?: it::class.simpleName}.")
            }
            terminar(cierre)
        }
        return Result.success(Unit)
    }

    /** Corta el armado en el acto: lo que dejó a medias en la carpeta de trabajo no vale y se borra. */
    fun cancelar() {
        cancelar = true
        proceso?.destroy()
    }

    /** El usuario ya vio cómo terminó. */
    fun descartarCierre() {
        if (!_estado.value.activo) _estado.value = _estado.value.copy(cierre = null)
    }

    private fun preparar(solicitud: Solicitud, usuarioId: UUID, modulo: ModuloPython): Cierre {
        val contexto = OrtomosaicoDb.contextoDeVuelo(solicitud.vueloUuid).getOrElse {
            return Cierre(Cierre.Resultado.ERROR, "No se pudo leer el lote de este vuelo en la base de datos (${OrtomosaicoDb.motivo(it)}).")
        } ?: return Cierre(
            Cierre.Resultado.ERROR,
            "Este vuelo todavía no está en la base de datos de esta PC, y de ahí sale su lote. Falta que el teléfono envíe su historial."
        )
        val salidas = FlightLogStore(DataFolder.vuelos).salidas().filter { it.info.vueloUuid == solicitud.vueloUuid }
        val primera = salidas.minByOrNull { it.info.indice }
            ?: return Cierre(Cierre.Resultado.ERROR, "No se encontraron las salidas de este vuelo en esta PC.")
        val titulo = "${contexto.loteNombre} · ${contexto.misionNombre}"
        _estado.value = _estado.value.copy(titulo = titulo)

        val ahora = System.currentTimeMillis()
        val base = Ficha(
            estado = EstadoOrtomosaico.PROCESANDO, vueloUuid = solicitud.vueloUuid, loteUuid = contexto.loteUuid, loteNombre = contexto.loteNombre,
            misionNombre = contexto.misionNombre, usuarioId = usuarioId.toString(), vueloInicioMs = primera.info.vueloInicioMs, creadoMs = ahora,
            terminadoMs = null, calidad = solicitud.calidad.clave, imagenes = solicitud.imagenes.clave
        )
        val store = OrtomosaicoStore(DataFolder.ortomosaicos)
        val log = File(EventLog.dir.apply { mkdirs() }, "ortomosaico-${LocalDate.now()}.log")
        EventLog.log("ortomosaico", "Inicio: $titulo · salidas ${solicitud.salidas.sorted()} · ${solicitud.calidad.clave} · ${solicitud.imagenes.clave}")
        val referencia = EntradaArmado.referencia(
            store.listar().filter { it.ficha.loteUuid == contexto.loteUuid }, solicitud.vueloUuid, primera.info.vueloInicioMs
        )
        return Armador(
            store = store,
            lanzar = { entrada, salida -> OrtomosaicoModule.proceso(modulo, entrada, salida) },
            registrarBase = { ficha, bytes -> OrtomosaicoDb.registrar(ficha, usuarioId, bytes) },
            alProgreso = { p, etapa -> _estado.value = _estado.value.copy(porcentaje = p, etapa = etapa) },
            cancelado = { cancelar },
            alProceso = { proceso = it },
            log = log
        ).ejecutar(base, EntradaArmado.json(solicitud, salidas, contexto, referencia), store.porVuelo(solicitud.vueloUuid), referencia)
    }

    private fun terminar(cierre: Cierre) {
        EventLog.log("ortomosaico", "${cierre.resultado}: ${cierre.texto}")
        proceso = null
        _estado.value = _estado.value.copy(
            activo = false, cierre = cierre, porcentaje = if (cierre.resultado == Cierre.Resultado.LISTO) 100 else _estado.value.porcentaje,
            etapa = when (cierre.resultado) { Cierre.Resultado.LISTO -> "Ortomosaico listo"; Cierre.Resultado.CANCELADO -> "Cancelado"; else -> "Se detuvo por un error" }
        )
    }
}

/**
 * Un armado, de la orden al resultado publicado. Aparte de [Armado] (que es el estado de toda la app) para poder probarlo con un módulo
 * de mentira: no sabe de pantallas, de la base real ni de dónde está el módulo.
 */
internal class Armador(
    private val store: OrtomosaicoStore,
    private val lanzar: (entrada: File, salida: File) -> ProcessBuilder,
    private val registrarBase: (Ficha, Long?) -> Result<Unit>,
    private val alProgreso: (Int, String) -> Unit,
    private val cancelado: () -> Boolean,
    private val alProceso: (Process?) -> Unit,
    private val log: File
) {
    /**
     * Arma el ortomosaico de [base] (una ficha «armando») con la orden [entradaJson]. [existente] es el que el vuelo ya tenía, si lo tenía;
     * [referencia] es la fecha del lote con la que se pidió alinear este (solo para contarlo al terminar).
     */
    fun ejecutar(base: Ficha, entradaJson: String, existente: Entrada?, referencia: Entrada? = null): Cierre {
        val destino = store.carpetaDe(base)
        val trabajo = store.carpetaTemporal(base)
        trabajo.deleteRecursively()
        trabajo.mkdirs()
        val orden = File(trabajo, Archivos.ENTRADA).apply { writeText(entradaJson, Charsets.UTF_8) }
        // Sin ortomosaico previo, la ficha «armando» ya aparece en el historial; con uno, sigue viéndose el anterior hasta que el nuevo esté listo
        if (existente == null) store.guardarFicha(destino, base)

        val resultado = correr(orden, trabajo)
        return when {
            cancelado() -> {
                trabajo.deleteRecursively()
                cerrarSinResultado(base, existente, EstadoOrtomosaico.CANCELADO, "Se canceló antes de terminar.")
                Cierre(Cierre.Resultado.CANCELADO, "Armado cancelado.${if (existente != null) " Se conserva el ortomosaico anterior." else ""}")
            }
            resultado.isFailure -> {
                trabajo.deleteRecursively()
                val motivo = resultado.exceptionOrNull()?.message ?: "Error desconocido."
                cerrarSinResultado(base, existente, EstadoOrtomosaico.ERROR, motivo)
                Cierre(Cierre.Resultado.ERROR, motivo + if (existente != null) " Se conserva el ortomosaico anterior." else "")
            }
            else -> publicar(base, existente, trabajo, resultado.getOrThrow(), referencia)
        }
    }

    private fun publicar(base: Ficha, existente: Entrada?, trabajo: File, r: OrtomosaicoModule.Resumen, referencia: Entrada?): Cierre {
        val faltan = listOf(Archivos.GEOTIFF, Archivos.VISTA, Archivos.MINIATURA, Archivos.GEOREF).filterNot { File(trabajo, it).isFile }
        if (faltan.isNotEmpty()) {
            trabajo.deleteRecursively()
            val motivo = "El módulo dijo que terminó pero faltan sus archivos (${faltan.joinToString()}). Vuelve a armarlo."
            cerrarSinResultado(base, existente, EstadoOrtomosaico.ERROR, motivo)
            return Cierre(Cierre.Resultado.ERROR, motivo)
        }
        val ficha = base.copy(
            estado = EstadoOrtomosaico.LISTO, terminadoMs = System.currentTimeMillis(), motor = r.motor, imagenesUsadas = r.imagenesUsadas,
            imagenesTotal = r.imagenesTotal, coberturaPct = r.coberturaPct, areaHa = r.areaHa, gsdCmPx = r.gsdCmPx, crs = r.crs,
            limites = r.limites, errorEstimadoM = r.errorEstimadoM, error = null
        )
        store.guardarFicha(trabajo, ficha)
        val bytes = OrtomosaicoStore.tamano(trabajo)
        store.publicar(trabajo, ficha).onFailure {
            trabajo.deleteRecursively()
            val motivo = "El ortomosaico se armó pero no se pudo guardar en su carpeta: ${it.message}. Vuelve a armarlo."
            cerrarSinResultado(base, existente, EstadoOrtomosaico.ERROR, motivo)
            return Cierre(Cierre.Resultado.ERROR, motivo)
        }
        val advertencias = r.advertencias.toMutableList()
        registrarBase(ficha, bytes).onFailure {
            EventLog.log("ortomosaico", "No se pudo registrar en la base ${ficha.vueloUuid}: ${it.message}")
            advertencias += "No se pudo anotar en la base de datos (${OrtomosaicoDb.motivo(it)}); el ortomosaico se ve igual."
        }
        val datos = listOfNotNull(
            r.areaHa?.let(Formato::hectareas), r.coberturaPct?.let { "${Formato.porcentaje(it)} del lote cubierto" }, r.gsdCmPx?.let(Formato::resolucion),
            r.teselas?.let { "$it teselas para la IA" },
            if (r.alineadoCon != null && referencia != null) {
                "alineado con el ortomosaico del ${Formato.fecha(referencia.ficha.vueloInicioMs)}" + (r.desacuerdoM?.let { " (desacuerdo medido ${"%.1f".format(java.util.Locale.ROOT, it)} m)" } ?: "")
            } else null
        )
        return Cierre(
            Cierre.Resultado.LISTO,
            "Ortomosaico del ${Formato.fecha(ficha.vueloInicioMs)} listo" + if (datos.isEmpty()) "." else ": ${datos.joinToString(" · ")}.",
            ficha.loteUuid, advertencias
        )
    }

    /** Un armado que no llegó a nada: si el vuelo no tenía ortomosaico, queda su ficha con el motivo; si tenía, no se toca. */
    private fun cerrarSinResultado(base: Ficha, existente: Entrada?, estado: EstadoOrtomosaico, motivo: String) {
        if (existente != null) return
        val ficha = base.copy(estado = estado, terminadoMs = System.currentTimeMillis(), error = motivo)
        runCatching { store.guardarFicha(store.carpetaDe(base), ficha) }.onFailure { EventLog.log("ortomosaico", "No se pudo guardar la ficha: ${it.message}") }
        registrarBase(ficha, null).onFailure { EventLog.log("ortomosaico", "No se pudo registrar en la base ${ficha.vueloUuid}: ${it.message}") }
    }

    /** Ejecuta el módulo y devuelve su resumen, o el motivo por el que no lo hay. */
    private fun correr(orden: File, trabajo: File): Result<OrtomosaicoModule.Resumen> {
        val p = try {
            lanzar(orden, trabajo)
                // El registro detallado del módulo sale por la salida de errores: hay que leerlo o el proceso se traba al llenarse
                .redirectError(ProcessBuilder.Redirect.appendTo(log))
                .start()
        } catch (e: java.io.IOException) {
            return Result.failure(IllegalStateException("No se pudo ejecutar el módulo de ortomosaicos (${e.message}). Reinstala AgroScan."))
        }
        alProceso(p)
        var resultado: Result<OrtomosaicoModule.Resumen>? = null
        p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { linea ->
            when (val e = OrtomosaicoModule.evento(linea)) {
                is OrtomosaicoModule.Evento.Progreso -> alProgreso(e.porcentaje, e.etapa)
                is OrtomosaicoModule.Evento.Fin -> resultado = Result.success(e.resumen)
                is OrtomosaicoModule.Evento.Error -> resultado = Result.failure(IllegalStateException(e.mensaje))
                null -> {}   // una línea que no es del contrato se ignora
            }
        }
        val codigo = if (p.waitFor(10, TimeUnit.SECONDS)) p.exitValue() else { p.destroyForcibly(); -1 }
        return resultado ?: Result.failure(IllegalStateException("El módulo terminó sin dar resultado (código $codigo); el detalle está en ${log.name}."))
    }
}
