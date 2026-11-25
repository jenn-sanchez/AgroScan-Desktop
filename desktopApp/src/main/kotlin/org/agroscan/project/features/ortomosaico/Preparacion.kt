package org.agroscan.project.features.ortomosaico

/** Lo que una salida tiene listo para armar el ortomosaico. */
data class SalidaArmado(
    /** Posición en el vuelo, desde 0. */
    val indice: Int,
    /** El registro de vuelo (`registro.csv`) llegó del teléfono. */
    val registro: Boolean,
    /** Videos importados de la microSD. */
    val videos: Int,
    /** Imágenes que dejó el preprocesamiento. */
    val imagenes: Int
) {
    val numero: Int get() = indice + 1
    val lista: Boolean get() = registro && imagenes > 0
}

/** Para qué sirve cada punto de la revisión: si no hay problema, es solo información; un aviso deja armar; un bloqueo no. */
enum class Nivel { OK, AVISO, BLOQUEO }

/** Algo que el usuario puede hacer desde la revisión para resolver un punto. */
enum class Accion(val etiqueta: String) {
    IR_A_PROCESAMIENTO("Ir a Procesamiento")
}

/** Un punto de la revisión previa: qué pasa y, si hay problema, qué hacer. */
data class Requisito(val nivel: Nivel, val titulo: String, val detalle: String? = null, val accion: Accion? = null)

data class Evaluacion(val requisitos: List<Requisito>) {
    val puedeArmar: Boolean get() = requisitos.none { it.nivel == Nivel.BLOQUEO }

    /** El primer motivo que impide armar, para decirlo junto al botón. */
    val motivoBloqueo: Requisito? get() = requisitos.firstOrNull { it.nivel == Nivel.BLOQUEO }
}

/**
 * La revisión previa a armar un ortomosaico, como lógica pura y probada: dice si se puede armar y, si no, por qué y qué hacer. Cada
 * mensaje cuenta qué pasó y cómo resolverlo, en el lenguaje de quien opera el dron.
 */
object Preparacion {

    /** Por debajo de esto no se arma: un mosaico a 4 cm por píxel y sus teselas ocupan GB. */
    const val ESPACIO_MINIMO_BYTES = 3L * 1_000_000_000

    /** Por debajo de esto se avisa: un lote grande puede necesitar más. */
    const val ESPACIO_HOLGADO_BYTES = 10L * 1_000_000_000

    /**
     * @param incluidas índices de las salidas que el usuario quiere incluir
     * @param existente el ortomosaico que ya tiene este vuelo, si lo tiene
     * @param ocupado hay otro proceso pesado en marcha (preprocesamiento u otro armado)
     * @param armadoNoDisponible por qué esta PC no puede armar (falta el módulo o su entorno de Python); null si puede
     */
    fun evaluar(
        salidas: List<SalidaArmado>,
        incluidas: Set<Int>,
        espacioLibreBytes: Long,
        ocupado: Boolean,
        armadoNoDisponible: String?,
        existente: Entrada? = null
    ): Evaluacion {
        val r = mutableListOf<Requisito>()
        val elegidas = salidas.filter { it.indice in incluidas }

        if (elegidas.isEmpty()) {
            r += Requisito(Nivel.BLOQUEO, "Elige al menos una salida", "Marca las salidas del vuelo que quieres incluir en el ortomosaico.")
        }
        elegidas.filterNot { it.registro }.forEach {
            r += Requisito(
                Nivel.BLOQUEO, "Salida ${it.numero}: falta el registro de vuelo",
                "El registro llega solo del teléfono al terminar la salida. Si volaste sin la PC cerca, en el teléfono pulsa «Enviar vuelos pendientes»."
            )
        }
        elegidas.filter { it.registro && it.imagenes == 0 }.forEach {
            r += Requisito(
                Nivel.BLOQUEO, "Salida ${it.numero}: faltan las imágenes preprocesadas",
                if (it.videos == 0) "Primero importa su video desde la microSD y después preprocésalo."
                else "Preprocesa su video para sacar los cuadros del mosaico.",
                Accion.IR_A_PROCESAMIENTO
            )
        }
        if (elegidas.isNotEmpty() && elegidas.all { it.lista }) {
            r += Requisito(
                Nivel.OK, "Registro e imágenes listos",
                "${elegidas.size} ${if (elegidas.size == 1) "salida incluida" else "salidas incluidas"}, con ${elegidas.sumOf { it.imagenes }} imágenes en total."
            )
        }
        val sinIncluir = salidas.filter { it.indice !in incluidas && it.lista }
        if (sinIncluir.isNotEmpty() && elegidas.isNotEmpty()) {
            r += Requisito(
                Nivel.AVISO, "Hay salidas sin incluir",
                "El ortomosaico puede quedar incompleto: faltan las salidas ${sinIncluir.joinToString(", ") { it.numero.toString() }}."
            )
        }

        r += when {
            espacioLibreBytes < ESPACIO_MINIMO_BYTES -> Requisito(
                Nivel.BLOQUEO, "Poco espacio en el disco: quedan ${Formato.bytes(espacioLibreBytes)}",
                "Se necesitan al menos ${Formato.bytes(ESPACIO_MINIMO_BYTES)}. Libera espacio, borra ortomosaicos que ya no uses o cambia la carpeta de datos en Procesamiento."
            )
            espacioLibreBytes < ESPACIO_HOLGADO_BYTES -> Requisito(
                Nivel.AVISO, "Espacio justo: quedan ${Formato.bytes(espacioLibreBytes)}",
                "Un lote grande puede necesitar más de ${Formato.bytes(ESPACIO_HOLGADO_BYTES)}."
            )
            else -> Requisito(Nivel.OK, "Espacio en el disco suficiente", "Quedan ${Formato.bytes(espacioLibreBytes)}.")
        }

        if (ocupado) {
            r += Requisito(Nivel.BLOQUEO, "Hay otro proceso en marcha", "Espera a que termine el preprocesamiento o el armado actual: solo se puede uno a la vez.")
        }
        when {
            existente?.ficha?.estado == EstadoOrtomosaico.PROCESANDO ->
                r += Requisito(Nivel.BLOQUEO, "Este vuelo ya se está armando", "Espera a que termine o cancélalo antes de volver a armarlo.")
            existente != null ->
                r += Requisito(
                    Nivel.AVISO, "Este vuelo ya tiene un ortomosaico",
                    "Si armas otra vez, el de ${Formato.fecha(existente.ficha.vueloInicioMs)} se reemplaza, y solo cuando el nuevo termine bien."
                )
        }
        if (armadoNoDisponible != null) {
            r += Requisito(Nivel.BLOQUEO, "El armado no está disponible en esta PC", armadoNoDisponible)
        }
        return Evaluacion(r)
    }
}
