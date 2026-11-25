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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.agroscan.project.features.ortomosaico.Limites
import org.agroscan.project.features.ortomosaico.Piramide
import org.agroscan.project.features.ortomosaico.Proyeccion
import kotlin.math.roundToInt

/**
 * Una imagen del mosaico con el terreno que cubre; [etiqueta] es lo que se lee sobre ella ("Antes · 12 sep 2026"). [imagen] es el mosaico entero en pequeño
 * (se ve de inmediato); con [piramide], al acercar el visor dibuja encima las teselas a resolución real de la zona que se mira.
 */
internal class Capa(val etiqueta: String, val imagen: ImageBitmap, val limites: Limites, val piramide: Piramide? = null)

/** Las teselas ya leídas, las últimas usadas (cada una ocupa ~1 MB descomprimida: no se guarda medio mosaico). */
private class CacheTeselas(private val maximo: Int = 160) {
    private data class Clave(val carpeta: String, val posicion: Piramide.Posicion)

    private val mapa = object : LinkedHashMap<Clave, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Clave, ImageBitmap>) = size > maximo
    }

    @Synchronized fun obtener(p: Piramide, pos: Piramide.Posicion): ImageBitmap? = mapa[Clave(p.carpeta.path, pos)]
    @Synchronized fun poner(p: Piramide, pos: Piramide.Posicion, img: ImageBitmap) { mapa[Clave(p.carpeta.path, pos)] = img }
}

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
    val teselas = remember { CacheTeselas() }
    var llegadas by remember { mutableStateOf(0) }       // sube cuando llega una tesela: el dibujo lo lee y se vuelve a pintar

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

    // Al acercar, se leen (fuera del hilo de la interfaz) las teselas de resolución real de la zona que se ve; mientras tanto se ve la vista
    LaunchedEffect(antes, despues, tam) {
        if (tam.width <= 0) return@LaunchedEffect
        snapshotFlow { Triple(cam.escala, cam.dx, cam.dy) }.collectLatest {
            delay(60)       // al arrastrar o hacer zoom no se piden las teselas de cada paso, solo las de donde se queda
            for (c in listOfNotNull(antes, despues)) {
                val pir = c.piramide ?: continue
                val r = marco.rect(c.limites)
                for (t in pir.visibles(r.x * cam.escala + cam.dx, r.y * cam.escala + cam.dy, r.ancho * cam.escala, r.alto * cam.escala, tam.width.toDouble(), tam.height.toDouble())) {
                    if (teselas.obtener(pir, t.posicion) != null) continue
                    val img = withContext(Dispatchers.IO) { cargarImagen(pir.archivo(t.posicion)) } ?: continue
                    teselas.poner(pir, t.posicion, img)
                    llegadas++
                }
            }
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
            @Suppress("UNUSED_VARIABLE") val alLlegarUnaTesela = llegadas      // se lee aquí para que llegue una tesela y se repinte

            /** La vista de siempre y, encima, las teselas ya leídas de la zona que se ve (a su resolución real). */
            fun DrawScope.dibujarOpaco(c: Capa) {
                val r = marco.rect(c.limites)
                val x = r.x * cam.escala + cam.dx
                val y = r.y * cam.escala + cam.dy
                val ancho = r.ancho * cam.escala
                val alto = r.alto * cam.escala
                drawImage(
                    c.imagen,
                    dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                    dstSize = IntSize(ancho.roundToInt().coerceAtLeast(1), alto.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium
                )
                val pir = c.piramide ?: return
                for (t in pir.visibles(x, y, ancho, alto, size.width.toDouble(), size.height.toDouble())) {
                    val img = teselas.obtener(pir, t.posicion) ?: continue
                    // Los bordes se calculan redondeando cada uno, no el ancho: así dos teselas vecinas se tocan sin dejar una raya ni solaparse
                    val izquierda = (x + t.x0 * ancho).roundToInt()
                    val arriba = (y + t.y0 * alto).roundToInt()
                    drawImage(
                        img, dstOffset = IntOffset(izquierda, arriba),
                        dstSize = IntSize(((x + t.x1 * ancho).roundToInt() - izquierda).coerceAtLeast(1), ((y + t.y1 * alto).roundToInt() - arriba).coerceAtLeast(1)),
                        filterQuality = FilterQuality.Medium
                    )
                }
            }

            fun DrawScope.dibujar(c: Capa, transparencia: Float = 1f) {
                if (transparencia >= 0.999f) return dibujarOpaco(c)
                // Con transparencia se dibuja la vista y sus teselas juntas en una capa y se mezcla la capa: si cada una se mezclara por su lado, la tesela dejaría ver la vista de abajo
                val canvas = drawContext.canvas
                canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = transparencia })
                dibujarOpaco(c)
                canvas.restore()
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
