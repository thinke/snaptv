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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    // Same visualizer as the TV app.
    sourceSets.getByName("main").kotlin.srcDir("../shared/visuals")
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(libs.jna)
    implementation(libs.jmdns)
}

compose.desktop {
    application {
        mainClass = "io.github.thinke.snaptv.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "snaptv-desktop"
            packageVersion = (System.getenv("SNAPTV_VERSION_NAME") ?: "0.1.0").substringBefore('-')
            description = "SnapTV Desktop: Snapcast room with a visualizer"
            // JNA and JmDNS need these at run time.
            modules("java.naming", "jdk.unsupported")
        }
    }
}
