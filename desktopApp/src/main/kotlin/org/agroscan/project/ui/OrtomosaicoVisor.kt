package org.agroscan.project.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.agroscan.project.features.ortomosaico.Limites
import org.agroscan.project.features.ortomosaico.Proyeccion
import kotlin.math.roundToInt

/** Una imagen del mosaico con el terreno que cubre; [etiqueta] es lo que se lee sobre ella ("Antes · 12 sep 2026"). */
internal class Capa(val etiqueta: String, val imagen: ImageBitmap, val limites: Limites)

internal enum class ModoComparar(val etiqueta: String, val ayuda: String) {
    CORTINA("Cortina", "Arrastra la línea para ver una fecha a cada lado."),
    TRANSPARENCIA("Transparencia", "Mueve el control para mezclar las dos fechas.")
}

private class Encuadre {
    var escala by mutableStateOf(1f)      // píxeles de pantalla por metro de terreno
    var dx by mutableStateOf(0f)
    var dy by mutableStateOf(0f)
    var minEscala = 0.1f
    var maxEscala = 100f
}

private val FondoVisor = Color(0xFF1F2937)

/** Un texto flotante al pasar el cursor por un botón de solo ícono: así se sabe qué hace sin tener que probarlo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tip(texto: String, content: @Composable () -> Unit) = TooltipArea(
    tooltip = {
        Text(
            texto, Modifier.background(Color(0xFF111827), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White, fontSize = 11.sp
        )
    },
    delayMillis = 400,
    tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
    content = content
)

/**
 * Visor de ortomosaicos: la imagen de [antes] en su sitio del terreno con zoom (rueda o botones) y desplazamiento (arrastrar), el
 * [contorno] del lote encima y una barra de escala. Con [despues] compara las dos fechas por **cortina** o **transparencia**; cada imagen
 * se dibuja en su propio sitio, así que vale aunque no cubran exactamente la misma zona. Todo es nítido a cualquier zoom porque se dibuja
 * la imagen original, no una copia reducida.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun VisorOrtomosaico(
    antes: Capa,
    despues: Capa? = null,
    contorno: List<Pair<Double, Double>> = emptyList(),
    modifier: Modifier = Modifier
) {
    val marco = remember(antes, despues) { Proyeccion(if (despues != null) Proyeccion.union(antes.limites, despues.limites) else antes.limites) }
    val cam = remember(marco) { Encuadre() }
    var tam by remember { mutableStateOf(IntSize.Zero) }
    var ajustado by remember(marco) { mutableStateOf(false) }
    var modo by remember { mutableStateOf(ModoComparar.CORTINA) }
    var divisor by remember { mutableStateOf(0.5f) }
    var alfa by remember { mutableStateOf(0.5f) }
    var verContorno by remember { mutableStateOf(true) }
    val density = LocalDensity.current

    fun ajustar() {
        if (tam.width <= 0 || tam.height <= 0) return
        val e = Proyeccion.escalaParaAjustar(marco.anchoM, marco.altoM, tam.width.toFloat(), tam.height.toFloat())
        cam.escala = e
        cam.dx = (tam.width - marco.anchoM.toFloat() * e) / 2
        cam.dy = (tam.height - marco.altoM.toFloat() * e) / 2
        cam.minEscala = e * 0.4f
        cam.maxEscala = e * 40f
    }

    /** Cambia el zoom dejando quieto el punto [centro] de la pantalla (el cursor o el centro del visor). */
    fun zoom(factor: Float, centro: Offset) {
        val nueva = (cam.escala * factor).coerceIn(cam.minEscala, cam.maxEscala)
        val k = nueva / cam.escala
        cam.dx = centro.x - (centro.x - cam.dx) * k
        cam.dy = centro.y - (centro.y - cam.dy) * k
        cam.escala = nueva
    }

    LaunchedEffect(tam, marco) {
        if (!ajustado && tam.width > 0) {
            ajustar()
            ajustado = true
        }
    }

    Box(modifier.clip(RoundedCornerShape(8.dp)).background(FondoVisor).onSizeChanged { tam = it }) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = "Ortomosaico de ${antes.etiqueta}" + (despues?.let { " comparado con ${it.etiqueta}" } ?: "") }
                .pointerInput(marco) { detectDragGestures { _, arrastre -> cam.dx += arrastre.x; cam.dy += arrastre.y } }
                .onPointerEvent(PointerEventType.Scroll) { evento ->
                    val cambio = evento.changes.first()
                    zoom(if (cambio.scrollDelta.y < 0) 1.2f else 1 / 1.2f, cambio.position)
                }
        ) {
            fun DrawScope.dibujar(c: Capa, transparencia: Float = 1f) {
                val r = marco.rect(c.limites)
                drawImage(
                    c.imagen,
                    dstOffset = IntOffset((r.x * cam.escala + cam.dx).roundToInt(), (r.y * cam.escala + cam.dy).roundToInt()),
                    dstSize = IntSize((r.ancho * cam.escala).roundToInt().coerceAtLeast(1), (r.alto * cam.escala).roundToInt().coerceAtLeast(1)),
                    alpha = transparencia,
                    filterQuality = FilterQuality.Medium
                )
            }
            dibujar(antes)
            if (despues != null) {
                when (modo) {
                    ModoComparar.CORTINA -> {
                        val x = divisor * size.width
                        clipRect(left = x) { dibujar(despues) }
                        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                    }
                    ModoComparar.TRANSPARENCIA -> dibujar(despues, alfa)
                }
            }
            if (verContorno && contorno.size >= 3) {
                val camino = Path()
                contorno.forEachIndexed { i, (lat, lon) ->
                    val px = (marco.x(lon) * cam.escala + cam.dx).toFloat()
                    val py = (marco.y(lat) * cam.escala + cam.dy).toFloat()
                    if (i == 0) camino.moveTo(px, py) else camino.lineTo(px, py)
                }
                camino.close()
                drawPath(camino, Color.White, style = Stroke(width = 5f))
                drawPath(camino, Green, style = Stroke(width = 2.5f))
            }
        }

        // La línea de la cortina se agarra desde un asa con área grande: así no hay que acertar en una raya de 2 píxeles
        if (despues != null && modo == ModoComparar.CORTINA && tam.width > 0) {
            Box(
                Modifier.offset { IntOffset((divisor * tam.width).roundToInt() - 18.dp.roundToPx(), 0) }
                    .width(36.dp).fillMaxHeight()
                    .draggable(rememberDraggableState { d -> divisor = (divisor + d / tam.width).coerceIn(0.02f, 0.98f) }, Orientation.Horizontal)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics { contentDescription = "Línea de comparación: arrástrala a izquierda o derecha" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.SwapHoriz, null, Modifier.size(28.dp).clip(CircleShape).background(Color.White).padding(3.dp), tint = Green)
            }
        }

        // Qué se está viendo (arriba a la izquierda)
        Column(Modifier.align(Alignment.TopStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (despues == null) {
                Etiqueta(antes.etiqueta)
            } else {
                Etiqueta("Antes · ${antes.etiqueta}")
                Etiqueta("Después · ${despues.etiqueta}")
                Etiqueta(modo.ayuda, secundaria = true)
            }
        }

        // Controles (arriba a la derecha)
        Column(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BotonIcono(Icons.Filled.Add, "Acercar") { zoom(1.4f, Offset(tam.width / 2f, tam.height / 2f)) }
                BotonIcono(Icons.Filled.Remove, "Alejar") { zoom(1 / 1.4f, Offset(tam.width / 2f, tam.height / 2f)) }
                BotonIcono(Icons.Filled.CenterFocusStrong, "Ajustar a la pantalla") { ajustar() }
            }
            if (despues != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModoComparar.entries.forEach { m ->
                        Text(
                            m.etiqueta,
                            Modifier.clip(RoundedCornerShape(50)).background(if (m == modo) Green else Color(0xCCFFFFFF)).clickable { modo = m }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            fontSize = 11.sp, color = if (m == modo) Color.White else TextDark, fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (modo == ModoComparar.TRANSPARENCIA) {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCCFFFFFF)).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Antes", fontSize = 10.sp, color = TextDark)
                        Slider(
                            alfa, { alfa = it }, Modifier.width(130.dp),
                            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green)
                        )
                        Text("Después", fontSize = 10.sp, color = TextDark)
                    }
                }
            }
        }

        // Barra de escala (abajo a la izquierda) y contorno (abajo a la derecha)
        val metros = Proyeccion.largoBarraM(cam.escala, maxPx = 140f)
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Etiqueta(if (metros >= 1) "${metros.roundToInt()} m" else "${(metros * 100).roundToInt()} cm")
            Spacer(Modifier.height(2.dp))
            Box(Modifier.width(with(density) { (metros * cam.escala).toFloat().toDp() }).height(4.dp).background(Color.White))
        }
        if (contorno.size >= 3) {
            Text(
                (if (verContorno) "✓ " else "") + "Contorno del lote",
                Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(50))
                    .background(if (verContorno) Green else Color(0xCCFFFFFF)).clickable { verContorno = !verContorno }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                fontSize = 11.sp, color = if (verContorno) Color.White else TextDark, fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun Etiqueta(texto: String, secundaria: Boolean = false) {
    Text(
        texto,
        Modifier.clip(RoundedCornerShape(4.dp)).background(Color(if (secundaria) 0x99000000 else 0xCC111827)).padding(horizontal = 8.dp, vertical = 3.dp),
        fontSize = 11.sp, color = Color.White
    )
}

@Composable
private fun BotonIcono(icono: androidx.compose.ui.graphics.vector.ImageVector, descripcion: String, alHacerClic: () -> Unit) {
    Tip(descripcion) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Color(0xE6FFFFFF)).clickable(onClick = alHacerClic)
                .semantics { contentDescription = descripcion },
            contentAlignment = Alignment.Center
        ) { Icon(icono, null, Modifier.size(18.dp), tint = TextDark) }
    }
}
