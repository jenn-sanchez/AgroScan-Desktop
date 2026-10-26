package org.agroscan.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agroscan.project.features.login.Auth

/** Avatar de la barra superior: iniciales del usuario y un menú con sus datos y "Cerrar sesión". */
@Composable
internal fun UserAvatar() {
    val usuario by Auth.usuario.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (usuario != null) Green else Color(0xFF374151))
                .clickable { menu = true },
            Alignment.Center
        ) {
            val u = usuario
            if (u == null) Icon(Icons.Filled.Person, "Iniciar sesión", tint = Color.White)
            else Text(iniciales(u.nombre), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            usuario?.let { u ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(u.nombre, fontWeight = FontWeight.SemiBold, color = TextDark)
                    Text("Cédula ${u.cedula}", fontSize = 11.sp, color = TextMuted)
                }
                HorizontalDivider(color = Border)
            }
            DropdownMenuItem(
                text = { Text("Cerrar sesión") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                onClick = { menu = false; Auth.cerrarSesion() }
            )
        }
    }
}

private fun iniciales(nombre: String) =
    nombre.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

@Composable
fun LoginUi() {
    var registrando by remember { mutableStateOf(false) }
    var nombre by remember { mutableStateOf("") }
    var cedula by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repetir by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun enviar() {
        if (busy) return
        error = if (registrando) Auth.validarRegistro(nombre, cedula, password, repetir)
        else if (cedula.isBlank() || password.isEmpty()) "Escribe tu cédula y contraseña" else null
        if (error != null) return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (registrando) Auth.registrar(nombre, cedula, password, repetir) else Auth.iniciarSesion(cedula, password)
            }
            busy = false
            r.onFailure { error = it.message ?: "No se pudo conectar con la base de datos" }
        }
    }

    Column(
        Modifier.fillMaxSize().background(ContentBg).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Card(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            Text(
                if (registrando) "Crear cuenta" else "Iniciar sesión",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextDark
            )
            Text(
                if (registrando) "Regístrate con tu cédula para usar AgroScan." else "Ingresa con tu cédula y contraseña.",
                fontSize = 12.sp, color = TextMuted
            )
            Spacer(Modifier.height(20.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (registrando) LoginField("Nombre completo", nombre, { nombre = it })
                LoginField("Cédula", cedula, { v -> cedula = v.filter(Char::isDigit).take(10) }, numeric = true)
                LoginField("Contraseña", password, { password = it }, secret = true, onDone = ::enviar.takeUnless { registrando })
                if (registrando) LoginField("Repetir contraseña", repetir, { repetir = it }, secret = true, onDone = ::enviar)
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color(0xFFDC2626), fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = ::enviar,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (registrando) "Registrarme" else "Entrar", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (registrando) "¿Ya tienes cuenta? Inicia sesión" else "¿No tienes cuenta? Regístrate",
                Modifier.fillMaxWidth().clickable(enabled = !busy) {
                    registrando = !registrando; error = null; password = ""; repetir = ""
                }.padding(4.dp),
                color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoginField(
    label: String, value: String, onChange: (String) -> Unit,
    numeric: Boolean = false, secret: Boolean = false, onDone: (() -> Unit)? = null
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) "Ocultar" else "Mostrar")
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = when { numeric -> KeyboardType.Number; secret -> KeyboardType.Password; else -> KeyboardType.Text },
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() })
    )
}
