package org.agroscan.project.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.agroscan.project.features.ortomosaico.Biblioteca
import org.agroscan.project.features.ortomosaico.Comparacion
import org.agroscan.project.features.ortomosaico.Entrada
import org.agroscan.project.features.ortomosaico.EstadoOrtomosaico
import org.agroscan.project.features.ortomosaico.Formato
import org.agroscan.project.features.ortomosaico.LoteHistorial
import org.agroscan.project.features.ortomosaico.OrtomosaicoDb
import org.agroscan.project.features.ortomosaico.OrtomosaicoStore
import org.agroscan.project.features.ortomosaico.Piramide
import java.awt.Desktop
import java.io.File

/** Qué se ve a la derecha en Creados: el historial del lote, un ortomosaico a pantalla completa o dos fechas comparadas. */
private sealed interface VistaCreados {
    data object Historial : VistaCreados
    data class Ver(val dir: File) : VistaCreados
    data class Comparar(val a: File, val b: File) : VistaCreados
}

private val Rojo = Color(0xFFDC2626)

/**
 * La biblioteca de ortomosaicos: a la izquierda los lotes (polígonos) y a la derecha el historial del elegido, del más reciente al más
 * antiguo, para ver cómo cambia el cultivo con el tiempo. Desde cada fecha se puede ver, abrir su carpeta o eliminar; con dos o más
 * se pueden comparar.
 */
@Composable
internal fun CreadosPanel(
    biblioteca: Biblioteca,
    loteElegido: String?,
    onElegirLote: (String) -> Unit,
    onIrACrear: () -> Unit,
    onEliminar: (Entrada) -> Unit
) {
    if (biblioteca.lotes.isEmpty()) {
        EstadoVacio(
            Icons.Filled.Layers, "Todavía no hay ortomosaicos",
            "Aquí aparecerán ordenados por lote y fecha, para que compares cómo avanza el cultivo. Se crean desde un vuelo ya preprocesado.",
            "Ir a Crear", onIrACrear
        )
        return
    }
    val lote = biblioteca.lotes.firstOrNull { it.uuid == loteElegido } ?: biblioteca.lotes.first()
    var vista by remember(lote.uuid) { mutableStateOf<VistaCreados>(VistaCreados.Historial) }
    var porEliminar by remember { mutableStateOf<Entrada?>(null) }

    Row(Modifier.fillMaxSize()) {
        ListaDeLotes(biblioteca.lotes, lote.uuid, onElegirLote, Modifier.width(260.dp).fillMaxHeight())
        VerticalDivider(color = Border)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // Si lo que se estaba viendo ya no existe (se eliminó), se vuelve al historial
            val ver = (vista as? VistaCreados.Ver)?.let { v -> lote.ortomosaicos.firstOrNull { it.dir == v.dir } }
            val a = (vista as? VistaCreados.Comparar)?.let { v -> lote.listos.firstOrNull { it.dir == v.a } }
            val b = (vista as? VistaCreados.Comparar)?.let { v -> lote.listos.firstOrNull { it.dir == v.b } }
            when {
                ver != null -> VerOrtomosaico(lote, ver, { vista = VistaCreados.Historial }) { porEliminar = ver }
                a != null && b != null -> CompararOrtomosaicos(lote, a, b, { x, y -> vista = VistaCreados.Comparar(x.dir, y.dir) }) { vista = VistaCreados.Historial }
                else -> HistorialLote(
                    lote,
                    alVer = { vista = VistaCreados.Ver(it.dir) },
                    alComparar = { vista = lote.listos.let { l -> VistaCreados.Comparar(l[1].dir, l[0].dir) } },
                    alEliminar = { porEliminar = it },
                    alIrACrear = onIrACrear
                )
            }
        }
    }

    porEliminar?.let { entrada ->
        DialogoEliminar(entrada, alConfirmar = { porEliminar = null; onEliminar(entrada) }, alCancelar = { porEliminar = null })
    }
}

@Composable
private fun ListaDeLotes(lotes: List<LoteHistorial>, elegido: String, alElegir: (String) -> Unit, modifier: Modifier) {
    Column(modifier.background(Color.White).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Lotes", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextMuted, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        lotes.forEach { l ->
            val sel = l.uuid == elegido
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) SelectedBg else Color.Transparent)
                    .border(1.dp, if (sel) Green else Border, RoundedCornerShape(8.dp)).clickable { alElegir(l.uuid) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Miniatura(l.ultimo, Modifier.size(44.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.nombre, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        l.ultimo?.let { "${Formato.ortomosaicos(l.ortomosaicos.size)} · último ${Formato.fecha(it.ficha.vueloInicioMs)}" } ?: "Sin ortomosaicos todavía",
                        fontSize = 11.sp, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorialLote(
    lote: LoteHistorial, alVer: (Entrada) -> Unit, alComparar: () -> Unit, alEliminar: (Entrada) -> Unit, alIrACrear: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(lote.nombre, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextDark)
                Text(
                    listOfNotNull(lote.areaHa?.let(Formato::hectareas), Formato.ortomosaicos(lote.ortomosaicos.size)).joinToString(" · "),
                    fontSize = 12.sp, color = TextMuted
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                OutlinedButton(onClick = alComparar, enabled = lote.listos.size >= 2, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Filled.Compare, null, Modifier.size(16.dp), tint = if (lote.listos.size >= 2) Green else TextMuted)
                    Spacer(Modifier.width(6.dp))
                    Text("Comparar fechas", color = if (lote.listos.size >= 2) TextDark else TextMuted)
                }
                // Cuando no se puede, se dice por qué (un botón apagado sin razón deja al usuario adivinando)
                if (lote.listos.size < 2) Text("Hacen falta 2 ortomosaicos listos", fontSize = 10.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (lote.ortomosaicos.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("Este lote todavía no tiene ortomosaicos", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Para crear el primero, preprocesa un vuelo de este lote en Procesamiento y pulsa «Crear ortomosaico». " +
                        "Cada vuelo nuevo se suma a este historial.",
                    fontSize = 12.sp, color = TextMuted
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = alIrACrear, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Ir a Crear") }
            }
        } else {
            lote.ortomosaicos.forEachIndexed { i, o -> Tarjeta(o, lote.ortomosaicos.getOrNull(i + 1), alVer, alEliminar) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tarjeta(o: Entrada, anterior: Entrada?, alVer: (Entrada) -> Unit, alEliminar: (Entrada) -> Unit) = Card(Modifier.fillMaxWidth()) {
    val f = o.ficha
    val tamano by produceState<Long?>(null, o.dir) { value = withContext(Dispatchers.IO) { OrtomosaicoStore.tamano(o.dir) } }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Miniatura(o, Modifier.size(width = 170.dp, height = 96.dp).let { if (o.listo && o.tieneVista) it.clickable { alVer(o) } else it })
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(Formato.fecha(f.vueloInicioMs), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark)
                Insignia(
                    f.estado.etiqueta,
                    when (f.estado) {
                        EstadoOrtomosaico.LISTO -> Tono.OK
                        EstadoOrtomosaico.PROCESANDO -> Tono.NEUTRO
                        EstadoOrtomosaico.CANCELADO -> Tono.NEUTRO
                        else -> Tono.ERROR
                    }
                )
                anterior?.let {
                    val dias = Formato.diasEntre(it.ficha.vueloInicioMs, f.vueloInicioMs)
                    Text(if (dias == "mismo día") "el mismo día que el anterior" else "$dias después del anterior", fontSize = 11.sp, color = TextMuted)
                }
            }
            if (f.misionNombre.isNotBlank()) Text(f.misionNombre, fontSize = 12.sp, color = TextMuted)
            Spacer(Modifier.height(6.dp))
            when (f.estado) {
                EstadoOrtomosaico.LISTO -> FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.areaHa?.let { Dato("Área", Formato.hectareas(it)) }
                    f.coberturaPct?.let { Dato("Cobertura del lote", Formato.porcentaje(it)) }
                    f.gsdCmPx?.let { Dato("Resolución", Formato.resolucion(it)) }
                    if (f.imagenesTotal > 0) Dato("Imágenes usadas", "${f.imagenesUsadas} de ${f.imagenesTotal}")
                    Dato("Tamaño", tamano?.let(Formato::bytes) ?: "…")
                }
                EstadoOrtomosaico.PROCESANDO -> {
                    Text("Se está armando. No se puede eliminar hasta que termine o lo canceles.", fontSize = 12.sp, color = TextDark)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)), color = Green, trackColor = Color(0xFFE5E7EB))
                }
                else -> Text(
                    (f.error ?: "No terminó.") + " Puedes volver a armarlo desde la pestaña Crear.",
                    fontSize = 12.sp, color = if (f.estado == EstadoOrtomosaico.CANCELADO) TextMuted else Rojo
                )
            }
            if (o.listo && !o.tieneVista) {
                Spacer(Modifier.height(6.dp))
                Insignia("Falta la vista previa: abre la carpeta para revisar los archivos", Tono.ALERTA)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (o.listo && o.tieneVista) {
                    TextButton(onClick = { alVer(o) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Filled.Visibility, null, Modifier.size(16.dp), tint = Green)
                        Spacer(Modifier.width(4.dp))
                        Text("Ver", color = Green, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { abrirCarpeta(o.dir) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(16.dp), tint = TextDark)
                    Spacer(Modifier.width(4.dp))
                    Text("Abrir carpeta", color = TextDark, fontSize = 12.sp)
                }
                if (f.estado != EstadoOrtomosaico.PROCESANDO) {
                    TextButton(onClick = { alEliminar(o) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Filled.DeleteOutline, null, Modifier.size(16.dp), tint = Rojo)
                        Spacer(Modifier.width(4.dp))
                        Text("Eliminar", color = Rojo, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Dato(etiqueta: String, valor: String) {
    Column {
        Text(valor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
        Text(etiqueta, fontSize = 10.sp, color = TextMuted)
    }
}

/** La vista reducida de un ortomosaico, o un cuadro gris con un ícono mientras carga o si no hay. */
@Composable
private fun Miniatura(o: Entrada?, modifier: Modifier) {
    val imagen by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, o?.dir, o?.ficha?.estado) {
        value = o?.let { e -> withContext(Dispatchers.IO) { cargarImagen(if (e.miniatura.isFile) e.miniatura else e.vista, maxLado = 480) } }
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE5E7EB)), contentAlignment = Alignment.Center) {
        val img = imagen
        if (img != null) Image(img, "Vista previa del ortomosaico", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Layers, null, Modifier.size(22.dp), tint = Color(0xFF9CA3AF))
    }
}

@Composable
private fun DialogoEliminar(o: Entrada, alConfirmar: () -> Unit, alCancelar: () -> Unit) {
    val tamano by produceState<Long?>(null, o.dir) { value = withContext(Dispatchers.IO) { OrtomosaicoStore.tamano(o.dir) } }
    AlertDialog(
        onDismissRequest = alCancelar,
        title = { Text("¿Eliminar el ortomosaico del ${Formato.fecha(o.ficha.vueloInicioMs)}?", fontSize = 17.sp) },
        text = {
            Column {
                Text("${o.ficha.loteNombre}${o.ficha.misionNombre.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Se borrarán sus archivos de esta PC${tamano?.let { " (${Formato.bytes(it)})" } ?: ""}: el mosaico, la vista previa y las teselas. " +
                        "Los videos y los registros del vuelo no se tocan. Esta acción no se puede deshacer.",
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = { Button(onClick = alConfirmar, colors = ButtonDefaults.buttonColors(containerColor = Rojo)) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = alCancelar) { Text("Cancelar", color = TextDark) } },
        containerColor = Color.White
    )
}

@Composable
private fun Volver(texto: String, alVolver: () -> Unit) {
    TextButton(onClick = alVolver, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp), tint = Green)
        Spacer(Modifier.width(4.dp))
        Text(texto, color = Green, fontSize = 13.sp)
    }
}

/** Un ortomosaico a pantalla completa, con el contorno de su lote encima. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerOrtomosaico(lote: LoteHistorial, o: Entrada, alVolver: () -> Unit, alEliminar: () -> Unit) {
    val f = o.ficha
    val capa by produceState<Result<Capa>?>(null, o.dir) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val limites = f.limites ?: error("Este ortomosaico no trae los límites del terreno, así que no se puede ubicar.")
                val imagen = cargarImagen(o.vista) ?: error("No se pudo abrir la vista previa (${o.vista.name}): falta o está dañada.")
                Capa(Formato.fecha(f.vueloInicioMs), imagen, limites, Piramide.leer(o.dir))
            }
        }
    }
    val contorno by produceState(emptyList<Pair<Double, Double>>(), lote.uuid) {
        value = withContext(Dispatchers.IO) { OrtomosaicoDb.contorno(lote.uuid).getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Volver("Historial", alVolver)
            Text("${lote.nombre} · ${Formato.fecha(f.vueloInicioMs)}", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { abrirCarpeta(o.dir) }) { Text("Abrir carpeta", color = TextDark, fontSize = 12.sp) }
            TextButton(onClick = alEliminar) { Text("Eliminar", color = Rojo, fontSize = 12.sp) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val c = capa
            when {
                c == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Green, strokeWidth = 3.dp)
                c.isFailure -> EstadoVacio(Icons.Filled.BrokenImage, "No se puede mostrar este ortomosaico", c.exceptionOrNull()?.message.orEmpty(), "Abrir carpeta") { abrirCarpeta(o.dir) }
                else -> VisorOrtomosaico(c.getOrThrow(), contorno = contorno, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            f.areaHa?.let { Dato("Área", Formato.hectareas(it)) }
            f.coberturaPct?.let { Dato("Cobertura del lote", Formato.porcentaje(it)) }
            f.gsdCmPx?.let { Dato("Resolución", Formato.resolucion(it)) }
            if (f.imagenesTotal > 0) Dato("Imágenes usadas", "${f.imagenesUsadas} de ${f.imagenesTotal}")
            f.errorEstimadoM?.let { Dato("Ubicación", "±${"%.0f".format(it)} m") }
        }
    }
}

/** Dos fechas del mismo lote una sobre otra (cortina o transparencia), con avisos de lo que conviene saber antes de fiarse. */
@Composable
private fun CompararOrtomosaicos(lote: LoteHistorial, a: Entrada, b: Entrada, alCambiar: (Entrada, Entrada) -> Unit, alVolver: () -> Unit) {
    val (antes, despues) = Comparacion.ordenar(a, b)
    val distintas = a.dir != b.dir
    val capas by produceState<Result<Pair<Capa, Capa>>?>(null, antes.dir, despues.dir) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                fun capa(e: Entrada): Capa {
                    val limites = e.ficha.limites ?: error("El ortomosaico del ${Formato.fecha(e.ficha.vueloInicioMs)} no trae los límites del terreno.")
                    val imagen = cargarImagen(e.vista) ?: error("No se pudo abrir la vista previa del ortomosaico del ${Formato.fecha(e.ficha.vueloInicioMs)}.")
                    return Capa(Formato.fecha(e.ficha.vueloInicioMs), imagen, limites, Piramide.leer(e.dir))
                }
                capa(antes) to capa(despues)
            }
        }
    }
    val contorno by produceState(emptyList<Pair<Double, Double>>(), lote.uuid) {
        value = withContext(Dispatchers.IO) { OrtomosaicoDb.contorno(lote.uuid).getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Volver("Historial", alVolver)
            Text("Comparar fechas de ${lote.nombre}", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectorDeFecha("Primera fecha", a, lote.listos) { alCambiar(it, b) }
            SelectorDeFecha("Segunda fecha", b, lote.listos) { alCambiar(a, it) }
            if (distintas) {
                Text(
                    "${Formato.fecha(antes.ficha.vueloInicioMs)} → ${Formato.fecha(despues.ficha.vueloInicioMs)} · ${Formato.diasEntre(antes.ficha.vueloInicioMs, despues.ficha.vueloInicioMs)}",
                    fontSize = 12.sp, color = TextMuted
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (!distintas) {
            Aviso("Elige dos fechas distintas para compararlas.", Tono.ALERTA)
        } else {
            Comparacion.avisos(antes.ficha, despues.ficha).forEach { Aviso(it, Tono.ALERTA) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            val c = capas
            when {
                !distintas -> {}
                c == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Green, strokeWidth = 3.dp)
                c.isFailure -> EstadoVacio(Icons.Filled.BrokenImage, "No se pueden comparar", c.exceptionOrNull()?.message.orEmpty())
                else -> VisorOrtomosaico(c.getOrThrow().first, c.getOrThrow().second, contorno, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SelectorDeFecha(etiqueta: String, elegida: Entrada, opciones: List<Entrada>, alElegir: (Entrada) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { abierto = true }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(etiqueta, fontSize = 9.sp, color = TextMuted)
                Text(Formato.fecha(elegida.ficha.vueloInicioMs), fontSize = 12.sp, color = TextDark)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp), tint = TextDark)
        }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }, containerColor = Color.White) {
            opciones.forEach { o ->
                DropdownMenuItem(
                    text = { Text(Formato.fecha(o.ficha.vueloInicioMs) + (o.ficha.misionNombre.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), fontSize = 13.sp) },
                    onClick = { abierto = false; alElegir(o) }
                )
            }
        }
    }
}

/** Abre la carpeta en el Explorador; si no se puede (no existe, no hay escritorio) no hace nada: es solo una comodidad. */
internal fun abrirCarpeta(dir: File) {
    runCatching { if (dir.isDirectory) Desktop.getDesktop().open(dir) }
}
