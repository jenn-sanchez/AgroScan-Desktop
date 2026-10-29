package org.agroscan.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agroscan.project.core.DataFolder
import org.agroscan.project.features.flightlog.FlightLogServer
import org.agroscan.project.features.flightlog.FlightLogStore
import org.agroscan.project.features.flightlog.VideoImport
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Una salida tal como se ve en la lista: lo que ya llegó del teléfono y de la microSD. */
private data class SalidaVista(val numero: String, val marcas: Int, val videos: Int)
private data class VueloVista(val carpeta: String, val salidas: List<SalidaVista>)

/**
 * Lo que necesita el procesamiento de cada vuelo: dónde se guarda, importar los videos de la microSD y qué salidas ya tienen su
 * registro (llega solo del teléfono por la Red Local) y su video.
 */
@Composable
fun ProcesamientoUi() {
    val carpeta by DataFolder.current.collectAsState()
    val recibido by FlightLogServer.received.collectAsState()
    var refresco by remember { mutableStateOf(0) }
    // La lista se vuelve a leer cuando termina (o empieza) una importación: la copia sigue aunque se cambie de pestaña
    val importando = VideoImport.estado.collectAsState().value.activo
    val vuelos by produceState(emptyList<VueloVista>(), carpeta, recibido, refresco, importando) {
        value = withContext(Dispatchers.IO) { leerVuelos() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Procesamiento", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            PhoneStatusPill()
            Spacer(Modifier.width(12.dp))
            UserAvatar()
        }
        HorizontalDivider(color = Border)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CarpetaCard(carpeta)
            ImportarCard()
            VuelosCard(vuelos) { refresco++ }
        }
    }
}

@Composable
private fun Titulo(icon: ImageVector, texto: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = Green)
        Spacer(Modifier.width(8.dp))
        Text(texto, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
    }
}

@Composable
private fun CarpetaCard(carpeta: File) = Card(Modifier.fillMaxWidth()) {
    Titulo(Icons.Filled.Folder, "Carpeta de datos")
    Spacer(Modifier.height(4.dp))
    Text("Aquí se guardan los registros del teléfono y los videos de cada vuelo.", fontSize = 11.sp, color = TextMuted)
    Spacer(Modifier.height(10.dp))
    Text(carpeta.absolutePath, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextDark)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { elegirCarpeta("Carpeta de datos de AgroScan", carpeta)?.let(DataFolder::set) }) {
            Text("Cambiar…", color = TextDark)
        }
        OutlinedButton(onClick = { carpeta.mkdirs(); runCatching { Desktop.getDesktop().open(carpeta) } }) {
            Text("Abrir carpeta", color = TextDark)
        }
        if (carpeta != DataFolder.default) {
            TextButton(onClick = { DataFolder.set(DataFolder.default) }) { Text("Usar Documentos", color = Green) }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text("Cambiarla no mueve lo que ya está guardado en la carpeta anterior.", fontSize = 10.sp, color = TextMuted)
}

@Composable
private fun ImportarCard() = Card(Modifier.fillMaxWidth()) {
    // El estado vive en VideoImport (no aquí): cambiar de pestaña no interrumpe la copia y al volver se ve cómo va
    val estado by VideoImport.estado.collectAsState()

    Titulo(Icons.Filled.SdCard, "Importar videos de la microSD")
    Spacer(Modifier.height(4.dp))
    Text(
        "Pon la tarjeta del dron en el lector: se detecta sola (si no, te pide elegirla). Cada video se copia solo a la salida en que " +
            "se grabó; los que no son de una misión se ignoran. Primero tiene que haber llegado el registro de esa salida desde el teléfono. " +
            "La copia es idéntica al original, sin perder calidad, y sigue aunque cambies de pestaña.",
        fontSize = 11.sp, color = TextMuted
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val origen = VideoImport.detectarTarjeta() ?: elegirCarpeta("Tarjeta microSD del dron", null, mostrarArchivos = true) ?: return@Button
                VideoImport.iniciar(origen, FlightLogStore(DataFolder.vuelos))
            },
            enabled = !estado.activo,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) {
            if (estado.activo) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Importar desde la microSD")
        }
        if (estado.activo) TextButton(onClick = VideoImport::cancelar) { Text("Cancelar", color = Color(0xFFDC2626)) }
    }
    estado.progreso?.let { p ->
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { p.fraccion }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = Green, trackColor = Color(0xFFE5E7EB), drawStopIndicator = {}
        )
        Spacer(Modifier.height(6.dp))
        Text(VideoImport.texto(p), fontSize = 11.sp, color = TextDark)
    }
    if (estado.buscando) {
        Spacer(Modifier.height(8.dp))
        Text("Buscando videos en la tarjeta…", fontSize = 11.sp, color = TextMuted)
    }
    estado.mensaje?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontSize = 11.sp, color = TextDark)
    }
}

@Composable
private fun VuelosCard(vuelos: List<VueloVista>, onRefrescar: () -> Unit) = Card(Modifier.fillMaxWidth()) {
    val error by FlightLogServer.error.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Titulo(Icons.Filled.FlightTakeoff, "Vuelos recibidos") }
        TextButton(onClick = onRefrescar) { Text("Actualizar", color = Green) }
    }
    Text(
        "El registro y las marcas del video llegan solos del teléfono al terminar cada salida (con la Red Local encendida).",
        fontSize = 11.sp, color = TextMuted
    )
    error?.let { Text(it, fontSize = 11.sp, color = Color(0xFFDC2626)) }
    Spacer(Modifier.height(8.dp))
    if (vuelos.isEmpty()) Text("Todavía no llegó ningún registro.", fontSize = 12.sp, color = TextMuted)
    vuelos.forEach { v ->
        HorizontalDivider(color = Border)
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(v.carpeta, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
            v.salidas.forEach { s ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.numero, Modifier.width(110.dp), fontSize = 11.sp, color = TextDark)
                    Chip(true, "Registro · ${s.marcas} marcas")
                    Spacer(Modifier.width(6.dp))
                    Chip(s.videos > 0, if (s.videos > 0) "Video · ${s.videos} archivo(s)" else "Falta el video")
                }
            }
        }
    }
}

@Composable
private fun Chip(ok: Boolean, texto: String) {
    Text(
        texto,
        Modifier.clip(RoundedCornerShape(50)).background(if (ok) SelectedBg else Color(0xFFFEF3C7)).padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 10.sp, color = if (ok) Green else Color(0xFF92400E)
    )
}

private fun leerVuelos(): List<VueloVista> =
    FlightLogStore(DataFolder.vuelos).salidas()
        .groupBy { it.dir.parentFile }
        .entries.sortedByDescending { it.key.name }
        .map { (vuelo, salidas) ->
            VueloVista(vuelo.name, salidas.sortedBy { it.info.indice }.map { s ->
                SalidaVista(
                    s.dir.name.replaceFirstChar { it.uppercase() }.replace('-', ' '),
                    FlightLogStore.marcas(s.eventos),
                    s.videoDir.listFiles { f -> f.extension.equals("mp4", ignoreCase = true) }?.size ?: 0
                )
            })
        }

/**
 * Diálogo de Windows para elegir una carpeta; null si se cancela. Con [mostrarArchivos] también lista los archivos (para ver que es
 * la carpeta de los videos); si se elige un archivo se toma su carpeta.
 */
private fun elegirCarpeta(titulo: String, inicial: File?, mostrarArchivos: Boolean = false): File? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }   // aspecto de Windows, no el de Java
    val chooser = JFileChooser(inicial).apply {
        dialogTitle = titulo
        fileSelectionMode = if (mostrarArchivos) JFileChooser.FILES_AND_DIRECTORIES else JFileChooser.DIRECTORIES_ONLY
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile.let { if (it.isFile) it.parentFile else it }
}
