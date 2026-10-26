package org.agroscan.project.features.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthTest {

    @Test
    fun cedulasValidas() {
        // Dígito verificador calculado aparte; 1710034065 es una cédula de ejemplo conocida.
        listOf("1710034065", "1312345679", "0912345675", "2400000010", "3012345678").forEach {
            assertTrue(Auth.cedulaValida(it), it)
        }
    }

    @Test
    fun cedulasInvalidas() {
        listOf(
            "1710034064",  // dígito verificador incorrecto
            "171003406",   // 9 dígitos
            "17100340655", // 11 dígitos
            "2512345678",  // provincia 25 no existe
            "0012345678",  // provincia 00
            "1762345678",  // tercer dígito ≥ 6 (RUC de sociedad, no persona)
            "17100340a5",
            ""
        ).forEach { assertFalse(Auth.cedulaValida(it), it) }
    }

    @Test
    fun hashVerificaSoloLaContrasenaCorrecta() {
        val guardado = Auth.hash("clave-de-prueba")
        assertTrue(guardado.startsWith("pbkdf2$"))
        assertFalse("clave-de-prueba" in guardado)
        assertTrue(Auth.verificar("clave-de-prueba", guardado))
        assertFalse(Auth.verificar("otra-clave-1", guardado))
        assertFalse(Auth.verificar("clave-de-prueba", "texto-plano"))
        assertNotEquals(guardado, Auth.hash("clave-de-prueba")) // sal distinta cada vez
    }

    @Test
    fun validacionDelRegistro() {
        assertNull(Auth.validarRegistro("Ana Pérez", "1710034065", "clave1234", "clave1234"))
        assertEquals("Las contraseñas no coinciden", Auth.validarRegistro("Ana", "1710034065", "clave1234", "clave1235"))
        assertEquals("La cédula no es válida", Auth.validarRegistro("Ana", "1710034064", "clave1234", "clave1234"))
        assertEquals("Escribe tu nombre", Auth.validarRegistro(" ", "1710034065", "clave1234", "clave1234"))
        assertTrue(Auth.validarRegistro("Ana", "1710034065", "corta", "corta")!!.contains("8"))
    }
}
