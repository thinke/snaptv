import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The version comes from the release tag in CI (SNAPTV_VERSION_NAME), like the Android app's.
val snaptvVersion: Provider<String> = providers.environmentVariable("SNAPTV_VERSION_NAME").orElse("0.0.0-dev")
val generateBuildInfo by tasks.registering {
    inputs.property("version", snaptvVersion)
    outputs.dir(layout.buildDirectory.dir("generated/buildinfo"))
    doLast {
        val version = inputs.properties["version"] as String
        val f = outputs.files.singleFile.resolve("io/github/thinke/snaptv/desktop/BuildInfo.kt")
        f.parentFile.mkdirs()
        f.writeText("package io.github.thinke.snaptv.desktop\n\nconst val VERSION = \"$version\"\n")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    // Same visualizer as the TV app.
    sourceSets.getByName("main").kotlin.srcDir("../shared/visuals")
    sourceSets.getByName("main").kotlin.srcDir(generateBuildInfo)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(libs.jna)
    implementation(libs.jmdns)
    implementation(libs.kotlinx.serialization.json)
    // KDE's own tray protocol (StatusNotifierItem over D-Bus), for a transparent icon and native menu.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)
    runtimeOnly(libs.slf4j.nop) // dbus-java logs through SLF4J; we don't need its logs
}

compose.desktop {
    application {
        mainClass = "io.github.thinke.snaptv.desktop.MainKt"
        // A small heap is plenty (audio buffers are a few MB); Serial GC has the least overhead
        // for one busy thread, and the free-ratio flags let it give unused heap back to the OS.
        jvmArgs += listOf(
            "-Xmx128m",
            "-XX:+UseSerialGC",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:ReservedCodeCacheSize=48m",
            "-Xss512k",
        )
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "snaptv-desktop"
            packageVersion = snaptvVersion.get().substringBefore('-').let { if (it == "0.0.0") "0.0.1" else it }
            description = "SnapTV Desktop: Snapcast room with a visualizer"
            // The trimmed runtime only has the modules listed here: JmDNS needs java.naming,
            // JNA jdk.unsupported, dbus-java jdk.security.auth (to read the Unix user id).
            modules("java.naming", "jdk.unsupported", "jdk.security.auth")
        }
    }
}

// Dev tool: renders every visualizer style to PNGs (see RenderVisuals.kt).
tasks.register<JavaExec>("renderVisuals") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.thinke.snaptv.desktop.RenderVisualsKt")
    args(providers.gradleProperty("out").orElse(layout.buildDirectory.dir("visuals").get().asFile.path).get())
}
