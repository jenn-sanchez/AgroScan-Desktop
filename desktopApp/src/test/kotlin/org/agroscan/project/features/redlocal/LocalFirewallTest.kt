package org.agroscan.project.features.redlocal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalFirewallTest {

    @Test fun lasReglasSonEstrechasYEscapanLaRuta() {
        val script = LocalFirewall.ruleScript("""C:\Users\O'Brien\AgroScan\AgroScan.exe""")
        // Una regla por puerto: telemetría (UDP) y registros de vuelo (TCP)
        assertTrue("-Protocol UDP -LocalPort 8765" in script, script)
        assertTrue("-Protocol TCP -LocalPort 8766" in script, script)
        assertEquals(2, Regex("-LocalAddress 192\\.168\\.137\\.1 -RemoteAddress 192\\.168\\.137\\.0/24").findAll(script).count())
        assertEquals(2, Regex("""-Program 'C:\\Users\\O''Brien\\AgroScan\\AgroScan\.exe'""").findAll(script).count())   // comilla escapada
        assertEquals(2, Regex("Remove-NetFirewallRule").findAll(script).count())   // las reglas viejas de AgroScan se borran antes
    }

    @Test fun elNombreDeLaReglaDependeDelProgramaSinImportarMayusculas() {
        val a = LocalFirewall.ruleName("AgroScan telemetria", """C:\Users\ASUS\AgroScan\AgroScan.exe""")
        assertTrue(a.matches(Regex("AgroScan telemetria [0-9a-f]{8}")), a)
        assertEquals(a, LocalFirewall.ruleName("AgroScan telemetria", """c:\users\asus\agroscan\agroscan.exe"""))   // Windows guarda la ruta en minúsculas
        assertTrue(a != LocalFirewall.ruleName("AgroScan telemetria", """C:\Program Files\AgroScan\AgroScan.exe"""))  // otro lugar → otra regla
    }
}
