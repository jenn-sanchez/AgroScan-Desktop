package org.agroscan.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.agroscan.project.features.ortomosaico.Accion
import org.agroscan.project.features.ortomosaico.AjustesOrtomosaico
import org.agroscan.project.features.ortomosaico.Calidad
import org.agroscan.project.features.ortomosaico.EstadoVuelo
import org.agroscan.project.features.ortomosaico.Formato
import org.agroscan.project.features.ortomosaico.Nivel
import org.agroscan.project.features.ortomosaico.OrigenImagenes
import org.agroscan.project.features.ortomosaico.Preparacion
import org.agroscan.project.features.ortomosaico.Requisito
import org.agroscan.project.features.ortomosaico.SalidaArmado
import org.agroscan.project.features.ortomosaico.Solicitud
import org.agroscan.project.features.ortomosaico.VueloArmado

private fun EstadoVuelo.tono() = when (this) {
    EstadoVuelo.SIN_PREPROCESAR -> Tono.ALERTA
    EstadoVuelo.LISTO -> Tono.OK
    EstadoVuelo.ARMANDO -> Tono.NEUTRO
    EstadoVuelo.CREADO -> Tono.OK
}

/**
 * Preparar un ortomosaico: a la izquierda los vuelos recibidos y en qué punto está cada uno, a la derecha lo que hace falta para armar
 * el elegido (salidas, revisión previa y ajustes). El botón «Armar» solo se enciende cuando la revisión no encuentra bloqueos, y si está
 * apagado dice por qué a su lado.
 */
@Composable
internal fun CrearPanel(
    vuelos: List<VueloArmado>,
    vueloElegido: String?,
    onElegirVuelo: (String) -> Unit,
    espacioLibreBytes: Long,
    ocupado: Boolean,
    armadoNoDisponible: String?,
    onArmar: (Solicitud) -> Unit,
    onIrAProcesamiento: () -> Unit,
    onVerCreado: (String?) -> Unit
) {
    if (vuelos.isEmpty()) {
        EstadoVacio(
            Icons.Filled.FlightTakeoff, "Todavía no hay vuelos",
            "Los vuelos llegan solos desde el teléfono al terminar cada salida. Cuando llegue uno, aquí podrás preparar su ortomosaico.",
            "Ir a Procesamiento", onIrAProcesamiento
        )
        return
    }
    val vuelo = vuelos.firstOrNull { it.uuid == vueloElegido } ?: vuelos.first()
    Row(Modifier.fillMaxSize()) {
        ListaDeVuelos(vuelos, vuelo.uuid, onElegirVuelo, Modifier.width(300.dp).fillMaxHeight())
        VerticalDivider(color = Border)
        DetalleDelVuelo(vuelo, espacioLibreBytes, ocupado, armadoNoDisponible, onArmar, onIrAProcesamiento, onVerCreado, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun ListaDeVuelos(vuelos: List<VueloArmado>, elegido: String, alElegir: (String) -> Unit, modifier: Modifier) {
    Column(modifier.background(Color.White).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Vuelos", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextMuted, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        vuelos.forEach { v ->
            val sel = v.uuid == elegido
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (sel) SelectedBg else Color.Transparent)
                    .border(1.dp, if (sel) Green else Border, RoundedCornerShape(8.dp))
                    .selectable(sel, role = Role.RadioButton) { alElegir(v.uuid) }.padding(10.dp)
            ) {
                Text(
                    v.lote.ifBlank { "Lote sin nombre" } + (v.mision.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDark, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(Formato.fecha(v.inicioMs), fontSize = 11.sp, color = TextMuted)
                Spacer(Modifier.height(4.dp))
                Insignia(v.estado.etiqueta, v.estado.tono())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetalleDelVuelo(
    vuelo: VueloArmado,
    espacioLibreBytes: Long,
    ocupado: Boolean,
    armadoNoDisponible: String?,
    onArmar: (Solicitud) -> Unit,
    onIrAProcesamiento: () -> Unit,
    onVerCreado: (String?) -> Unit,
    modifier: Modifier
) {
    // La selección vuelve a las salidas listas cada vez que cambia el vuelo o lo que está listo (p. ej. al terminar un preprocesamiento)
    var incluidas by remember(vuelo.uuid, vuelo.incluidasPorDefecto) { mutableStateOf(vuelo.incluidasPorDefecto) }
    val calidad by AjustesOrtomosaico.calidad.collectAsState()
    val imagenes by AjustesOrtomosaico.imagenes.collectAsState()
    val evaluacion = remember(vuelo, incluidas, espacioLibreBytes, ocupado, armadoNoDisponible) {
        Preparacion.evaluar(vuelo.salidas, incluidas, espacioLibreBytes, ocupado, armadoNoDisponible, vuelo.existente)
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(vuelo.lote.ifBlank { "Lote sin nombre" }, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextDark)
            Text(
                listOfNotNull(vuelo.mision.takeIf { it.isNotBlank() }, Formato.fecha(vuelo.inicioMs), vuelo.modo.takeIf { it.isNotBlank() }).joinToString(" · "),
                fontSize = 12.sp, color = TextMuted
            )
        }

        vuelo.existente?.takeIf { it.listo }?.let { creado ->
            Card(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = Green)
                    Spacer(Modifier.width(8.dp))
                    Text("Este vuelo ya tiene su ortomosaico", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                    TextButton(onClick = { onVerCreado(creado.ficha.loteUuid) }) {
                        Text("Verlo en Creados", color = Green, fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = Green)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Titulo(Icons.Filled.FlightTakeoff, "Salidas del vuelo")
            Spacer(Modifier.height(2.dp))
            Text("Marca las que formarán el ortomosaico. Solo se pueden elegir las que tienen registro e imágenes.", fontSize = 11.sp, color = TextMuted)
            Spacer(Modifier.height(8.dp))
            vuelo.salidas.forEach { s ->
                FilaSalida(
                    s, s.indice in incluidas,
                    alCambiar = { marcada -> incluidas = if (marcada) incluidas + s.indice else incluidas - s.indice }
                )
            }
            if (vuelo.salidas.any { !it.lista }) {
                TextButton(onClick = onIrAProcesamiento, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("Preprocesar en Procesamiento", color = Green, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp), tint = Green)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Titulo(Icons.Filled.Tune, "Ajustes")
            Spacer(Modifier.height(8.dp))
            Opciones("Calidad", Calidad.entries, calidad, { it.etiqueta }, { it.ayuda }) { AjustesOrtomosaico.elegir(it) }
            Spacer(Modifier.height(10.dp))
            Opciones("Imágenes de partida", OrigenImagenes.entries, imagenes, { it.etiqueta }, { it.ayuda }) { AjustesOrtomosaico.elegir(it) }
            Spacer(Modifier.height(6.dp))
            Text("Se recuerda lo que elijas para la próxima vez.", fontSize = 10.sp, color = TextMuted)
        }

        Card(Modifier.fillMaxWidth()) {
            Titulo(Icons.Filled.CheckCircle, "Revisión previa")
            Spacer(Modifier.height(8.dp))
            evaluacion.requisitos.forEach { Punto(it, onIrAProcesamiento) }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onArmar(Solicitud(vuelo.uuid, incluidas, calidad, imagenes)) },
                enabled = evaluacion.puedeArmar,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) {
                Icon(Icons.Filled.Layers, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (vuelo.existente != null) "Volver a armar ortomosaico" else "Armar ortomosaico")
            }
            // Un botón apagado sin explicación deja adivinando: aquí se dice qué lo impide
            evaluacion.motivoBloqueo?.let { Text(it.titulo, Modifier.weight(1f), fontSize = 12.sp, color = Color(0xFFB91C1C)) }
        }
    }
}

@Composable
private fun FilaSalida(s: SalidaArmado, marcada: Boolean, alCambiar: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = s.lista) { alCambiar(!marcada) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = marcada, onCheckedChange = alCambiar, enabled = s.lista, colors = CheckboxDefaults.colors(checkedColor = Green))
        Text("Salida ${s.numero}", Modifier.width(80.dp), fontSize = 13.sp, color = if (s.lista) TextDark else TextMuted)
        Chip(s.registro, if (s.registro) "Registro" else "Sin registro")
        Spacer(Modifier.width(6.dp))
        Chip(s.videos > 0, if (s.videos > 0) "${s.videos} ${if (s.videos == 1) "video" else "videos"}" else "Sin video")
        Spacer(Modifier.width(6.dp))
        Chip(s.imagenes > 0, if (s.imagenes > 0) "${s.imagenes} imágenes" else "Sin preprocesar")
    }
}

/** Una elección entre pocas opciones, con la ayuda de la elegida debajo (se ve qué significa sin tener que probar). */
@Composable
private fun <T> Opciones(titulo: String, opciones: List<T>, elegida: T, etiqueta: (T) -> String, ayuda: (T) -> String, alElegir: (T) -> Unit) {
    Text(titulo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opciones.forEach { o ->
            val sel = o == elegida
            Text(
                etiqueta(o),
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) SelectedBg else Color.White)
                    .border(1.dp, if (sel) Green else Border, RoundedCornerShape(8.dp))
                    .selectable(sel, role = Role.RadioButton) { alElegir(o) }.padding(horizontal = 14.dp, vertical = 6.dp),
                fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) Green else TextDark
            )
        }
    }
    Text(ayuda(elegida), fontSize = 11.sp, color = TextMuted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Punto(r: Requisito, alIrAProcesamiento: () -> Unit) {
    val (icono, color) = when (r.nivel) {
        Nivel.OK -> Icons.Filled.CheckCircle to Green
        Nivel.AVISO -> Icons.Filled.Warning to Color(0xFF92400E)
        Nivel.BLOQUEO -> Icons.Filled.ErrorOutline to Color(0xFFB91C1C)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(icono, r.nivel.name, Modifier.size(16.dp).padding(top = 1.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(r.titulo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            r.detalle?.let { Text(it, fontSize = 11.sp, color = TextMuted) }
            r.accion?.let {
                TextButton(onClick = { if (it == Accion.IR_A_PROCESAMIENTO) alIrAProcesamiento() }, contentPadding = PaddingValues(0.dp)) {
                    Text(it.etiqueta, color = Green, fontSize = 11.sp)
                }
            }
        }
    }
}
