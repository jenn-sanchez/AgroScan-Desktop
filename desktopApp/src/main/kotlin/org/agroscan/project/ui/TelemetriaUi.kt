package org.agroscan.project.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Danger = Color(0xFFC0181F)
private val Purple = Color(0xFF6366F1)
private val StatBg = Color(0xFFEEF2FF)

@Composable
fun TelemetriaUi() {
    Column(Modifier.fillMaxSize()) {
        TelemetriaTopBar()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Pantallas angostas: el panel de datos baja debajo del mapa.
            if (maxWidth > 760.dp) {
                Row(Modifier.fillMaxSize()) {
                    MapView(Modifier.weight(1f).fillMaxHeight().padding(20.dp))
                    LivePanel(Modifier.width(300.dp).fillMaxHeight(), pinAbort = true)
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MapView(Modifier.fillMaxWidth().height(420.dp).padding(20.dp))
                    LivePanel(Modifier.fillMaxWidth(), pinAbort = false)
                }
            }
        }
    }
}

@Composable
private fun TelemetriaTopBar() {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Green))
        Spacer(Modifier.width(8.dp))
        Text(
            "Misión en Curso: Lote Norte-4", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Vuelo Automático", Modifier.clip(RoundedCornerShape(4.dp)).background(SelectedBg)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp, color = Green, maxLines = 1
        )
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
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF374151)), Alignment.Center) {
            Icon(Icons.Filled.Person, null, tint = Color.White)
        }
    }
    HorizontalDivider(color = Border)
}

// shortcut: mapa simulado con puntos fijos; reemplazar por un mapa real cuando exista la fuente de tiles.
@Composable
private fun MapView(modifier: Modifier) {
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFE8E6FA), Color(0xFFD9E3F5))))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
    ) {
        // Puntos de ruta en posiciones relativas para que escalen con el tamaño.
        listOf(0.17f to 0.11f, 0.52f to 0.11f, 0.52f to 0.45f, 0.86f to 0.45f).forEach { (x, y) ->
            Box(Modifier.offset(maxWidth * x, maxHeight * y).size(8.dp).clip(CircleShape).background(Color.Black))
        }
        Box(
            Modifier.offset(maxWidth * 0.82f, maxHeight * 0.2f).size(30.dp).clip(CircleShape).background(Green),
            Alignment.Center
        ) { Icon(Icons.Filled.Flight, null, Modifier.size(18.dp), tint = Color.White) }

        Icon(Icons.Filled.Image, null, Modifier.align(Alignment.Center).size(64.dp), tint = Purple)

        Column(
            Modifier.padding(14.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF4B5563))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("TIEMPO DE VUELO", fontSize = 9.sp, letterSpacing = 1.sp, color = Color(0xFFD1D5DB))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timer, null, Modifier.size(14.dp), tint = Accent)
                Spacer(Modifier.width(6.dp))
                Text("14:22", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
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

@Composable
private fun MapButton(icon: ImageVector, desc: String) {
    IconButton(
        onClick = {},
        Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).border(1.dp, Border, RoundedCornerShape(6.dp))
    ) { Icon(icon, desc, Modifier.size(16.dp), tint = TextDark) }
}

@Composable
private fun LivePanel(modifier: Modifier, pinAbort: Boolean) {
    Column(modifier.background(Color.White).border(1.dp, Border)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ShowChart, null, Modifier.size(18.dp), tint = Green)
                Spacer(Modifier.width(6.dp))
                Text("Datos en Vivo", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            }
            Text("Transmisión de datos del UAV", fontSize = 10.sp, color = TextMuted)
        }
        HorizontalDivider(color = Border)

        // Con pinAbort solo esta sección hace scroll y el botón queda fijo abajo.
        Column(
            (if (pinAbort) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(Modifier.weight(1f), Icons.Filled.Speed, "Velocidad", "12.4", "m/s")
                Stat(Modifier.weight(1f), Icons.Filled.Height, "Altitud", "45.0", "m")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(Modifier.weight(1f), Icons.Filled.BatteryFull, "Batería", "78", "%", progress = 0.78f)
                Stat(Modifier.weight(1f), Icons.Filled.Route, "Recorrido", "1.2", "km")
            }
            Spacer(Modifier.height(6.dp))
            PhotosCard()
            Spacer(Modifier.height(6.dp))
            Text("ESTADO DEL SISTEMA", fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            SystemRow(Icons.Filled.SatelliteAlt, "Señal GPS") {
                Text(
                    "RTK FIX (24)", Modifier.clip(RoundedCornerShape(4.dp)).background(SelectedBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Green
                )
            }
            SystemRow(Icons.Filled.CameraAlt, "Cámara Multiespectral") {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Accent))
                Text(" Activa", fontSize = 10.sp, color = Green)
            }
            SystemRow(Icons.Filled.Air, "Viento en Altitud", divider = false) {
                Text("14 km/h NO", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextDark)
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
private fun PhotosCard() = Card(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFFDBEAFE)), Alignment.Center) {
            Icon(Icons.Filled.PhotoCamera, null, Modifier.size(16.dp), tint = Color(0xFF2563EB))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Fotos Capturadas", fontSize = 10.sp, color = TextMuted)
            Text("142", fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TextDark)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("↑ +3/s", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Green)
            Text("Intervalo: 2s", fontSize = 9.sp, color = TextMuted)
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
