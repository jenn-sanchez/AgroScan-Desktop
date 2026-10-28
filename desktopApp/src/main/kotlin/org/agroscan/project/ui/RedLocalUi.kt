package org.agroscan.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agroscan.project.features.redlocal.LocalHotspot
import org.agroscan.project.features.telemetry.TelemetryFirewall

private val DangerText = Color(0xFFDC2626)

@Composable
fun RedLocalUi() {
    Column(Modifier.fillMaxSize()) {
        RedLocalTopBar()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SyncColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth())
        }
    }
}

@Composable
private fun RedLocalTopBar() {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.SettingsInputAntenna, null, Modifier.size(18.dp), tint = Green)
        Spacer(Modifier.width(6.dp))
        Text("Sincronización Local", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Green, maxLines = 1)
        PhoneStatusPill()
    }
    HorizontalDivider(color = Border)
}

@Composable
private fun SyncColumn(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HotspotCard()
        ActionRow(Icons.Filled.Upload, "Enviar Polígono a PC", "Exportar área de trabajo actual")
        ActionRow(Icons.Filled.Upload, "Enviar Polígono a PC", "Exportar área de trabajo actual")
        ActionRow(Icons.Filled.Download, "Recibir Ruta de Vuelo", "Sincronizar misión planificada", highlighted = true)
    }
}

@Composable
private fun HotspotCard() {
    var on by remember { mutableStateOf(LocalHotspot.isOn) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(StatBgLight).border(1.dp, Border, CircleShape),
                Alignment.Center
            ) { Icon(Icons.Filled.CellTower, null, tint = Green) }
            Spacer(Modifier.height(10.dp))
            Text("Red Local AgroScan", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextDark)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) SelectedBg else SidebarBg)
                    .border(1.dp, Border, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) Accent else TextMuted))
                Text(if (on) "  Red encendida" else "  Red apagada", fontSize = 11.sp, color = TextDark)
            }
            Spacer(Modifier.height(10.dp))
            if (on) {
                Text("Conecta el teléfono a este Wi-Fi (no necesita internet):", fontSize = 10.sp, color = TextMuted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Credential("Red", LocalHotspot.SSID)
                Credential("Contraseña", LocalHotspot.password)
                Credential("IP de la PC", LocalHotspot.PC_IP)
            } else {
                Text(
                    "Crea una red Wi-Fi desde esta computadora para conectar el teléfono sin internet.",
                    fontSize = 10.sp, color = TextMuted, textAlign = TextAlign.Center
                )
            }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 10.sp, color = DangerText, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        var firewall: Result<Unit>? = null
                        val result = withContext(Dispatchers.IO) {
                            // Al encender, la regla del Firewall para la telemetría (Windows pregunta solo si falta)
                            if (on) LocalHotspot.stop() else LocalHotspot.start().onSuccess { firewall = TelemetryFirewall.ensure() }
                        }
                        result.onSuccess { on = LocalHotspot.isOn }.onFailure { error = "No se pudo cambiar la red: ${it.message}" }
                        firewall?.onFailure { error = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (on) DangerText else Green)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(if (on) Icons.Filled.WifiOff else Icons.Filled.Wifi, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (on) "Apagar red local" else "Encender red local")
            }
        }
    }
}

private val StatBgLight = Color(0xFFEEF2FF)

@Composable
private fun Credential(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", fontSize = 11.sp, color = TextMuted)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Green)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String, highlighted: Boolean = false) {
    val fg = if (highlighted) Color.White else TextDark
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) Accent else Color.White)
            .border(1.dp, if (highlighted) Accent else Border, RoundedCornerShape(8.dp))
            .clickable { }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(if (highlighted) Color.White.copy(alpha = 0.25f) else SelectedBg),
            Alignment.Center
        ) { Icon(icon, null, Modifier.size(16.dp), tint = if (highlighted) Color.White else Green) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg)
            Text(subtitle, fontSize = 9.sp, color = if (highlighted) Color.White.copy(alpha = 0.85f) else TextMuted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = fg)
    }
}
