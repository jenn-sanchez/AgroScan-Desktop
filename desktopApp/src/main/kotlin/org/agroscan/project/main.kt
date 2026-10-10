package org.agroscan.project


import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import org.agroscan.project.ui.Home
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "AgroScan",
        state = rememberWindowState(width = 1280.dp, height = 800.dp)
    ) {
        window.minimumSize = Dimension(800,600)
        MaterialTheme {
            Home()
        }

    }
}