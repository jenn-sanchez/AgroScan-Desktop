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

// Módulos de Python que usa la app: preprocesamiento de imágenes (preprocesamiento/) y armado de ortomosaicos (ortomosaico/). Programando,
// la app usa su código con el Python de su venv; el instalador los lleva convertidos en agroscan-preproceso.exe y agroscan-ortomosaico.exe,
// con su Python y librerías: quien instala no necesita Python.
// shortcut: solo Windows (venv\Scripts, .exe); para Mac/Linux habría que empaquetarlos en cada sistema.
val modulosRecursos: Provider<Directory> = layout.buildDirectory.dir("modulos")

/** Registra las tareas de un módulo de Python: crear su venv (`preparar<Nombre>`) y empaquetarlo como .exe (`empaquetar<Nombre>`). */
fun moduloPython(carpeta: String, nombre: String, exe: String, descripcion: String): TaskProvider<Exec> {
    val dir: Directory = rootProject.layout.projectDirectory.dir(carpeta)
    val preparar = tasks.register<Exec>("preparar$nombre") {
        group = "agroscan"
        description = "Crea $carpeta/venv con Python 3.12, las librerías del módulo de $descripcion y PyInstaller"
        workingDir(dir)
        commandLine("cmd", "/c", "py -3.12 -m venv venv && venv\\Scripts\\python -m pip install --disable-pip-version-check -r requirements.txt pyinstaller==6.22.3")
    }
    return tasks.register<Exec>("empaquetar$nombre") {
        group = "agroscan"
        description = "Convierte el módulo de $descripcion en $exe.exe para meterlo en el instalador"
        dependsOn(preparar)
        inputs.files(dir.file("main.py"), dir.file("requirements.txt"), dir.dir("src").asFileTree.matching { include("**/*.py") })
        outputs.dir(modulosRecursos.map { it.dir("windows/$exe") })
        workingDir(dir)
        // En recursos/windows: Compose lo copia a los recursos de la app (compose.application.resources.dir, ver ModuloPython).
        // UTF-8 fijo: el .exe no lee PYTHONIOENCODING y su registro saldría con las tildes rotas.
        commandLine(
            dir.file("venv/Scripts/python.exe").asFile, "-m", "PyInstaller", "--noconfirm", "--name", exe,
            "--python-option", "X utf8",
            "--distpath", modulosRecursos.get().dir("windows").asFile, "--workpath", temporaryDir, "--specpath", temporaryDir, "main.py"
        )
    }
}

val empaquetarPreproceso = moduloPython("preprocesamiento", "Preproceso", "agroscan-preproceso", "preprocesamiento")
val empaquetarOrtomosaico = moduloPython("ortomosaico", "Ortomosaico", "agroscan-ortomosaico", "ortomosaicos")

// Solo al armar la app instalable, no en cada ./gradlew run (que también copia los recursos). Como entrada y no solo dependencia:
// Compose no revisa si cambiaron los recursos y dejaría el .exe viejo en el instalador.
val conModulos = setOf("createDistributable", "createReleaseDistributable", "packageMsi", "packageReleaseMsi")
tasks.matching { it.name in conModulos }.configureEach { inputs.files(empaquetarPreproceso, empaquetarOrtomosaico) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { mustRunAfter(empaquetarPreproceso, empaquetarOrtomosaico) }

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
            appResourcesRootDir.set(modulosRecursos)
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