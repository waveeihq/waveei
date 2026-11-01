// Plain Kotlin: no Android, iOS or server dependencies.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Golden cases live at the repository root so that a future Swift port can share them.
    val goldenDir = rootProject.layout.projectDirectory.dir("golden")
    inputs.dir(goldenDir).withPropertyName("golden")
    systemProperty("golden.dir", goldenDir.asFile.absolutePath)
}
