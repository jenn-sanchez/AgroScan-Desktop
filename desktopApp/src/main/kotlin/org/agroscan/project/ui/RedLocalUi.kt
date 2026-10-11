package org.agroscan.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agroscan.project.features.redlocal.LocalHotspot

private val DangerText = Color(0xFFDC2626)
private val DangerBg = Color(0xFFFEE2E2)

@Composable
fun RedLocalUi(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        RedLocalTopBar(onBack)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > 820.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Pantallas angostas: las dos columnas se apilan.
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    SyncColumn(Modifier.weight(1f))
                    NtripCard(Modifier.weight(1.2f))
                } else {
                    SyncColumn(Modifier.fillMaxWidth())
                    NtripCard(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun RedLocalTopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.SettingsInputAntenna, null, Modifier.size(18.dp), tint = Green)
        Spacer(Modifier.width(6.dp))
        Text("Sincronización Local", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Green, maxLines = 1)
        OutlinedButton(onClick = onBack, shape = RoundedCornerShape(50)) {
            Icon(Icons.Filled.Home, null, Modifier.size(16.dp), tint = TextDark)
            Spacer(Modifier.width(6.dp))
            Text("Volver a inicio", color = TextDark, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(DangerBg).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(DangerText))
            Text("  ESTADO: ESTACIÓN DESCONECTADA", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DangerText, maxLines = 1)
        }
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
                        val result = withContext(Dispatchers.IO) { if (on) LocalHotspot.stop() else LocalHotspot.start() }
                        result.onSuccess { on = LocalHotspot.isOn }.onFailure { error = "No se pudo cambiar la red: ${it.message}" }
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

@Composable
private fun NtripCard(modifier: Modifier) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var mount by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SettingsInputAntenna, null, Modifier.size(20.dp), tint = TextDark)
            Spacer(Modifier.width(8.dp))
            Text("Configuración NTRIP (Manual)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
        }
        Spacer(Modifier.height(16.dp))
        BoxWithConstraints {
            val wide = maxWidth > 520.dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field("Host / Dirección IP", host, { host = it }, Modifier.weight(2f))
                        Field("Puerto", port, { v -> port = v.filter(Char::isDigit).take(5) }, Modifier.weight(1f), numeric = true)
                        Field("Punto de Montaje (Mountpoint)", mount, { mount = it }, Modifier.weight(2f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field("Usuario", user, { user = it }, Modifier.weight(1f))
                        Field("Contraseña", pass, { pass = it }, Modifier.weight(1f), secret = true)
                    }
                } else {
                    Field("Host / Dirección IP", host, { host = it }, Modifier.fillMaxWidth())
                    Field("Puerto", port, { v -> port = v.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), numeric = true)
                    Field("Punto de Montaje (Mountpoint)", mount, { mount = it }, Modifier.fillMaxWidth())
                    Field("Usuario", user, { user = it }, Modifier.fillMaxWidth())
                    Field("Contraseña", pass, { pass = it }, Modifier.fillMaxWidth(), secret = true)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) {
            Icon(Icons.Filled.Link, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("CONECTAR ESTACIÓN", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier,
    numeric: Boolean = false, secret: Boolean = false
) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = TextDark, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color(0xFFF5F5F5), focusedContainerColor = Color.White)
        )
    }
}
