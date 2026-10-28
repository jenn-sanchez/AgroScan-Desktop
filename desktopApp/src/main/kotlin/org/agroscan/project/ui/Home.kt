package org.agroscan.project.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.agroscan.project.features.login.Auth
import org.agroscan.project.core.Database
import org.agroscan.project.features.home.HomeData
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.agroscan.project.features.redlocal.LocalHotspot

internal val Green = Color(0xFF006C47)
internal val Accent = Color(0xFF10B981)
internal val SidebarBg = Color(0xFFEAF1FF)
internal val ContentBg = Color(0xFFF8F9FF)
internal val SelectedBg = Color(0xFFA7F3D0)
internal val Border = Color(0xFFD7DCE5)
internal val TextMuted = Color(0xFF4B5563)
internal val TextDark = Color(0xFF111827)

private data class NavItem(val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem("Inicio", Icons.Filled.Home),
    NavItem("Perímetro", Icons.Filled.Map),
    NavItem("Planificación", Icons.Filled.Architecture),
    NavItem("Telemetría", Icons.Filled.SettingsInputAntenna),
    NavItem("Procesamiento", Icons.Filled.Memory),
    NavItem("Resultados", Icons.Filled.Assessment),
    NavItem("Red Local", Icons.Filled.Lan),
)

/** Lo que se muestra cuando un dato no existe (base vacía, sin conexión o sin telemetría en vivo). */
internal const val NA = "N/A"

private val fechaFormato = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.forLanguageTag("es"))

private fun estadoTexto(estado: String?) = when (estado) {
    "BORRADOR" -> "Borrador"
    "SINCRONIZADO" -> "Lista para volar"
    "COMPLETADO" -> "Completada"
    null -> NA
    else -> estado
}

@Composable
fun Home() {
    val usuario by Auth.usuario.collectAsState()
    var selected by remember { mutableStateOf(0) }
    Row(Modifier.fillMaxSize().background(ContentBg)) {
        Sidebar(selected) { selected = it }
        // Sin sesión, cualquier opción muestra el login: los datos de cada pantalla son del usuario que entró.
        if (usuario == null) LoginUi()
        else key(usuario?.id) {
            when (navItems[selected].label) {
                "Telemetría" -> TelemetriaUi()
                "Red Local" -> RedLocalUi()
                else -> Column(Modifier.fillMaxSize()) {
                    TopBar()
                    Dashboard()
                }
            }
        }
    }
}

// Ancho fijo: la barra lateral nunca se contrae.
@Composable
private fun Sidebar(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier.width(220.dp).fillMaxHeight().background(SidebarBg)
            .border(1.dp, Border).padding(12.dp)
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource("icon.png"), "Logo AgroScan", Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("AgroScan", color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Enterprise Drone System", color = TextMuted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            navItems.forEachIndexed { i, item ->
                val isSel = i == selected
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) SelectedBg else Color.Transparent)
                        .clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(item.icon, null, Modifier.size(18.dp), tint = if (isSel) Green else TextDark)
                    Spacer(Modifier.width(10.dp))
                    Text(item.label, fontSize = 13.sp, color = if (isSel) Green else TextDark)
                }
            }
        }
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = TextDark)
        ) {
            Icon(Icons.Filled.FlightTakeoff, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Launch Mission", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Resumen Operativo", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        DbStatusPill()
        Spacer(Modifier.width(8.dp))
        PhoneStatusPill()
        listOf(Icons.Filled.Wifi, Icons.Filled.BatteryChargingFull, Icons.Filled.LocationOn).forEach {
            Spacer(Modifier.width(16.dp))
            Icon(it, null, Modifier.size(20.dp), tint = TextDark)
        }
        Spacer(Modifier.width(16.dp))
        UserAvatar()
    }
}

/** Estado del teléfono: conectado solo cuando se unió a la red local que crea esta PC. */
@Composable
internal fun PhoneStatusPill() {
    val connected by LocalHotspot.phoneConnected.collectAsState()
    val color = if (connected) Green else Color(0xFFDC2626)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (connected) SelectedBg else Color(0xFFFEE2E2))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(if (connected) "Teléfono conectado" else "Teléfono desconectado", fontSize = 11.sp, color = color, maxLines = 1)
    }
}

/** Estado de PostgreSQL: se prueba una vez al abrir la pantalla. */
@Composable
private fun DbStatusPill() {
    val status by produceState<Result<String>?>(null) {
        value = withContext(Dispatchers.IO) { Database.check() }
        value?.onFailure { System.err.println("Base de datos: ${it.message}") }
    }
    val ok = status?.isSuccess == true
    val color = when (status) { null -> TextMuted; else -> if (ok) Green else Color(0xFFDC2626) }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (ok) SelectedBg else Color(0xFFFEE2E2))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            when { status == null -> "BD: verificando…"; ok -> "BD ${if (Database.isLocal) "local" else "en línea"}: ${status?.getOrNull()}"; else -> "BD sin conexión" },
            fontSize = 11.sp, color = color, maxLines = 1
        )
    }
}

@Composable
private fun Dashboard() {
    val resumen by produceState<Result<HomeData.Resumen>?>(null) {
        val id = Auth.usuario.value?.id ?: return@produceState
        value = withContext(Dispatchers.IO) { HomeData.resumen(id) }
        value?.onFailure { System.err.println("Base de datos: ${it.message}") }
    }
    val r = resumen?.getOrNull()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Pantallas angostas: las tarjetas se apilan en columna.
        val wide = maxWidth > 720.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Adaptive(wide) {
                StatusCard(it, resumen)
                BatteryCard(it)
                StorageCard(it)
            }
            Adaptive(wide) {
                HectaresCard(it, r?.hectareas)
                FlightsCard(it, r)
            }
            MissionsTable(wide, resumen)
        }
    }
}

@Composable
private fun Adaptive(wide: Boolean, content: @Composable (Modifier) -> Unit) {
    if (wide) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        content(Modifier.weight(1f).fillMaxHeight())
    } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        content(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun Card(modifier: Modifier, background: Brush = Brush.linearGradient(listOf(Color.White, Color.White)), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(background)
            .border(1.dp, Border, RoundedCornerShape(10.dp)).padding(16.dp),
        content = content
    )
}

@Composable
private fun CardHeader(title: String, icon: ImageVector, tint: Color = TextDark) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), Modifier.weight(1f), fontSize = 11.sp, letterSpacing = 1.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
    }
}

@Composable
private fun Progress(value: Float) {
    LinearProgressIndicator(
        progress = { value },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
        color = Green, trackColor = Color(0xFFE5E7EB), drawStopIndicator = {}
    )
}

@Composable
private fun StatusCard(m: Modifier, resumen: Result<HomeData.Resumen>?) = Card(m) {
    val ok = resumen?.isSuccess == true
    CardHeader("Estado del Sistema", if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, if (ok) Green else Color(0xFFDC2626))
    Spacer(Modifier.height(8.dp))
    Text(
        when { resumen == null -> "Verificando…"; ok -> "Operativo"; else -> "Sin base de datos" },
        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextDark
    )
    Text(
        if (resumen?.isFailure == true) "No se pudo leer la base de datos." else "Base de datos disponible.",
        fontSize = 11.sp, color = TextMuted
    )
}

@Composable
private fun BatteryCard(m: Modifier) = Card(m) {
    CardHeader("Batería Flota Principal", Icons.Filled.BatteryFull)
    Spacer(Modifier.height(8.dp))
    // Batería del dron que está transmitiendo ahora; sin señal reciente del teléfono, N/A
    val live = rememberLiveView().fresh?.takeIf { it.dron }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(live?.let { "${it.bat}%" } ?: NA, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextDark)
        Spacer(Modifier.width(6.dp))
        Text(if (live != null) "En vivo" else "Sin telemetría", fontSize = 11.sp, color = TextMuted, modifier = Modifier.padding(bottom = 4.dp))
    }
    Spacer(Modifier.height(8.dp))
    Progress(((live?.bat ?: 0) / 100f).coerceIn(0f, 1f))
}

@Composable
private fun StorageCard(m: Modifier) = Card(m) {
    // Disco donde está la carpeta del usuario (ahí se guardarán videos y ortomosaicos).
    val disk = remember { File(System.getProperty("user.home")) }
    val totalGb = disk.totalSpace / 1e9
    val usedGb = totalGb - disk.usableSpace / 1e9
    CardHeader("Almacenamiento Local", Icons.Filled.Storage)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("%.0f".format(usedGb), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextDark)
        Text(" GB", fontSize = 16.sp, color = TextMuted, modifier = Modifier.weight(1f))
        Text("de %.0f GB".format(totalGb), fontSize = 11.sp, color = TextMuted, modifier = Modifier.padding(bottom = 4.dp))
    }
    Spacer(Modifier.height(8.dp))
    Progress(if (totalGb > 0) (usedGb / totalGb).toFloat() else 0f)
}

@Composable
private fun HectaresCard(m: Modifier, hectareas: Double?) = Card(
    m, Brush.linearGradient(listOf(Color(0xFFD1E7DD), Color(0xFFE8F3EE), Color(0xFFC9DFD4)))
) {
    Row(Modifier.fillMaxSize().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Hectáreas Escaneadas Totales", fontSize = 11.sp, color = TextMuted)
            Text(hectareas?.let { "%,.1f".format(Locale.US, it) } ?: NA, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = TextDark)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White).border(1.dp, Border, CircleShape), Alignment.Center) {
            Icon(Icons.Filled.Terrain, null, tint = Green)
        }
    }
}

@Composable
private fun FlightsCard(m: Modifier, r: HomeData.Resumen?) = Card(m) {
    // Variación contra el mes anterior; sin vuelos el mes anterior no hay base para comparar.
    val cambio = r?.takeIf { it.vuelosMesAnterior > 0 }?.let { (it.vuelosMes - it.vuelosMesAnterior) * 100 / it.vuelosMesAnterior }
    Row(Modifier.fillMaxSize().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Vuelos Completados (Mes)", fontSize = 11.sp, color = TextMuted)
            Text(r?.vuelosMes?.toString() ?: NA, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = TextDark)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFD1FAE5)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if ((cambio ?: 0) < 0) Icons.AutoMirrored.Filled.TrendingDown else Icons.AutoMirrored.Filled.TrendingUp,
                    null, Modifier.size(12.dp), tint = Green
                )
                Text(cambio?.let { " %+d%%".format(it) } ?: " $NA", fontSize = 10.sp, color = Green)
            }
            Text("vs mes anterior", fontSize = 10.sp, color = TextMuted)
        }
    }
}

@Composable
private fun MissionsTable(wide: Boolean, resumen: Result<HomeData.Resumen>?) {
    // En pantallas angostas se oculta la columna "Captura".
    val weights = if (wide) listOf(1f, 1.2f, 1f, 0.8f) else listOf(1f, 1.2f, 0.9f)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White)
            .border(1.dp, Border, RoundedCornerShape(10.dp))
    ) {
        Row(Modifier.fillMaxWidth().background(ContentBg).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Misiones Recientes", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            Row(Modifier.clickable { }, verticalAlignment = Alignment.CenterVertically) {
                Text("Ver todas", fontSize = 11.sp, color = Green)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(12.dp), tint = Green)
            }
        }
        HorizontalDivider(color = Border)
        val headers = listOf("Fecha", "Lote · Misión", "Captura", "Estado").let { if (wide) it else it - "Captura" }
        TableRow(weights) { i -> Text(headers[i].uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, color = TextMuted) }
        val misiones = resumen?.getOrNull()?.misiones.orEmpty()
        if (misiones.isEmpty()) {
            HorizontalDivider(color = Border)
            Text(
                when { resumen == null -> "Cargando…"; resumen.isFailure -> "No se pudo leer la base de datos"; else -> "Sin misiones registradas" },
                Modifier.fillMaxWidth().padding(16.dp), fontSize = 12.sp, color = TextMuted
            )
        }
        misiones.forEach { mision ->
            HorizontalDivider(color = Border)
            val captura = mision.modoCaptura?.lowercase()?.replaceFirstChar { it.uppercase() } ?: NA
            val cells = listOf(
                mision.fecha.atZoneSameInstant(ZoneId.systemDefault()).format(fechaFormato),
                "${mision.lote} · ${mision.mision}",
                captura
            ).let { if (wide) it else it.dropLast(1) }
            TableRow(weights) { i ->
                if (i < cells.size) Text(cells[i], fontSize = 12.sp, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else StatusChip(estadoTexto(mision.estado))
            }
        }
    }
}

@Composable
private fun TableRow(weights: List<Float>, cell: @Composable (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        weights.forEachIndexed { i, w -> Box(Modifier.weight(w).padding(end = 8.dp)) { cell(i) } }
    }
}

@Composable
private fun StatusChip(estado: String) {
    val (bg, fg) = when {
        estado == "Completada" -> Accent to TextDark
        estado == "Lista para volar" -> SelectedBg to Green
        else -> Color(0xFFE5E7F5) to TextMuted
    }
    Text(
        estado, Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 10.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}
