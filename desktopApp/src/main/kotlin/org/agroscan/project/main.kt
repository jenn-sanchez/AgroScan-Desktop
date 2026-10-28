package org.agroscan.project


import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import org.agroscan.project.features.telemetry.TelemetryReceiver
import org.agroscan.project.ui.Home
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import javax.imageio.ImageIO

fun main() = application {
    // Escucha la telemetría que manda el teléfono (UDP) desde que abre la app, esté o no abierta la pantalla de Telemetría
    LaunchedEffect(Unit) { TelemetryReceiver.start() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "AgroScan",
        state = rememberWindowState(width = 1280.dp, height = 800.dp)
    ) {
        window.minimumSize = Dimension(800,600)
        // Varios tamaños pre-escalados: Windows elige el más cercano en vez de reducir uno grande con mala calidad.
        LaunchedEffect(Unit) {
            window.iconImages = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)
                .mapNotNull { s -> object {}.javaClass.getResourceAsStream("/icons/$s.png")?.use(ImageIO::read) }
        }
        MaterialTheme {
            Home()
        }

    }
}