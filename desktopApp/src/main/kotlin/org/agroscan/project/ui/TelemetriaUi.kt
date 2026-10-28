package org.agroscan.project.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.agroscan.project.features.login.Auth
import org.agroscan.project.features.redlocal.LocalHotspot
import org.agroscan.project.features.telemetry.LiveTelemetry
import org.agroscan.project.features.telemetry.TelemetryData
import org.agroscan.project.features.telemetry.TelemetryReceiver
import org.agroscan.project.features.telemetry.enMision
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private val Danger = Color(0xFFC0181F)
private val Purple = Color(0xFF6366F1)
private val HomeBlue = Color(0xFF2563EB)
private val Amber = Color(0xFFB45309)
private val StatBg = Color(0xFFEEF2FF)

/**
 * Lo último que mandó el teléfono y la hora actual (se actualiza cada segundo para notar que dejó de llegar).
 * [fresh] es el dato solo si es reciente; [latest] es el último que llegó aunque ya sea viejo.
 */
internal class LiveView(val latest: LiveTelemetry?, val now: Long) {
    val fresh: LiveTelemetry? get() = latest?.takeIf { now - it.recibidoMs <= TelemetryReceiver.STALE_MS }
    val ageS: Long? get() = latest?.let { ((now - it.recibidoMs) / 1000).coerceAtLeast(0) }
}

@Composable
internal fun rememberLiveView(): LiveView {
    val latest by TelemetryReceiver.live.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return LiveView(latest, now)
}

@Composable
fun TelemetriaUi() {
    val view = rememberLiveView()
    Column(Modifier.fillMaxSize()) {
        TelemetriaTopBar(view)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Pantallas angostas: el panel de datos baja debajo del mapa.
            if (maxWidth > 760.dp) {
                Row(Modifier.fillMaxSize()) {
                    MapView(Modifier.weight(1f).fillMaxHeight().padding(20.dp), view)
                    LivePanel(Modifier.width(300.dp).fillMaxHeight(), pinAbort = true, view)
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MapView(Modifier.fillMaxWidth().height(420.dp).padding(20.dp), view)
                    LivePanel(Modifier.fillMaxWidth(), pinAbort = false, view)
                }
            }
        }
    }
}

@Composable
private fun TelemetriaTopBar(view: LiveView) {
    val enBase by produceState<String?>(null) {
        val id = Auth.usuario.value?.id ?: return@produceState
        value = withContext(Dispatchers.IO) { TelemetryData.misionEnCurso(id) }.getOrNull()
    }
    // La misión que manda el teléfono en vivo manda sobre la de la base (el historial del teléfono aún no se sincroniza con la base)
    val planVivo = if (view.fresh?.enMision == true) TelemetryReceiver.currentPlan(view.now)?.takeIf { it.mision.isNotBlank() } else null
    val enCurso = planVivo?.mision ?: enBase
    val etiqueta = planVivo?.takeIf { it.salida > 0 }?.let {
        "Salida ${it.salida} de ${it.salidas} · ${"%.0f".format(Locale.US, it.alturaM)} m · ${if (it.modo == "foto") "Fotos" else "Video"}"
    } ?: "Vuelo Automático"
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (enCurso != null) Green else TextMuted))
        Spacer(Modifier.width(8.dp))
        Text(
            enCurso?.let { "Misión en Curso: $it" } ?: "Sin misión en curso", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (enCurso != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                etiqueta, Modifier.clip(RoundedCornerShape(4.dp)).background(SelectedBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                fontSize = 10.sp, color = Green, maxLines = 1
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(50)).border(1.dp, Border, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(Icons.Filled.Wifi, Icons.Filled.BatteryChargingFull, Icons.Filled.LocationOn).forEach {
                Icon(it, null, Modifier.size(12.dp), tint = TextDark)
                Spacer(Modifier.width(4.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        PhoneStatusPill()
        Spacer(Modifier.width(12.dp))
        UserAvatar()
    }
    HorizontalDivider(color = Border)
}

private fun formatClock(totalS: Long) = "%02d:%02d".format(totalS / 60, totalS % 60)

/**
 * Mapa en vivo sin imágenes de fondo (funciona sin internet): la ruta de la salida, por dónde ha pasado el dron y su posición.
 * shortcut: no hay mapa satelital porque la app de escritorio aún no tiene fuente de imágenes; añadirla debajo de este dibujo.
 */
@Composable
private fun MapView(modifier: Modifier, view: LiveView) {
    val plan by TelemetryReceiver.plan.collectAsState()
    val lote by TelemetryReceiver.lote.collectAsState()
    val trail by TelemetryReceiver.trail.collectAsState()
    val last = view.latest
    val drone = if (last?.lat != null && last.lon != null) last.lat to last.lon else null
    val route = plan?.ruta.orEmpty()
    val lot = lote?.perimetro.orEmpty()
    val home = plan?.home
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFE8E6FA), Color(0xFFD9E3F5))))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
    ) {
        if (drone == null && route.isEmpty() && lot.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Image, null, Modifier.size(64.dp), tint = Purple)
                Text("Sin posición del dron", fontSize = 11.sp, color = TextMuted)
            }
        } else {
            LiveMap(lot, route, trail, drone, home, last?.rumbo, signal = view.fresh != null, Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.BottomStart).padding(14.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("■ Lote", fontSize = 10.sp, color = Green)
                Text("┄ Ruta", fontSize = 10.sp, color = TextMuted)
                Text("━ Recorrido", fontSize = 10.sp, color = Accent)
                Text("● Origen", fontSize = 10.sp, color = HomeBlue)
            }
        }

        Column(
            Modifier.padding(14.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF4B5563))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("TIEMPO DE VUELO", fontSize = 9.sp, letterSpacing = 1.sp, color = Color(0xFFD1D5DB))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timer, null, Modifier.size(14.dp), tint = Accent)
                Spacer(Modifier.width(6.dp))
                Text(view.fresh?.takeIf { it.dron }?.let { formatClock(it.tVueloS) } ?: NA, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }

        if (drone != null || route.isNotEmpty() || lot.isNotEmpty()) {
            Text(
                "N ↑", Modifier.align(Alignment.TopEnd).padding(14.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMuted
            )
        }

        Column(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MapButton(Icons.Filled.MyLocation, "Centrar")
            Spacer(Modifier.height(8.dp))
            MapButton(Icons.Filled.Layers, "Capas")
            Spacer(Modifier.height(12.dp))
            Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).border(1.dp, Border, RoundedCornerShape(6.dp))) {
                IconButton(onClick = {}, Modifier.size(30.dp)) { Icon(Icons.Filled.Add, "Acercar", Modifier.size(16.dp)) }
                HorizontalDivider(Modifier.width(30.dp), color = Border)
                IconButton(onClick = {}, Modifier.size(30.dp)) { Icon(Icons.Filled.Remove, "Alejar", Modifier.size(16.dp)) }
            }
        }
    }
}

/** Dibuja lote, ruta, estela, origen y dron con el norte arriba. Proyección plana local (grados con la longitud escalada por cos(latitud)). */
@Composable
private fun LiveMap(
    lot: List<Pair<Double, Double>>,
    route: List<Pair<Double, Double>>,
    trail: List<Pair<Double, Double>>,
    drone: Pair<Double, Double>?,
    home: Pair<Double, Double>?,
    rumbo: Double?,
    signal: Boolean,
    modifier: Modifier
) {
    Canvas(modifier) {
        val all = lot + route + trail + listOfNotNull(drone, home)
        if (all.isEmpty()) return@Canvas
        val cosLat = cos(Math.toRadians(all.map { it.first }.average()))
        fun px(p: Pair<Double, Double>) = p.second * cosLat      // este (crece a la derecha)
        fun py(p: Pair<Double, Double>) = -p.first               // norte arriba: la pantalla crece hacia el sur
        val minX = all.minOf(::px)
        val minY = all.minOf(::py)
        // Mínimo ~33 m de lado para que un solo punto no se agrande sin fin
        val spanX = max(all.maxOf(::px) - minX, 0.0003)
        val spanY = max(all.maxOf(::py) - minY, 0.0003)
        val pad = 40f
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
        val offX = (size.width - spanX.toFloat() * scale) / 2
        val offY = (size.height - spanY.toFloat() * scale) / 2
        fun at(p: Pair<Double, Double>) = Offset(((px(p) - minX).toFloat() * scale) + offX, ((py(p) - minY).toFloat() * scale) + offY)

        fun line(points: List<Pair<Double, Double>>, color: Color, width: Float, dashed: Boolean = false) {
            if (points.size < 2) return
            val path = Path().apply {
                moveTo(at(points[0]).x, at(points[0]).y)
                points.drop(1).forEach { lineTo(at(it).x, at(it).y) }
            }
            drawPath(path, color, style = Stroke(width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
        }

        if (lot.size >= 3) {
            val outline = Path().apply {
                moveTo(at(lot[0]).x, at(lot[0]).y)
                lot.drop(1).forEach { lineTo(at(it).x, at(it).y) }
                close()
            }
            drawPath(outline, Color(0x2210B981))
            drawPath(outline, Green, style = Stroke(2.5f))
        }
        line(route, Color(0xFF6B7280), 2f, dashed = true)
        route.forEach { drawCircle(Color(0xFF6B7280), 4f, at(it)) }
        line(trail, Accent, 4f)
        home?.let {
            drawCircle(Color.White, 10f, at(it))
            drawCircle(HomeBlue, 7f, at(it))
        }

        drone?.let { d ->
            val c = at(d)
            val color = if (signal) Green else Danger     // en rojo si dejó de llegar la señal: es la última posición conocida
            drawCircle(Color.White, 11f, c)
            drawCircle(color, 8f, c)
            rumbo?.let {
                rotate(it.toFloat(), pivot = c) {
                    drawPath(Path().apply {
                        moveTo(c.x, c.y - 24f); lineTo(c.x - 8f, c.y - 10f); lineTo(c.x + 8f, c.y - 10f); close()
                    }, color)
                }
            }
        }
    }
}

@Composable
private fun MapButton(icon: ImageVector, desc: String) {
    IconButton(
        onClick = {},
        Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).border(1.dp, Border, RoundedCornerShape(6.dp))
    ) { Icon(icon, desc, Modifier.size(16.dp), tint = TextDark) }
}

@Composable
private fun LivePanel(modifier: Modifier, pinAbort: Boolean, view: LiveView) {
    // Con el teléfono conectado pero sin dron, los valores del dron son por defecto (batería 0...): se muestran como N/A
    val live = view.fresh?.takeIf { it.dron }
    val error by TelemetryReceiver.error.collectAsState()
    val phoneOnNetwork by LocalHotspot.phoneConnected.collectAsState()
    Column(modifier.background(Color.White).border(1.dp, Border)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ShowChart, null, Modifier.size(18.dp), tint = Green)
                Spacer(Modifier.width(6.dp))
                Text("Datos en Vivo", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            }
            val (estado, color) = when {
                live != null -> "Transmisión de datos del UAV · en vivo" to Green
                view.fresh != null -> "Teléfono conectado · dron sin conectar" to TextMuted
                view.latest != null -> "Sin señal · último dato hace ${view.ageS} s" to Danger
                // Lo más común: el firewall de Windows bloquea el puerto en la red de la zona móvil
                phoneOnNetwork -> "Teléfono en la red pero no llegan datos: apaga y enciende la Red Local y acepta el permiso de Windows" to Amber
                else -> "Esperando datos del teléfono" to TextMuted
            }
            Text(estado, fontSize = 10.sp, color = color)
            error?.let { Text(it, fontSize = 10.sp, color = Danger) }
        }
        HorizontalDivider(color = Border)

        // Con pinAbort solo esta sección hace scroll y el botón queda fijo abajo.
        Column(
            (if (pinAbort) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(Modifier.weight(1f), Icons.Filled.Speed, "Velocidad", live?.let { "%.1f".format(Locale.US, it.velMs) } ?: NA, "m/s")
                Stat(Modifier.weight(1f), Icons.Filled.Height, "Altitud", live?.let { "%.1f".format(Locale.US, it.altM) } ?: NA, "m")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(
                    Modifier.weight(1f), Icons.Filled.BatteryFull, "Batería", live?.bat?.toString() ?: NA, "%",
                    progress = ((live?.bat ?: 0) / 100f).coerceIn(0f, 1f)
                )
                Stat(Modifier.weight(1f), Icons.Filled.Route, "Recorrido", live?.let { "%.2f".format(Locale.US, it.distM / 1000) } ?: NA, "km")
            }
            Spacer(Modifier.height(6.dp))
            PhotosCard(live)
            Spacer(Modifier.height(6.dp))
            Text("ESTADO DEL SISTEMA", fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            SystemRow(Icons.Filled.SatelliteAlt, "Señal GPS") {
                Text(
                    live?.let { "${it.sat} satélites" } ?: NA, fontSize = 10.sp,
                    color = if (live == null) TextMuted else if (live.sat >= 10) Green else Danger
                )
            }
            SystemRow(Icons.Filled.CameraAlt, "Cámara Multiespectral") {
                Text(NA, fontSize = 10.sp, color = TextMuted)
            }
            SystemRow(Icons.Filled.Air, "Viento en Altitud", divider = false) {
                Text(NA, fontSize = 10.sp, color = TextMuted)
            }
        }

        HorizontalDivider(color = Border)
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White)
            ) {
                Icon(Icons.Filled.Warning, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("ABORTAR (RTL)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("Retorno automático a base de carga", fontSize = 9.sp, color = TextMuted)
        }
    }
}

@Composable
private fun Stat(modifier: Modifier, icon: ImageVector, label: String, value: String, unit: String, progress: Float? = null) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(StatBg).border(1.dp, Border, RoundedCornerShape(8.dp))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(10.dp), tint = if (progress != null) Green else TextMuted)
                Text(" ${label.uppercase()}", fontSize = 8.sp, color = if (progress != null) Green else TextMuted, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TextDark)
                Text(" $unit", fontSize = 9.sp, color = TextMuted, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (progress != null) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Accent))
        }
    }
}

@Composable
private fun PhotosCard(live: LiveTelemetry?) = Card(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFFDBEAFE)), Alignment.Center) {
            Icon(Icons.Filled.PhotoCamera, null, Modifier.size(16.dp), tint = Color(0xFF2563EB))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Fotos Capturadas", fontSize = 10.sp, color = TextMuted)
            Text(live?.fotos?.toString() ?: NA, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TextDark)
        }
        Column(horizontalAlignment = Alignment.End) {
            // En una misión de video las fotos son 0: lo que importa es que esté grabando
            Text(if (live?.grabando == true) "Grabando video" else "Intervalo: $NA", fontSize = 9.sp, color = if (live?.grabando == true) Danger else TextMuted)
        }
    }
}

@Composable
private fun SystemRow(icon: ImageVector, label: String, divider: Boolean = true, trailing: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = TextDark)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), fontSize = 11.sp, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
    if (divider) HorizontalDivider(color = Border)
}
