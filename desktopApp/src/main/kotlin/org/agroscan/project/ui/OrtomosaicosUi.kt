package org.agroscan.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agroscan.project.core.DataFolder
import org.agroscan.project.core.Navegacion
import org.agroscan.project.features.flightlog.FlightLogServer
import org.agroscan.project.features.login.Auth
import org.agroscan.project.features.ortomosaico.Armado
import org.agroscan.project.features.ortomosaico.Cierre
import org.agroscan.project.features.ortomosaico.Carga
import org.agroscan.project.features.ortomosaico.Cargas
import org.agroscan.project.features.ortomosaico.OrtomosaicoAcciones
import org.agroscan.project.features.preproceso.Preprocesamiento

/** Cómo se ve un mensaje: información que se va sola, advertencia o error que se queda hasta que se cierra. */
internal enum class Tono(val fondo: Color, val texto: Color) {
    OK(SelectedBg, Green),
    NEUTRO(Color(0xFFE5E7EB), Color(0xFF374151)),
    ALERTA(Color(0xFFFEF3C7), Color(0xFF92400E)),
    ERROR(Color(0xFFFEE2E2), Color(0xFFB91C1C))
}

private class Mensaje(val texto: String, val tono: Tono)

/**
 * Ortomosaicos: la biblioteca de los ya creados, con el historial de cada lote para ver cómo avanza el cultivo (pestaña **Creados**), y
 * la preparación de uno nuevo a partir de un vuelo ya preprocesado (pestaña **Crear**). La pestaña y lo elegido viven en
 * [Navegacion], así «Crear ortomosaico» en Procesamiento llega aquí con el vuelo ya elegido y al volver se sigue donde se dejó.
 */
@Composable
fun OrtomosaicosUi() {
    val usuario by Auth.usuario.collectAsState()
    val usuarioId = usuario?.id ?: return
    val carpeta by DataFolder.current.collectAsState()
    val recibido by FlightLogServer.received.collectAsState()
    val preprocesando = Preprocesamiento.estado.collectAsState().value.activo
    val armado by Armado.estado.collectAsState()
    var cancelando by remember { mutableStateOf(false) }
    val vista by Navegacion.ortomosaicos.collectAsState()
    var refresco by remember { mutableStateOf(0) }
    var mensaje by remember { mutableStateOf<Mensaje?>(null) }
    val scope = rememberCoroutineScope()

    // Se vuelve a leer cuando llega un vuelo, termina (o empieza) un preprocesamiento o cambia la carpeta; entre tanto se sigue viendo lo anterior
    val carga by produceState<Result<Carga>?>(null, carpeta, usuarioId, recibido, preprocesando, armado.activo, refresco) {
        value = withContext(Dispatchers.IO) { runCatching { Cargas.leer(usuarioId) } }
    }
    LaunchedEffect(mensaje) {
        if (mensaje?.tono == Tono.OK) {
            delay(8_000)
            mensaje = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ortomosaicos", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            PhoneStatusPill()
            Spacer(Modifier.width(12.dp))
            UserAvatar()
        }
        val datos = carga?.getOrNull()
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp)) {
            val creados = datos?.biblioteca?.lotes?.sumOf { it.ortomosaicos.size }
            val porArmar = datos?.vuelos?.count { it.estado == org.agroscan.project.features.ortomosaico.EstadoVuelo.LISTO }
            Pestana("Creados" + (creados?.let { " ($it)" } ?: ""), vista.pestana == Navegacion.Pestana.CREADOS) {
                Navegacion.elegirPestana(Navegacion.Pestana.CREADOS)
            }
            Pestana("Crear" + (porArmar?.takeIf { it > 0 }?.let { " ($it listos)" } ?: ""), vista.pestana == Navegacion.Pestana.CREAR) {
                Navegacion.elegirPestana(Navegacion.Pestana.CREAR)
            }
        }
        HorizontalDivider(color = Border)

        if (armado.activo) ArmandoEnCurso(armado) { cancelando = true }
        armado.cierre?.let { c ->
            val avisos = c.advertencias.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = " ").orEmpty()
            val verlo = c.resultado == Cierre.Resultado.LISTO && c.loteUuid != null
            Aviso(
                c.texto + avisos,
                when { c.resultado == Cierre.Resultado.ERROR -> Tono.ERROR; c.resultado == Cierre.Resultado.CANCELADO -> Tono.NEUTRO; c.advertencias.isNotEmpty() -> Tono.ALERTA; else -> Tono.OK },
                alCerrar = Armado::descartarCierre,
                accion = if (verlo) "Verlo" else null,
                alAccionar = { Navegacion.verOrtomosaicos(c.loteUuid); Armado.descartarCierre() }
            )
        }
        mensaje?.let { Aviso(it.texto, it.tono) { mensaje = null } }
        datos?.biblioteca?.aviso?.let { Aviso(it, Tono.ALERTA) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                carga == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Green, strokeWidth = 3.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("Cargando ortomosaicos…", fontSize = 12.sp, color = TextMuted)
                }
                datos == null -> EstadoVacio(
                    Icons.Filled.ErrorOutline, "No se pudieron cargar los ortomosaicos",
                    "Algo falló al leer la carpeta de datos: ${carga?.exceptionOrNull()?.message ?: "error desconocido"}. Revisa que la carpeta exista y que tengas permiso para leerla.",
                    "Reintentar"
                ) { refresco++ }
                vista.pestana == Navegacion.Pestana.CREADOS -> CreadosPanel(
                    biblioteca = datos.biblioteca,
                    loteElegido = vista.loteUuid,
                    onElegirLote = Navegacion::elegirLote,
                    onIrACrear = { Navegacion.elegirPestana(Navegacion.Pestana.CREAR) },
                    onEliminar = { entrada ->
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { OrtomosaicoAcciones.eliminar(entrada) }
                            mensaje = r.fold(
                                { Mensaje(it.mensaje(entrada.ficha), if (it.avisoBase == null) Tono.OK else Tono.ALERTA) },
                                { Mensaje("No se pudo eliminar el ortomosaico. ${it.message}", Tono.ERROR) }
                            )
                            refresco++
                        }
                    }
                )
                else -> CrearPanel(
                    vuelos = datos.vuelos,
                    vueloElegido = vista.vueloUuid,
                    onElegirVuelo = Navegacion::elegirVuelo,
                    espacioLibreBytes = datos.espacioLibreBytes,
                    ocupado = preprocesando || armado.activo,
                    armadoNoDisponible = datos.armadoNoDisponible,
                    onArmar = { solicitud ->
                        Armado.iniciar(solicitud, usuarioId).onFailure { mensaje = Mensaje(it.message ?: "No se pudo armar el ortomosaico.", Tono.ERROR) }
                    },
                    onIrAProcesamiento = { Navegacion.ir(Navegacion.PROCESAMIENTO) },
                    onVerCreado = { Navegacion.verOrtomosaicos(it) }
                )
            }
        }
    }

    if (cancelando) {
        AlertDialog(
            onDismissRequest = { cancelando = false },
            title = { Text("¿Cancelar el armado?", fontSize = 17.sp) },
            text = {
                Text(
                    "Va por el ${armado.porcentaje} %. Se pierde lo avanzado y habría que empezar de nuevo. " +
                        "Si este vuelo ya tenía un ortomosaico, se conserva.",
                    fontSize = 13.sp
                )
            },
            confirmButton = { Button(onClick = { cancelando = false; Armado.cancelar() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))) { Text("Cancelar armado") } },
            dismissButton = { TextButton(onClick = { cancelando = false }) { Text("Seguir armando", color = TextDark) } },
            containerColor = Color.White
        )
    }
}

/** El armado en marcha, visible en las dos pestañas: qué se arma, en qué etapa va, cuánto falta y cómo pararlo. */
@Composable
private fun ArmandoEnCurso(estado: Armado.Estado, alCancelar: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(SelectedBg).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Armando ortomosaico" + estado.titulo.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDark
            )
            Text("${estado.etapa} · ${estado.porcentaje} %", fontSize = 11.sp, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { estado.porcentaje / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = Green, trackColor = Color.White
            )
        }
        Spacer(Modifier.width(16.dp))
        OutlinedButton(onClick = alCancelar, shape = RoundedCornerShape(8.dp)) { Text("Cancelar", color = TextDark) }
    }
}

@Composable
private fun Pestana(texto: String, seleccionada: Boolean, alElegir: () -> Unit) {
    Column(
        Modifier.selectable(seleccionada, role = Role.Tab, onClick = alElegir).padding(horizontal = 14.dp).padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            texto, fontSize = 13.sp, fontWeight = if (seleccionada) FontWeight.SemiBold else FontWeight.Normal,
            color = if (seleccionada) Green else TextMuted
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.height(2.dp).fillMaxWidth().background(if (seleccionada) Green else Color.Transparent))
    }
}

/** Una franja con un mensaje: qué pasó y, si hace falta, qué hacer. Con [alCerrar] se puede quitar; con [accion], un atajo a lo siguiente. */
@Composable
internal fun Aviso(texto: String, tono: Tono, alCerrar: (() -> Unit)? = null, accion: String? = null, alAccionar: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(tono.fondo).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when (tono) { Tono.ERROR -> Icons.Filled.ErrorOutline; Tono.ALERTA -> Icons.Filled.Warning; else -> Icons.Filled.Info },
            null, Modifier.size(16.dp), tint = tono.texto
        )
        Spacer(Modifier.width(8.dp))
        Text(texto, Modifier.weight(1f), fontSize = 12.sp, color = tono.texto)
        if (accion != null) {
            TextButton(onClick = alAccionar, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text(accion, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tono.texto)
            }
        }
        if (alCerrar != null) {
            Icon(Icons.Filled.Close, "Cerrar este mensaje", Modifier.size(18.dp).clickable(onClick = alCerrar), tint = tono.texto)
        }
    }
}

/** Una etiqueta redondeada con el estado de algo. */
@Composable
internal fun Insignia(texto: String, tono: Tono) {
    Text(
        texto, Modifier.clip(RoundedCornerShape(50)).background(tono.fondo).padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 10.sp, color = tono.texto
    )
}

/** Lo que se ve cuando no hay nada que mostrar: qué es este espacio y qué hacer para llenarlo (no solo «vacío»). */
@Composable
internal fun EstadoVacio(icono: ImageVector, titulo: String, texto: String, accion: String? = null, alAccionar: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icono, null, Modifier.size(44.dp), tint = Border)
        Spacer(Modifier.height(10.dp))
        Text(titulo, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(texto, Modifier.widthIn(max = 420.dp), fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center)
        if (accion != null) {
            Spacer(Modifier.height(14.dp))
            Button(onClick = alAccionar, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text(accion) }
        }
    }
}
