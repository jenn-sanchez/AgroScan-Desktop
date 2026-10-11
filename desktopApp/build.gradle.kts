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
}

compose.desktop {
    application {
        mainClass = "org.agroscan.project.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.agroscan.project"
            packageVersion = "1.0.0"
            windows.iconFile.set(project.file("icons/icon.ico"))
        }
    }
}