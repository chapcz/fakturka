import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.material.icons.core)

    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.xml)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.jsr310)

    implementation(libs.openhtmltopdf.pdfbox)
    implementation(libs.kotlinx.html)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)

    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.pdfbox)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Release version: the CI passes -Pfakturka.version=<tag without "v">; local builds stay at the default.
val appVersion: String = providers.gradleProperty("fakturka.version").getOrElse("1.0.0")
version = appVersion

// The running app reads its version from this generated resource (shown in the UI, compared by the update check).
val generateVersionResource by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val v = appVersion
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val file = out.get().file("fakturka/version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$v\n")
    }
}
sourceSets.main { resources.srcDir(generateVersionResource) }

tasks.test {
    useJUnitPlatform()
    systemProperty("fakturka.preview", providers.gradleProperty("fakturka.preview").getOrElse("false"))
}

// jpackage needs a full JDK (with jmods). Point FAKTURKA_PACKAGING_JDK (or -Pfakturka.packagingJdk=...)
// at one when the default JDK is headless; otherwise a local default is used only if it exists.
val packagingJdk: String? = providers.gradleProperty("fakturka.packagingJdk")
    .orElse(providers.environmentVariable("FAKTURKA_PACKAGING_JDK"))
    .orNull
    ?: File(System.getProperty("user.home"), ".jdks/graalvm-jdk-21.0.4").takeIf { File(it, "jmods").isDirectory }?.path

compose.desktop {
    application {
        mainClass = "cz.chap.fakturka.MainKt"
        packagingJdk?.let { javaHome = it }
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "fakturka"
            packageVersion = appVersion
            modules("java.instrument", "java.sql", "jdk.unsupported", "java.xml", "java.naming", "java.desktop", "java.net.http")
            linux {
                shortcut = true
                menuGroup = "Office"
                iconFile.set(project.file("packaging/fakturka.png"))
            }
        }
    }
}
