import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.postgresql:postgresql:42.7.8")

    testImplementation(libs.kotlin.test)
}

tasks.test {
    // Las pruebas no escriben en los registros de la carpeta de datos real (core/EventLog)
    systemProperty("agroscan.eventlog", "off")
}

compose.desktop {
    application {
        mainClass = "org.agroscan.project.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AgroScan"
            packageVersion = "1.0.0"
            description = "AgroScan - Enterprise Drone System"
            vendor = "AgroScan"
            // El instalador trae su propio Java recortado: solo estos módulos además de los básicos. Si se usa una API nueva
            // del JDK, revisar con ./gradlew :desktopApp:suggestRuntimeModules o el .exe fallará al usarla.
            // java.management no lo detecta esa tarea, pero el driver de PostgreSQL lo usa al conectarse.
            modules("java.instrument", "java.management", "java.prefs", "java.sql", "jdk.httpserver", "jdk.unsupported")
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menu = true       // acceso en el menú Inicio
                shortcut = true   // acceso directo en el escritorio
                // No cambiar nunca: así una versión nueva del instalador reemplaza a la anterior en vez de instalarse al lado
                upgradeUuid = "f77a5b96-8b36-425a-965a-2bbc531313cf"
            }
        }
    }
}