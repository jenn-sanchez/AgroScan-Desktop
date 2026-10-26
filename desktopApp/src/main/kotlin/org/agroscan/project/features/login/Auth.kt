package org.agroscan.project.features.login

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import java.util.prefs.Preferences
import org.agroscan.project.core.Database
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Cuentas y sesión. El usuario se identifica con su cédula (única en la tabla `usuario`) y una contraseña.
 *
 * - Sesión persistente y sin conexión (como el modo offline de Steam): la base solo se usa al iniciar sesión o
 *   registrarse. Después el usuario (id, cédula, nombre) queda guardado en la PC (Preferences de Java, en el registro
 *   del usuario de Windows) y la app lo restaura al abrirse sin consultar la base. Cerrar sesión lo borra.
 *   No se valida contra la base a propósito: quien usa esta cuenta de Windows ya tiene la conexión a la base
 *   (AGROSCAN_DB_URL), así que hacerlo no agregaría seguridad y sí impediría entrar sin conexión.
 * - La contraseña se guarda como hash PBKDF2-SHA256 con sal aleatoria, nunca en texto plano:
 *   `pbkdf2$<iteraciones>$<sal base64>$<hash base64>` en `usuario.password_hash`.
 * - Funciones bloqueantes (base de datos + hash lento a propósito): llamarlas fuera del hilo de UI.
 */
object Auth {
    data class Usuario(val id: UUID, val cedula: String, val nombre: String)

    private val prefs = Preferences.userRoot().node("org/agroscan/desktop/sesion")

    private val _usuario = MutableStateFlow(guardado())
    /** Usuario con sesión iniciada (restaurada de la PC al abrir la app), o null. */
    val usuario: StateFlow<Usuario?> = _usuario

    const val MIN_PASSWORD = 8

    fun iniciarSesion(cedula: String, password: String): Result<Usuario> = runCatching {
        val fila = Database.connect().use { c ->
            c.prepareStatement("SELECT id, nombre, password_hash, activo FROM usuario WHERE cedula = ?").use { st ->
                st.setString(1, cedula.trim())
                st.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else listOf(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getBoolean(4))
                }
            }
        }
        // Mismo mensaje para cédula inexistente y contraseña errónea: no revela qué cédulas tienen cuenta.
        if (fila == null || !verificar(password, fila[2] as String)) error("Cédula o contraseña incorrecta")
        if (fila[3] != true) error("Esta cuenta está desactivada")
        Usuario(fila[0] as UUID, cedula.trim(), fila[1] as String).also(::abrirSesion)
    }

    fun registrar(nombre: String, cedula: String, password: String, repetir: String): Result<Usuario> = runCatching {
        validarRegistro(nombre, cedula, password, repetir)?.let { error(it) }
        val id = try {
            Database.connect().use { c ->
                c.prepareStatement("INSERT INTO usuario (cedula, nombre, password_hash) VALUES (?, ?, ?) RETURNING id").use { st ->
                    st.setString(1, cedula.trim())
                    st.setString(2, nombre.trim())
                    st.setString(3, hash(password))
                    st.executeQuery().use { rs -> rs.next(); rs.getObject(1, UUID::class.java) }
                }
            }
        } catch (e: SQLException) {
            if (e.sqlState == "23505") error("Ya existe una cuenta con esa cédula") else throw e // 23505 = violación de UNIQUE
        }
        Usuario(id, cedula.trim(), nombre.trim()).also(::abrirSesion)
    }

    fun cerrarSesion() {
        prefs.clear()
        _usuario.value = null
    }

    private fun abrirSesion(u: Usuario) {
        prefs.put("id", u.id.toString())
        prefs.put("cedula", u.cedula)
        prefs.put("nombre", u.nombre)
        prefs.flush()
        _usuario.value = u
    }

    private fun guardado(): Usuario? = runCatching {
        Usuario(UUID.fromString(prefs.get("id", null)), prefs.get("cedula", null)!!, prefs.get("nombre", null)!!)
    }.getOrNull()


    /** Mensaje del primer error del formulario de registro, o null si es válido. */
    fun validarRegistro(nombre: String, cedula: String, password: String, repetir: String): String? = when {
        nombre.isBlank() -> "Escribe tu nombre"
        !cedulaValida(cedula.trim()) -> "La cédula no es válida"
        password.length < MIN_PASSWORD -> "La contraseña debe tener al menos $MIN_PASSWORD caracteres"
        password != repetir -> "Las contraseñas no coinciden"
        else -> null
    }

    /**
     * Cédula ecuatoriana: 10 dígitos, provincia 01–24 (o 30, ecuatorianos en el exterior), tercer dígito < 6
     * y dígito verificador por módulo 10 (coeficientes 2,1,2,1…; si un producto pasa de 9 se le resta 9).
     */
    fun cedulaValida(cedula: String): Boolean {
        if (cedula.length != 10 || !cedula.all { it in '0'..'9' }) return false
        val d = cedula.map { it - '0' }
        val provincia = d[0] * 10 + d[1]
        if (provincia !in 1..24 && provincia != 30) return false
        if (d[2] >= 6) return false
        val suma = (0 until 9).sumOf { i -> (d[i] * if (i % 2 == 0) 2 else 1).let { if (it > 9) it - 9 else it } }
        return (10 - suma % 10) % 10 == d[9]
    }

    // OWASP recomienda ≥ 600.000 iteraciones para PBKDF2-HMAC-SHA256.
    private const val ITERACIONES = 600_000

    internal fun hash(password: String): String {
        val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val b64 = Base64.getEncoder()
        return "pbkdf2$$ITERACIONES$${b64.encodeToString(sal)}$${b64.encodeToString(pbkdf2(password, sal, ITERACIONES))}"
    }

    internal fun verificar(password: String, guardado: String): Boolean {
        val partes = guardado.split('$')
        if (partes.size != 4 || partes[0] != "pbkdf2") return false
        val b64 = Base64.getDecoder()
        val esperado = b64.decode(partes[3])
        // Comparación en tiempo constante para no filtrar cuántos bytes coinciden.
        return MessageDigest.isEqual(esperado, pbkdf2(password, b64.decode(partes[2]), partes[1].toInt()))
    }

    private fun pbkdf2(password: String, sal: ByteArray, iteraciones: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), sal, iteraciones, 256)).encoded
}
