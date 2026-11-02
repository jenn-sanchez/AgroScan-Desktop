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

// Módulo de preprocesamiento de imágenes (Python, en preprocesamiento/). Programando, la app usa su código con el Python de su venv;
// el instalador lo lleva convertido en agroscan-preproceso.exe, con su Python y librerías: quien instala no necesita Python.
// shortcut: solo Windows (venv\Scripts, .exe); para Mac/Linux habría que empaquetarlo en cada sistema.
val preproceso: Directory = rootProject.layout.projectDirectory.dir("preprocesamiento")
val preprocesoRecursos: Provider<Directory> = layout.buildDirectory.dir("preproceso")

val prepararPreproceso by tasks.registering(Exec::class) {
    group = "agroscan"
    description = "Crea preprocesamiento/venv con Python 3.12, las librerías del módulo y PyInstaller"
    workingDir(preproceso)
    commandLine("cmd", "/c", "py -3.12 -m venv venv && venv\\Scripts\\python -m pip install --disable-pip-version-check -r requirements.txt pyinstaller==6.22.3")
}

val empaquetarPreproceso by tasks.registering(Exec::class) {
    group = "agroscan"
    description = "Convierte el módulo en agroscan-preproceso.exe para meterlo en el instalador"
    dependsOn(prepararPreproceso)
    inputs.files(preproceso.file("main.py"), preproceso.file("requirements.txt"), preproceso.dir("src").asFileTree.matching { include("**/*.py") })
    outputs.dir(preprocesoRecursos)
    workingDir(preproceso)
    // En recursos/windows: Compose lo copia a los recursos de la app (compose.application.resources.dir, ver PreprocessModule).
    // UTF-8 fijo: el .exe no lee PYTHONIOENCODING y su registro saldría con las tildes rotas.
    commandLine(
        preproceso.file("venv/Scripts/python.exe").asFile, "-m", "PyInstaller", "--noconfirm", "--name", "agroscan-preproceso",
        "--python-option", "X utf8",
        "--distpath", preprocesoRecursos.get().dir("windows").asFile, "--workpath", temporaryDir, "--specpath", temporaryDir, "main.py"
    )
}

// Solo al armar la app instalable, no en cada ./gradlew run (que también copia los recursos). Como entrada y no solo dependencia:
// Compose no revisa si cambiaron los recursos y dejaría el .exe viejo en el instalador.
val conPreproceso = setOf("createDistributable", "createReleaseDistributable", "packageMsi", "packageReleaseMsi")
tasks.matching { it.name in conPreproceso }.configureEach { inputs.files(empaquetarPreproceso) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { mustRunAfter(empaquetarPreproceso) }

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
            appResourcesRootDir.set(preprocesoRecursos)
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